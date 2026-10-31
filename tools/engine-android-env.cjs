'use strict'
// Preloaded by the app (`node --require <engineDir>/android-env.cjs <engineDir>/server.mjs`).
//
// ## 为什么需要这个文件
//
// Android app processes are forked from zygote and run with AT_SECURE=1 in their auxv.
// Node then treats the process as privileged and its native SafeGetenv() refuses every
// environment lookup that goes through it — even though the variables ARE set
// (process.env still shows them, because Node's process.env is a snapshot of the
// initial environ, not a live lookup).
//
// On the phone that silently disables:
//   - os.tmpdir() (TMPDIR)  → '/tmp', which does not exist on Android. Anything that
//                              tries to make a temp file there fails with EACCES.
//   - NODE_COMPILE_CACHE    → no V8 code cache (every start re-parses everything)
//   - NODE_EXTRA_CA_CERTS   → the exported Android CA store (user-installed CAs) unused
//   - NODE_OPTIONS          → ignored by Node itself (it refuses it under AT_SECURE);
//                              the app works around this by also passing the same flags
//                              in argv.
//
// None of these produce an error message. They just make things quietly not work.
//
// This preload restores the first three from process.env before zai loads.
// It changes nothing when Node already honours the environment (host runs), unless
// DSH_ANDROID_ENV_FORCE=1 (used by the test).
//
// ## 时区
//
// Android has no TZ / /etc/localtime that Node's ICU reads, so the process would run in
// UTC — wrong dates in the prompt's time context, budget day rollover at UTC midnight.
// The app passes the device zone as TZ and DSH_ANDROID_TZ; if the zone ICU resolved
// differs (TZ unset or not honoured), assigning process.env.TZ makes Node reset ICU's
// default zone.
//
// 移植自 jackwener/DSH-Android 的 js/packages/bundle-android/android-env.cjs（Apache-2.0），
// 适配 zai 引擎（不需要 dsh 特有的那部分）。

const os = require('node:os')
const { isMainThread } = require('node:worker_threads')
const env = process.env

// Workers re-run this preload (they need the tmpdir fix too); only the main thread logs.
const note = (message) => {
  if (isMainThread) process.stderr.write(`android-env: ${message}\n`)
}

// ── 时区 ────────────────────────────────────────────────────────────────────
const wantZone = env.DSH_ANDROID_TZ || ''
if (wantZone !== '') {
  try {
    // 未知时区会抛,顺便当作校验
    new Intl.DateTimeFormat('en-US', { timeZone: wantZone })
    if (Intl.DateTimeFormat().resolvedOptions().timeZone !== wantZone) {
      env.TZ = wantZone
      note(`time zone ${Intl.DateTimeFormat().resolvedOptions().timeZone} (from DSH_ANDROID_TZ)`)
    }
  } catch {
    note(
      `DSH_ANDROID_TZ=${JSON.stringify(wantZone)} is not a known time zone; ` +
        `using ${Intl.DateTimeFormat().resolvedOptions().timeZone}`,
    )
  }
}

// ── env 修复 ────────────────────────────────────────────────────────────────
const tmp = env.TMPDIR
const gated =
  env.DSH_ANDROID_ENV_FORCE === '1' ||
  (tmp !== undefined && tmp !== '' && os.tmpdir() !== tmp.replace(/(.)\/$/, '$1'))

if (gated) {
  const restored = []

  if (tmp) {
    const dir = tmp.length > 1 && tmp.endsWith('/') ? tmp.slice(0, -1) : tmp
    os.tmpdir = function tmpdir() {
      return dir
    }
    // `import { tmpdir } from 'node:os'` 的绑定跟着被 patch 的 CommonJS export 走
    require('node:module').syncBuiltinESMExports()
    restored.push('TMPDIR')
  }

  if (env.NODE_COMPILE_CACHE) {
    try {
      const r = require('node:module').enableCompileCache(env.NODE_COMPILE_CACHE)
      if (r && r.status !== undefined) restored.push('NODE_COMPILE_CACHE')
    } catch (error) {
      note(`compile cache unavailable: ${error && error.message}`)
    }
  }

  if (env.NODE_EXTRA_CA_CERTS) {
    try {
      const tls = require('node:tls')
      const pem = require('node:fs').readFileSync(env.NODE_EXTRA_CA_CERTS, 'utf8')
      const extra = pem.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g) ?? []
      if (extra.length > 0) {
        tls.setDefaultCACertificates([
          ...new Set([...tls.getCACertificates('default'), ...extra]),
        ])
        restored.push(`NODE_EXTRA_CA_CERTS(${extra.length})`)
      }
    } catch (error) {
      note(`extra CA certificates not loaded: ${error && error.message}`)
    }
  }

  note(`Node ignores the environment (AT_SECURE); restored ${restored.join(', ') || 'nothing'}`)
}

// ── 工作目录 ────────────────────────────────────────────────────────────────
//
// Android 上 App 进程的 cwd 是 "/",Node 起来时直接继承它。opencc 的工作区
// 由 WS 环境变量决定,但 BashTool 起 shell 时用的还是 process.cwd(),
// 于是相对路径全落在根目录:读文件碰巧能读(根目录可读),写文件直接
// EACCES,报错信息里带着 "/foo.txt" 这种一眼假的路径,极难查。
//
// 为什么不在 Kotlin 侧切:android.system.Os 里没有 chdir —— Android 压根
// 不公开这个 API(只暴露了 chmod/chown/stat 那一堆)。Node 自己的
// process.chdir() 不受 AT_SECURE 影响,是唯一能切的地方。
const ws = env.WS || env.HOME
if (ws) {
  try {
    process.chdir(ws)
    note(`cwd = ${process.cwd()}`)
  } catch (error) {
    note(`chdir(${ws}) failed: ${error && error.message}`)
  }
}
