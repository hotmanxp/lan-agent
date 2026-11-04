#!/usr/bin/env node
/**
 * 构建「本地引擎包」—— 让 APK 里的 :engine 进程能跑 agent 引擎的那一坨文件。
 *
 * ## 它不进 APK(0.29.0 起)
 *
 * 引擎包(13MB)现在**由用户在设置页点「下载引擎」时按需拉取**,不再打进 APK ——
 * APK 只留纯客户端(~13MB)。产物落在 `dist/engine/`,再由
 * `tools/pack-engine-bundle.mjs` 跟原生库一起打成 zip 供下载。
 * 所以这个脚本的输出目录在仓库外(gitignore 掉的 `dist/`),不是 `app/src/main/assets`。
 *
 * ## 产物布局(这个布局不是随便定的,每一行都有理由)
 *
 *   <out>/
 *   ├── server.mjs                     引擎入口:起 zai 的 HTTP 服务,只监听 127.0.0.1
 *   ├── zai-server.mjs                 esbuild 打包出来的 zai server
 *   ├── engine.sha256                  整棵树的指纹,App 侧用它决定要不要重新解压
 *   └── node_modules/
 *       ├── @zn-ai/zn-agent-core/      ← **必须外置**,见下面「为什么 core 不能进 bundle」
 *       │   ├── package.json
 *       │   └── opencc-core.mjs
 *       ├── zod/                       core 的静态第三方依赖(它静态 import 了 108 次 zod/v4)
 *       ├── fflate/                    core 的动态 import 依赖
 *       └── sharp/                     **手机端替身**:sharp 是 external,这里放什么
 *                                       就是什么(见 tools/engine-shim/sharp/)
 *
 * ## 为什么 core 不能进 bundle
 *
 * core bundle 用 `import.meta.resolve('@zn-ai/zn-agent-core')` **自引用**
 * (opencc-web/packages/zn-agent-core/src/compat/tools/opencc/builtin.ts:40、
 * src/compat/openccInit.ts:49),靠这个拿到「真正的自己」来注册 opencc 内置工具。
 * 一旦把 core 也打进单文件,这个自引用就 resolve 不到了,症状是 agent 工具全废
 * (日志报 `[openccBuiltin] cannot resolve @zn-ai/zn-agent-core`),而服务本身
 * 启动得好好的 —— 属于最难查的那类静默失败。所以 node_modules/@zn-ai/zn-agent-core/
 * 这个目录结构必须原样保留。
 *
 * 同理 zod / zod/v3 / zod/v4 / zod/v4-mini 也必须留在 node_modules 里由 Node
 * 运行时解析,不能被 esbuild 内联进 zai-server.mjs。
 *
 * ## 用法
 *
 *   node tools/build-engine-pkg.mjs                 # 构建 + 自检 + 跑起来验一遍
 *   node tools/build-engine-pkg.mjs --skip-verify   # 只构建(不启动子进程)
 *   node tools/build-engine-pkg.mjs --out <dir>     # 换输出目录
 *
 * 环境变量:
 *   OPENCC_WEB_ROOT   opencc-web 仓库位置(默认 /Users/ethan/code/opencc-web)
 */

import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import http from 'node:http'
import { existsSync, readdirSync, statSync, writeSync } from 'node:fs'
import { cp, copyFile, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..')

/** 钉死的 esbuild 版本 —— 必须跟 opencc-web 里 vite 用的那份同版本,否则打包行为会漂。 */
const ESBUILD_PIN = '0.28.1'

/**
 * 必须留在 bundle 之外的依赖。
 *
 * - @zn-ai/zn-agent-core:自引用(见文件头),bundle 进去 agent 工具全废
 * - zod / zod/v3 / zod/v4:core 静态 import 了 108 次 zod/v4
 * - sharp / node-pty:手机上没有对应的原生库。外置之后:
 *   node-pty 是 lazy require + try/catch → 顶多 503,不影响启动;
 *   sharp 只在图片处理路径动态 import,不影响启动 ——
 *   而且 node_modules/sharp/ 里放的是**我们自己的替身**(tools/engine-shim/sharp/),
 *   把图片处理转给主进程的 Android BitmapFactory,所以大图照样能读。
 */
const EXTERNALS = ['sharp', 'node-pty', '@zn-ai/zn-agent-core', 'zod', 'zod/v3', 'zod/v4']

/**
 * 运行时真正需要的 zod 子路径 —— 少一个就白屏。
 * (core bundle: zod ×14 / zod/v3 ×4 / zod/v4 ×108 / zod/v4-mini ×2)
 */
const ZOD_SUBPATHS = ['v3', 'v4', 'v4-mini']

// ─────────────────────────────────────────────────────────────────────────────
// 参数
// ─────────────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const opt = {
    out: resolve(REPO_ROOT, 'dist/engine'),
    webRoot: process.env.OPENCC_WEB_ROOT || '/Users/ethan/code/opencc-web',
    verify: true,
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--out') {
      opt.out = resolve(process.cwd(), argv[++i] ?? '')
    } else if (a === '--web-root') {
      opt.webRoot = resolve(process.cwd(), argv[++i] ?? '')
    } else if (a === '--skip-verify') {
      opt.verify = false
    } else if (a === '--help' || a === '-h') {
      console.log(
        '用法: node tools/build-engine-pkg.mjs [--out <dir>] [--web-root <dir>] [--skip-verify]\n' +
          `默认输出: ${resolve(REPO_ROOT, 'dist/engine')}\n` +
          `默认源仓库: ${opt.webRoot}`,
      )
      process.exit(0)
    } else {
      fail(`不认识的参数: ${a}`)
    }
  }
  if (!opt.out) fail('--out 需要一个目录')
  return opt
}

function fail(msg) {
  // writeSync 而不是 console.error:stderr 接管道时是异步写,process.exit 会把它截掉,
  // 报错信息就丢了 —— 而「失败要响亮」正是这个脚本的核心要求。
  writeSync(2, `\n✗ 引擎包构建失败: ${msg}\n\n`)
  process.exit(1)
}

/** 退出前把 stdout 排空 —— stdout 接管道时是异步写,直接 exit 会截断日志。 */
async function flushAndExit(code) {
  process.exitCode = code
  await new Promise((r) => {
    if (process.stdout.writableLength === 0) return r()
    process.stdout.write('', r)
  })
  process.exit(code)
}

function info(msg) {
  console.log(msg)
}

// ─────────────────────────────────────────────────────────────────────────────
// 源仓库定位
// ─────────────────────────────────────────────────────────────────────────────

/** 源文件必须在,缺了就当场炸 —— 引擎包坏了却在手机上才炸是最糟的情况。 */
function mustExist(p, what) {
  if (!existsSync(p)) fail(`${what} 不存在: ${p}\n  (opencc-web 仓库位置对不对?用 --web-root 或 OPENCC_WEB_ROOT 指定)`)
  return p
}

function resolveEsbuild(webRoot) {
  const pnpm = join(webRoot, 'node_modules/.pnpm')
  if (!existsSync(pnpm)) fail(`找不到 pnpm store: ${pnpm}\n  先在 opencc-web 里跑 pnpm install`)
  const versions = readdirSync(pnpm)
    .filter((d) => /^esbuild@\d+\.\d+\.\d+$/.test(d))
    .map((d) => d.slice('esbuild@'.length))
    .sort((a, b) => (a === ESBUILD_PIN ? -1 : b === ESBUILD_PIN ? 1 : compareVer(b, a)))
  const picked = versions[0]
  if (!picked) fail(`pnpm store 里没有 esbuild(找的是 esbuild@${ESBUILD_PIN})`)
  if (picked !== ESBUILD_PIN) {
    console.warn(`⚠️ pnpm store 里没有 esbuild@${ESBUILD_PIN},退而用 esbuild@${picked}`)
  }
  const entry = join(pnpm, `esbuild@${picked}`, 'node_modules/esbuild/lib/main.js')
  mustExist(entry, 'esbuild 入口')
  return { entry, version: picked }
}

function compareVer(a, b) {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < 3; i++) {
    if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) - (pb[i] ?? 0)
  }
  return 0
}

// ─────────────────────────────────────────────────────────────────────────────
// 拷贝(必须解引用 pnpm 的 symlink)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 拷目录并**解引用** symlink。
 *
 * pnpm 的 node_modules 是一堆指向 .pnpm store 的软链,直接 cp 进 APK 会得到
 * 一堆断链(目标路径在 APK 里根本不存在),表现是运行时才炸、而且日志里
 * 只是一句 ENOENT。cp -RL 的语义在这里是硬要求。
 */
async function copyTree(src, dest) {
  await rm(dest, { recursive: true, force: true })
  await mkdir(dirname(dest), { recursive: true })
  await cp(src, dest, { recursive: true, dereference: true, force: true, preserveTimestamps: false })
}

/**
 * 砍掉 zod 里的类型声明和 TS 源码。
 *
 * zod 3.25.76 的 exports map 里 `"@zod/source": "./src/index.ts"` 只在
 * Node 显式声明了 `@zod/source` 这个自定义 export condition 时才命中,
 * 运行时永远走不到;`.d.ts` 更是只有 TypeScript 才看。这两坨占了 zod 一半的
 * 体积(5MB → 1.7MB)和 2/3 的文件数(596 → ~180)。
 *
 * 砍完的风险由「真跑一遍」兜底(见 verifyRuntime):起不来就直接构建失败,
 * 不会带着一个半残的包进 APK。
 */
async function pruneZodTypes(zodDir) {
  const removed = []
  const walk = async (dir) => {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const p = join(dir, entry.name)
      if (entry.isDirectory()) {
        if (entry.name === 'src') {
          removed.push(p)
          continue
        }
        await walk(p)
      } else if (/\.d\.(ts|cts|mts)$/.test(entry.name)) {
        removed.push(p)
      }
    }
  }
  await walk(zodDir)
  for (const p of removed) await rm(p, { recursive: true, force: true })
  return removed.length
}

// ─────────────────────────────────────────────────────────────────────────────
// 自检:目录结构
// ─────────────────────────────────────────────────────────────────────────────

async function listSymlinks(dir) {
  const found = []
  const walk = async (d) => {
    for (const entry of await readdir(d, { withFileTypes: true })) {
      const p = join(d, entry.name)
      if (entry.isSymbolicLink()) found.push(p)
      else if (entry.isDirectory()) await walk(p)
    }
  }
  await walk(dir)
  return found
}

/**
 * 校验产物结构。任何一条不过就 fail —— 引擎包坏了却在手机上才炸是最糟的情况。
 * @returns {Promise<{problems: string[], fileCount: number, bytes: number}>}
 */
async function verifyLayout(out) {
  const problems = []
  const need = async (rel, what) => {
    const p = join(out, rel)
    try {
      const s = await stat(p)
      if (!s.isFile() || s.size === 0) problems.push(`${rel} 存在但不是非空文件(${what})`)
      return true
    } catch {
      problems.push(`缺 ${rel}(${what})`)
      return false
    }
  }
  const needDir = async (rel, what) => {
    try {
      const s = await stat(join(out, rel))
      if (!s.isDirectory()) problems.push(`${rel} 不是目录(${what})`)
      return true
    } catch {
      problems.push(`缺目录 ${rel}(${what})`)
      return false
    }
  }

  await need('server.mjs', '引擎入口')
  await need('zai-server.mjs', '打包好的 zai server')
  await need('package.json', '引擎包自己的 manifest(zai 靠它认包根)')
  await need('android-env.cjs', 'AT_SECURE env 修复的 preload(App 侧用 --require 加载)')
  await needDir('node_modules', '运行时依赖')
  await needDir('node_modules/@zn-ai/zn-agent-core', '外置的 agent core')
  await need('node_modules/@zn-ai/zn-agent-core/opencc-core.mjs', 'core bundle')
  await needDir('node_modules/zod', 'zod')
  await needDir('node_modules/fflate', 'fflate')
  await needDir('node_modules/sharp', '手机端 sharp 替身')
  await need('node_modules/sharp/index.mjs', '手机端 sharp 替身')
  await need('node_modules/sharp/package.json', '手机端 sharp 替身')

  // core 的 package.json:main / exports 必须指向真实存在的文件 ——
  // core 是靠 package.json 被 import.meta.resolve 找到的,指错了就是「工具全废」。
  //
  // 引擎包自己的 package.json 同理:名字必须是 '@zn-ai/zai',否则 zai 向上
  // 找包根时找不到它,/api/health 会退回 version '0.0.0'(静默降级,不报错)。
  const enginePkgPath = join(out, 'package.json')
  if (existsSync(enginePkgPath)) {
    try {
      const pkg = JSON.parse(await readFile(enginePkgPath, 'utf-8'))
      if (pkg.name !== '@zn-ai/zai') {
        problems.push(`引擎包 package.json 的 name 应为 '@zn-ai/zai',实际是 ${JSON.stringify(pkg.name)}`)
      }
      if (typeof pkg.version !== 'string' || !pkg.version) {
        problems.push('引擎包 package.json 缺 version')
      }
    } catch (e) {
      problems.push(`引擎包 package.json 解析失败: ${e.message}`)
    }
  }

  const corePkgPath = join(out, 'node_modules/@zn-ai/zn-agent-core/package.json')
  if (existsSync(corePkgPath)) {
    try {
      const pkg = JSON.parse(await readFile(corePkgPath, 'utf-8'))
      const coreDir = dirname(corePkgPath)
      const targets = []
      if (pkg.main) targets.push(['main', pkg.main])
      const dot = pkg.exports?.['.']
      if (typeof dot === 'string') targets.push(['exports["."]', dot])
      else if (dot && typeof dot === 'object') {
        for (const [k, v] of Object.entries(dot)) {
          if (typeof v === 'string') targets.push([`exports["."].${k}`, v])
        }
      }
      if (targets.length === 0) problems.push('core 的 package.json 没有 main / exports["."]')
      for (const [label, target] of targets) {
        if (!existsSync(join(coreDir, target))) {
          problems.push(`core 的 package.json ${label} 指向不存在的文件: ${target}`)
        }
      }
    } catch (e) {
      problems.push(`core 的 package.json 解析失败: ${e.message}`)
    }
  } else {
    problems.push('缺 node_modules/@zn-ai/zn-agent-core/package.json')
  }

  // zod 的子路径:core 静态 import 了 zod/v4 / zod/v4-mini,zai server import 了 zod / zod/v3
  for (const sp of ZOD_SUBPATHS) {
    await needDir(`node_modules/zod/${sp}`, `zod 子路径 ${sp}`)
    if (!existsSync(join(out, 'node_modules/zod', sp, 'index.js'))) {
      problems.push(`zod 的 ${sp} 子路径没有 index.js`)
    }
  }
  if (!existsSync(join(out, 'node_modules/zod/index.js'))) {
    problems.push('zod 根没有 index.js')
  }

  // 断链检查(硬要求):symlink 进了 APK 就是静默失败
  const symlinks = await listSymlinks(out)
  if (symlinks.length > 0) {
    problems.push(
      `产物里有 ${symlinks.length} 个 symlink(断链进 APK 会静默失败),前几个: ` +
        symlinks.slice(0, 5).map((p) => p.replace(out + '/', '')).join(', '),
    )
  }

  // 外置验证:这些 import 必须在 bundle 里**保持外部引用**,
  // 一旦被内联,前面的结构检查全绿但运行时炸。
  const serverSrc = await readFile(join(out, 'zai-server.mjs'), 'utf-8')
  for (const spec of ['@zn-ai/zn-agent-core', 'zod']) {
    if (!new RegExp(`from\\s*['"]${spec.replace('/', '\\/')}['"]`).test(serverSrc)) {
      problems.push(`zai-server.mjs 里没有对外引用 ${spec} —— 它被 bundle 进去了?`)
    }
  }
  const coreSrc = await readFile(join(out, 'node_modules/@zn-ai/zn-agent-core/opencc-core.mjs'), 'utf-8')
  for (const spec of ['zod/v4', 'zod/v4-mini']) {
    if (!new RegExp(`from\\s*['"]${spec.replace('/', '\\/')}['"]`).test(coreSrc)) {
      problems.push(`core bundle 里没有对外引用 ${spec} —— 它被 bundle 进去了?`)
    }
  }
  // 自引用必须留着 —— 这是 core 找回自己的唯一途径
  if (!coreSrc.includes('import.meta.resolve')) {
    problems.push('core bundle 里没有 import.meta.resolve —— 这版 core 的自引用机制变了?')
  }

  // 统计
  let fileCount = 0
  let bytes = 0
  const walk = async (d) => {
    for (const entry of await readdir(d, { withFileTypes: true })) {
      const p = join(d, entry.name)
      if (entry.isDirectory()) await walk(p)
      else if (entry.isFile()) {
        fileCount++
        bytes += (await stat(p)).size
      }
    }
  }
  await walk(out)

  return { problems, fileCount, bytes }
}

// ─────────────────────────────────────────────────────────────────────────────
// 运行时自检:真把服务起起来,curl 一下
// ─────────────────────────────────────────────────────────────────────────────

async function waitForHealth(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  let lastErr = 'timeout'
  while (Date.now() < deadline) {
    try {
      const res = await httpGet(port, '/api/health', 3000)
      const text = await readAll(res)
      if (res.statusCode === 200) return { ok: true, body: text }
      lastErr = `HTTP ${res.statusCode}: ${text.slice(0, 200)}`
    } catch (e) {
      lastErr = e.message
    }
    await sleep(300)
  }
  return { ok: false, error: lastErr }
}

async function readFirstSseEvent(port, timeoutMs = 15000) {
  return new Promise((resolve) => {
    let buf = ''
    let settled = false
    let timer = null
    const finish = (r) => {
      if (settled) return
      settled = true
      if (timer) clearTimeout(timer)
      req.destroy()
      resolve(r)
    }
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path: '/api/event',
        method: 'GET',
        headers: { accept: 'text/event-stream' },
        // agent:false = 不走 keep-alive 连接池。全局 fetch 的 undici 连接池
        // 会把 socket 留着不让进程退出 —— 脚本跑完了却挂着,看着像卡死。
        agent: false,
      },
      (res) => {
        if (res.statusCode !== 200) {
          finish({ ok: false, error: `HTTP ${res.statusCode}` })
          return
        }
        res.setEncoding('utf-8')
        res.on('data', (chunk) => {
          buf += chunk
          const m = buf.match(/^event:\s*(\S+)/m)
          if (m) finish({ ok: true, event: m[1], raw: buf.slice(0, 200) })
        })
        res.on('end', () => finish({ ok: false, error: `SSE 流提前结束,没读到 event 头: ${buf.slice(0, 200)}` }))
      },
    )
    timer = setTimeout(() => finish({ ok: false, error: 'SSE 读取超时' }), timeoutMs)
    req.on('error', (e) => finish({ ok: false, error: e.message }))
    req.end()
  })
}

/** 一次性 HTTP GET(不走连接池)。 */
function httpGet(port, path, timeoutMs) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port, path, method: 'GET', agent: false },
      (res) => resolve(res),
    )
    req.setTimeout(timeoutMs, () => req.destroy(new Error('请求超时')))
    req.on('error', reject)
    req.end()
  })
}

function readAll(res) {
  return new Promise((resolve, reject) => {
    let buf = ''
    res.setEncoding('utf-8')
    res.on('data', (c) => (buf += c))
    res.on('end', () => resolve(buf))
    res.on('error', reject)
  })
}


const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function verifyRuntime(out) {
  const work = await mkdtemp(join(tmpdir(), 'engine-verify-'))
  const home = join(work, 'home')
  const ws = join(work, 'ws')
  const dataDir = join(home, '.zai')
  const port = 9400 + Math.floor(Math.random() * 400)
  for (const d of [home, ws, dataDir]) await mkdir(d, { recursive: true })

  const child = spawn(process.execPath, [join(out, 'server.mjs')], {
    env: {
      ...process.env,
      HOME: home,
      WS: ws,
      ZAI_DATA_DIR: dataDir,
      PORT: String(port),
      TMPDIR: join(work, 'tmp'),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let stderr = ''
  let stdout = ''
  child.stdout.on('data', (d) => (stdout += d.toString()))
  child.stderr.on('data', (d) => (stderr += d.toString()))
  const exited = new Promise((r) => child.on('exit', (code) => r(code)))

  try {
    const health = await waitForHealth(port)
    if (!health.ok) {
      fail(
        `引擎包起来了但 /api/health 不通: ${health.error}\n` +
          `--- stdout ---\n${stdout.slice(-2000)}\n--- stderr ---\n${stderr.slice(-4000)}`,
      )
    }
    let parsed = null
    try {
      parsed = JSON.parse(health.body)
    } catch {
      /* 解析不了不算致命,下面只要求 ok 字段 */
    }
    if (!parsed || parsed.ok !== true) {
      fail(`/api/health 返回的不是 {ok:true}: ${health.body.slice(0, 300)}`)
    }
    info(`  ✓ /api/health → ${health.body.slice(0, 120)}`)

    const sse = await readFirstSseEvent(port)
    if (!sse.ok) {
      fail(
        `/api/event 没吐 SSE 事件: ${sse.error}\n` +
          `--- stdout ---\n${stdout.slice(-2000)}\n--- stderr ---\n${stderr.slice(-4000)}`,
      )
    }
    if (sse.event !== 'server.connected') {
      fail(`/api/event 第一个事件不是 server.connected,而是 ${sse.event}(${sse.raw})`)
    }
    info(`  ✓ /api/event → event: ${sse.event}`)
    return true
  } finally {
    child.kill('SIGTERM')
    await Promise.race([exited, sleep(3000)])
    if (!child.killed) child.kill('SIGKILL')
    await rm(work, { recursive: true, force: true })
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 主流程
// ─────────────────────────────────────────────────────────────────────────────

/** 入口模板。写死成文件而不是运行时拼字符串,是为了让这一坨能被 code review 看见。 */
const SERVER_ENTRY = `// 引擎入口(由 tools/build-engine-pkg.mjs 生成,别手改)。
//
// 起 zai 的 HTTP 服务,只监听 127.0.0.1 —— App 主进程用 OkHttp 连过来,
// 复用 lan-agent 现有的 AgentApi / SSE 客户端,一行都不用改。
import http from 'node:http'
import { mkdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { createApp } from './zai-server.mjs'

function envPath(name) {
  const v = process.env[name]
  if (!v || !v.trim()) {
    console.error('[engine] 缺少环境变量 ' + name)
    process.exit(2)
  }
  return resolve(v.trim())
}

// HOME 必须显式指向 app 私有目录:Node 的 os.homedir() 读的是 $HOME,
// Android 上没设会退化成 "/",~/.zai 就写到根目录去了(非 root 直接 EACCES)。
const HOME = envPath('HOME')
const WS = envPath('WS')
const ZAI_DATA_DIR = process.env.ZAI_DATA_DIR && process.env.ZAI_DATA_DIR.trim()
  ? resolve(process.env.ZAI_DATA_DIR.trim())
  : join(HOME, '.zai')
const PORT = Number(process.env.PORT || 9420)
const HOST = '127.0.0.1'

// TMPDIR 同理:Android 上 os.tmpdir() 默认 /tmp,不可写。
const TMPDIR = process.env.TMPDIR && process.env.TMPDIR.trim()
  ? resolve(process.env.TMPDIR.trim())
  : join(HOME, 'tmp')

for (const d of [HOME, WS, ZAI_DATA_DIR, TMPDIR]) mkdirSync(d, { recursive: true })

// 统一回写,core 内部读的就是这几个
process.env.HOME = HOME
process.env.ZAI_DATA_DIR = ZAI_DATA_DIR
process.env.TMPDIR = TMPDIR

// **必须 chdir 到 WS** —— Android app 进程的 cwd 是 "/",而 core 里有 98 处
// process.cwd()(BashTool 的工作目录、transcript 的落盘路径
// .zai/projects/<sanitized-cwd>/ 都算)。不 chdir 的话,agent 的所有文件操作
// 都会对着根目录跑 —— 非 root 直接 EACCES,而且报错很难看出是 cwd 的问题。
//
// createApp({cwd: WS}) 只覆盖了「显式传 cwd」的那部分路由;剩下那些走
// process.cwd() 的依然会拿到 "/"。所以在这儿改进程 cwd 才是彻底的。
try {
  process.chdir(WS)
} catch (e) {
  console.error('[engine] chdir 到 ' + WS + ' 失败,cwd 仍是 ' + process.cwd())
  console.error(e)
}

const app = await createApp({ token: 'local', cwd: WS, cwdName: 'ws', host: HOST, sdk: true })
const server = http.createServer(app)
await new Promise((r) => server.listen(PORT, HOST, r))
console.log('[engine] LISTENING ' + HOST + ':' + PORT + ' ws=' + WS + ' home=' + HOME + ' cwd=' + process.cwd())
`

async function main() {
  const opt = parseArgs(process.argv.slice(2))
  const web = opt.webRoot

  info(`引擎包构建`)
  info(`  源仓库: ${web}`)
  info(`  输出:   ${opt.out}`)

  const zaiEntry = mustExist(join(web, 'packages/zai/dist/server/index.js'), 'zai 的 server 产物')
  const coreBundle = mustExist(
    join(web, 'packages/zn-agent-core/dist/opencc-core.mjs'),
    'agent core 的 bundle 产物',
  )
  const zodSrc = mustExist(join(web, 'packages/zai/node_modules/zod'), 'zod(pnpm 软链)')
  const fflateSrc = mustExist(join(web, 'packages/zn-agent-core/node_modules/fflate'), 'fflate(pnpm 软链)')
  const zaiPkg = JSON.parse(
    await readFile(mustExist(join(web, 'packages/zai/package.json'), 'zai 的 package.json'), 'utf-8'),
  )

  // zai 的 server bundle —— **直接消费 opencc-web 的产物**,不再自己调 esbuild。
  //
  // Why:「单文件怎么打」只有一份实现(banner / external 列表 / esbuild 版本
  // 全在那边)。两处各打一次迟早漂:external 少一个 → core 被内联进去 →
  // 自引用 resolve 不到 → agent 工具全废而服务照样起(最难查的静默失败)。
  //
  // 桌面端(bundle/zai.mjs)跟引擎包用的是**同一份 server bundle**。
  const serverBundle = mustExist(
    join(web, 'packages/zai/dist/bundle/zai-server.mjs'),
    'zai 的 server bundle\n  先跑: cd opencc-web && pnpm --filter @zn-ai/zai run build:bundle',
  )

  // dist 过期是最常见的「构建出来行为跟源码对不上」来源 —— 直接喊出来。
  const zaiSrc = join(web, 'packages/zai/src/server/index.ts')
  if (existsSync(zaiSrc) && statSync(zaiEntry).mtimeMs < statSync(zaiSrc).mtimeMs) {
    console.warn(
      `⚠️ zai 的 dist 比 src 旧 —— 先跑 \`pnpm --filter @zn-ai/zai build\` 再回来构建引擎包`,
    )
  }
  // bundle 比它打包的入口还旧 → 引擎包会带着上一版的代码。
  if (statSync(serverBundle).mtimeMs < statSync(zaiEntry).mtimeMs) {
    fail(
      'zai 的 server bundle 比 dist/server/index.js 还旧 —— 引擎包会带着上一版代码\n' +
        '  先跑: cd opencc-web && pnpm --filter @zn-ai/zai run build:bundle',
    )
  }

  // 1) 清空输出目录 —— 幂等的前提:每次都是全新的一份,不会残留上次的垃圾文件。
  await rm(opt.out, { recursive: true, force: true })
  await mkdir(opt.out, { recursive: true })

  // 2) 打 zai server bundle
  info(`  [1/4] 拷 zai server bundle(opencc-web 的产物) …`)
  await copyFile(serverBundle, join(opt.out, 'zai-server.mjs'))
  info(`        zai-server.mjs ${(await stat(join(opt.out, 'zai-server.mjs'))).size} 字节`)

  // 3) 外置依赖:core / zod / fflate
  info(`  [2/4] 拷外置依赖(core / zod / fflate) …`)
  const coreDir = join(opt.out, 'node_modules/@zn-ai/zn-agent-core')
  await mkdir(coreDir, { recursive: true })
  await cp(coreBundle, join(coreDir, 'opencc-core.mjs'))
  await writeFile(
    join(coreDir, 'package.json'),
    JSON.stringify(
      {
        name: '@zn-ai/zn-agent-core',
        version: '0.13.7',
        type: 'module',
        main: 'opencc-core.mjs',
        exports: { '.': './opencc-core.mjs' },
      },
      null,
      2,
    ) + '\n',
  )

  const zodDest = join(opt.out, 'node_modules/zod')
  await copyTree(zodSrc, zodDest)
  const pruned = await pruneZodTypes(zodDest)
  info(`        zod 解引用拷贝完成,砍掉 ${pruned} 个类型声明/TS 源文件`)

  await copyTree(fflateSrc, join(opt.out, 'node_modules/fflate'))

  // 3b) sharp 替身
  //
  // 手机上没有 sharp 的原生库(它的预编译包只覆盖 darwin / linux / win /
  // freebsd,没有 android-arm64)→ 引擎里 `import('sharp')` 直接失败,
  // core 的图片处理(压缩 / 缩放)全废,大图 Read 会报错「Install `sharp`」。
  //
  // 因为 sharp 在 esbuild 里是 external(运行时才从 node_modules 解析),
  // 这里放一个自己的实现就行 —— core 和 opencc-web 一行都不用改。
  //
  // 替身把活转给主进程的 Android BitmapFactory(能力桥),手机上就有原生
  // 图像栈可用,还省掉 wasm 版 libvips 的 9MB 下载体积。
  const sharpShimSrc = mustExist(
    resolve(REPO_ROOT, 'tools/engine-shim/sharp'),
    'sharp 替身源码',
  )
  await copyTree(sharpShimSrc, join(opt.out, 'node_modules/sharp'))
  info('        node_modules/sharp(手机端替身 → 能力桥)')

  // 4) 入口
  info(`  [3/4] 写入口 server.mjs …`)
  await writeFile(join(opt.out, 'server.mjs'), SERVER_ENTRY)

  // 4a) 引擎包自己的 package.json
  //
  // zai 的代码现在**不再靠 import.meta.url 往上数层级**找包根,而是向上找
  // `name === '@zn-ai/zai'` 的 package.json(见 zai 的
  // src/shared/packagePaths.ts)。引擎包是一个独立目录,里面本来没有
  // package.json → 解析不到包根 → /api/health 只能回 version '0.0.0'。
  //
  // 在引擎目录里放一份同名 package.json 就同时解决两件事:
  //   1. /api/health 返回真实版本号(之前永远是 '0.0.0');
  //   2. 引擎目录被认成包根,webDistDir 之类的派生路径有确定的落点。
  await writeFile(
    join(opt.out, 'package.json'),
    JSON.stringify(
      {
        name: '@zn-ai/zai',
        version: zaiPkg.version,
        private: true,
        description: 'zai 本机引擎包(手机端 / 自包含运行)',
      },
      null,
      2,
    ) + '\n',
  )
  info(`        package.json (name @zn-ai/zai, version ${zaiPkg.version})`)

  // 4b) AT_SECURE 修复的 preload
  //
  // App 进程是 zygote fork 出来的,带 AT_SECURE=1 → Node 的 SafeGetenv()
  // 拒绝一切 env 查找(process.env 的初始快照还在,但 os.tmpdir() 这类实时
  // 查表全废,全部退回不存在的 /tmp)。这个 preload 在 zai 加载之前把
  // TMPDIR / NODE_COMPILE_CACHE / NODE_EXTRA_CA_CERTS 恢复回来,顺带按
  // DSH_ANDROID_TZ 修正时区。
  // 没有它:引擎能起、SSE 能通,但凡是碰临时目录的功能静默失败 —— 最难查那类。
  const preloadSrc = resolve(REPO_ROOT, 'tools/engine-android-env.cjs')
  await copyFile(preloadSrc, join(opt.out, 'android-env.cjs'))
  info(`        android-env.cjs (AT_SECURE env 修复)`)

  // 4c) 手机能力桥的 mod —— **不在引擎包里**
  //
  // core 的 mods 系统从磁盘目录加载 mod(App 侧用 OPENCC_MODS_DIR 指过来)。
  // 这个 mod 把「截屏 / 通知 / 剪贴板 / 启动 App / 系统设置」注册成引擎
  // 的工具,实现是调主进程上的能力服务(127.0.0.1:9421)。
  //
  // ⚠️ **0.29.6 起这个 mod 跟着 APK 走,不再打进引擎包** ——
  // 加一个手机能力 = 改这个 mod;mod 在引擎包里的话,每次加能力都要
  // 重打引擎包 → 换指纹 → **用户重下 34MB**。一个循环转不起来。
  //
  // 现在它在 `app/src/main/assets/local-engine/mods/phone-bridge/`,
  // 起引擎时由 EngineModsInstaller 拷进 <引擎目录>/mods/。
  //
  // 不拷的后果:OPENCC_MODS_DIR 指向空目录,core 那句「没找到 mod」是
  // 日志里一行,模型那边表现为「工具列表里没有手机能力」—— 没有报错,
  // 只是功能不在,很难联想到是构建时少拷了一个目录。
  info(`        mods/ 不打进引擎包(随 APK 走,见 EngineModsInstaller)`)

  // 5) 指纹:App 侧拿它当解压目录名,内容变了就重新解压
  const sha = await treeFingerprint(opt.out)
  await writeFile(join(opt.out, 'engine.sha256'), sha + '\n')
  info(`  [4/4] 指纹 ${sha.slice(0, 16)}`)

  // 6) 自检
  const { problems, fileCount, bytes } = await verifyLayout(opt.out)
  if (problems.length > 0) {
    fail('产物结构自检没过:\n  - ' + problems.join('\n  - '))
  }
  info(`  ✓ 结构自检通过(${fileCount} 个文件 / ${(bytes / 1024 / 1024).toFixed(1)} MB)`)

  if (opt.verify) {
    info(`  起服务验一遍 …`)
    await verifyRuntime(opt.out)
    info(`  ✓ 运行时自检通过`)
  }

  info(`\n完成: ${opt.out}`)
  await flushAndExit(0)
}

/** 整棵树的指纹(文件路径 + 内容),用来判断 App 侧要不要重新解压。 */
async function treeFingerprint(dir) {
  const hash = createHash('sha256')
  const walk = async (d, prefix) => {
    const entries = (await readdir(d, { withFileTypes: true })).sort((a, b) => (a.name < b.name ? -1 : 1))
    for (const e of entries) {
      if (e.name === 'engine.sha256') continue
      const p = join(d, e.name)
      const rel = prefix ? `${prefix}/${e.name}` : e.name
      if (e.isDirectory()) await walk(p, rel)
      else if (e.isFile()) {
        hash.update(rel)
        hash.update('\0')
        hash.update(await readFile(p))
        hash.update('\0')
      }
    }
  }
  await walk(dir, '')
  return hash.digest('hex')
}

main().catch((e) => {
  writeSync(2, `\n✗ 引擎包构建失败: ${e?.stack || e}\n\n`)
  process.exit(1)
})
