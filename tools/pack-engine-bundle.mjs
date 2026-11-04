#!/usr/bin/env node
/**
 * 把「引擎包」和「原生库」打成一个 zip,供 App 按需下载。
 *
 * ## 为什么要有这一步
 *
 * 0.29.0 起 APK 不再内嵌引擎 —— APK 只留纯客户端(~13MB),引擎包(13MB)和
 * 原生库(95MB)改成用户在设置页点「下载引擎」时拉。
 *
 * 下载的产物必须是一个**单文件** + 一个**校验和**:App 侧拿到 manifest 就知道
 * 「文件多大、期望的 sha256 是多少」,下完校验通过才解包 —— 不通过就删掉重下。
 *
 * ## 为什么是 zip 而不是 tar.gz
 *
 * App 侧解包用 `java.util.zip.ZipInputStream` —— **JDK 自带,不用引依赖**。
 * tar 在 Java 侧没有内置实现(要么引 commons-compress,要么手写 tar 解析器),
 * 而压缩率对这个场景无所谓(本来就是给手机下载的)。
 *
 * ## zip 里的布局
 *
 *   engine/     ← dist/engine/ 的全部内容(现在 app 侧解到 filesDir/engine/<指纹>/)
 *   native/     ← 7 个 .so(解到 filesDir/native/,JNI 用 System.load 绝对路径加载)
 *
 * ## 用法
 *
 *   node tools/pack-engine-bundle.mjs
 *   node tools/pack-engine-bundle.mjs --engine dist/engine --natives dist/natives/arm64-v8a
 *
 * 产物:
 *   dist/engine-bundle/engine-manifest.json
 *   dist/engine-bundle/engine-arm64-v8a-<sha12>.zip
 */
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  cpSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync,
} from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** 引擎包里必须有的文件(相对引擎包根)。少一个就是「装上了但引擎起不来」。 */
const REQUIRED_ENGINE_FILES = [
  'server.mjs',
  'zai-server.mjs',
  'engine.sha256',
  'package.json',
  'node_modules/@zn-ai/zn-agent-core/package.json',
  'node_modules/@zn-ai/zn-agent-core/opencc-core.mjs',
  'node_modules/zod/package.json',
  'node_modules/zod/index.js',
  'node_modules/fflate/package.json',
]

/** core 静态 import 了 108 次 zod/v4,少一个子路径就炸。 */
const ZOD_SUBPATHS = ['v3', 'v4', 'v4-mini']

/** 引擎起不来的最小集 + 工具链要用的两个。 */
const NATIVE_FILES = [
  'libnode.so',
  'libc++_shared.so',
  'libdshengine.so',
  'libdshnode.so',
  'librg.so',
  'libdsh_flock.so',
  'libdsh_pty.so',
]

function fail(msg) {
  process.stderr.write(`\n✗ 打包失败: ${msg}\n\n`)
  process.exit(1)
}

function parseArgs(argv) {
  const opt = {
    engine: resolve(REPO_ROOT, 'dist/engine'),
    natives: resolve(REPO_ROOT, 'dist/natives/arm64-v8a'),
    out: resolve(REPO_ROOT, 'dist/engine-bundle'),
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--engine') opt.engine = resolve(process.cwd(), argv[++i] ?? '')
    else if (a === '--natives') opt.natives = resolve(process.cwd(), argv[++i] ?? '')
    else if (a === '--out') opt.out = resolve(process.cwd(), argv[++i] ?? '')
    else if (a === '--help' || a === '-h') {
      console.log(
        '用法: node tools/pack-engine-bundle.mjs [--engine <dir>] [--natives <dir>] [--out <dir>]\n' +
          `默认引擎包: ${opt.engine}\n` +
          `默认原生库: ${opt.natives}\n` +
          `默认输出:   ${opt.out}`,
      )
      process.exit(0)
    } else fail(`不认识的参数: ${a}`)
  }
  return opt
}

/** 递归找符号链接 —— 打进 zip 的软链在手机上就是断链。 */
function findSymlinks(root, rel = '') {
  const found = []
  for (const name of readdirSync(join(root, rel))) {
    const child = rel ? join(rel, name) : name
    const st = lstatSync(join(root, child))
    if (st.isSymbolicLink()) found.push(child)
    else if (st.isDirectory()) found.push(...findSymlinks(root, child))
  }
  return found
}

function sha256Of(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

function verifyEngine(dir) {
  if (!existsSync(dir)) fail(`引擎包不存在: ${dir}\n  先跑 tools/build-engine-pkg.mjs`)
  for (const rel of REQUIRED_ENGINE_FILES) {
    const f = join(dir, rel)
    if (!existsSync(f) || statSync(f).size === 0) fail(`引擎包缺 ${rel}(或它是空文件)`)
  }
  for (const sp of ZOD_SUBPATHS) {
    const f = join(dir, 'node_modules/zod', sp, 'index.js')
    if (!existsSync(f)) fail(`引擎包缺 zod 的 ${sp} 子路径(core 静态 import 了 108 次 zod/v4)`)
  }
  const links = findSymlinks(dir)
  if (links.length) fail(`引擎包里有符号链接,打进 zip 就是断链: ${links.slice(0, 5).join(', ')}`)
}

function verifyNatives(dir) {
  if (!existsSync(dir)) fail(`原生库不存在: ${dir}\n  先跑 tools/fetch-native.sh`)
  for (const name of NATIVE_FILES) {
    const f = join(dir, name)
    if (!existsSync(f) || statSync(f).size === 0) fail(`原生库缺 ${name}\n  跑 tools/fetch-native.sh 补齐`)
  }
}

const opt = parseArgs(process.argv.slice(2))
verifyEngine(opt.engine)
verifyNatives(opt.natives)

if (!existsSync('/usr/bin/zip')) fail('系统没有 /usr/bin/zip(macOS 自带;换台机器打包的话要自己装)')

const staging = resolve(REPO_ROOT, 'dist/.bundle-staging')
rmSync(staging, { recursive: true, force: true })
mkdirSync(staging, { recursive: true })

// staging/engine 与 staging/native —— zip 里的顶层两个目录。
cpSync(opt.engine, join(staging, 'engine'), { recursive: true, dereference: true })
cpSync(opt.natives, join(staging, 'native'), { recursive: true, dereference: true })

mkdirSync(opt.out, { recursive: true })
// 旧产物清掉 —— manifest 里的文件名带指纹,不清会越攒越多。
for (const name of readdirSync(opt.out)) {
  if (name.endsWith('.zip')) rmSync(join(opt.out, name), { force: true })
}

const tmpZip = join(staging, 'bundle.zip')
execFileSync('/usr/bin/zip', ['-r', '-X', '-q', tmpZip, 'engine', 'native'], { cwd: staging })

const sha256 = sha256Of(tmpZip)
const size = statSync(tmpZip).size
const fileName = `engine-arm64-v8a-${sha256.slice(0, 12)}.zip`
const outZip = join(opt.out, fileName)
cpSync(tmpZip, outZip)
rmSync(staging, { recursive: true, force: true })

const engineFingerprint = readFileSync(join(opt.engine, 'engine.sha256'), 'utf8').trim()
const manifest = {
  version: '0.29.0',
  abi: 'arm64-v8a',
  fingerprint: sha256.slice(0, 16),
  engineFingerprint,
  file: fileName,
  sha256,
  size,
  createdAt: new Date().toISOString(),
}
writeFileSync(join(opt.out, 'engine-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`)

const mb = (n) => `${(n / 1024 / 1024).toFixed(1)}MB`
console.log('✓ 引擎包已打好')
console.log(`  zip      ${outZip}`)
console.log(`  大小     ${mb(size)}`)
console.log(`  sha256   ${sha256}`)
console.log(`  manifest ${join(opt.out, 'engine-manifest.json')}`)
console.log()
console.log('下一步: tools/serve-engine.sh 起静态服务,然后在 App 设置页把「引擎下载源」填成打印出来的地址')
