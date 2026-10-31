#!/usr/bin/env bash
# 在连着的安卓设备/模拟器上验证「本地引擎」这条链路。
#
# 干的事(从下往上,一层层确认):
#   1. libnode.so 在不在、能不能跑(node --version)
#   2. 引擎包解开了没有、入口在不在
#   3. bash / rg 软链建了没有、指向对不对
#   4. 引擎起没起(端口在不在)、/api/health 通不通
#   5. /api/event 的 SSE 吐不吐 server.connected
#
# 用法:
#   tools/verify-local-engine.sh              # 用 adb 默认设备
#   ADB_SERIAL=emulator-5554 tools/verify-local-engine.sh
#
# 退出码:0 = 全部通过;1 = 有环节没过(会指出卡在哪一层)。
#
# ⚠️ 写这个脚本时踩过的坑(改之前先看):
#   - `adb shell "run-as pkg ls -d files/engine/*/"` 里的通配符是**外层**
#     设备 shell 展开的(相对 /),不是 run-as 里展开的 → 永远匹配不到。
#     必须写绝对路径 /data/user/0/<pkg>/...
#   - **判成败要看退出码,不能看输出**。`test -f` / `grep -q` 成功时
#     一个字都不打印,外面再 `grep -q .` 就永远失败。
#   - `run-as` 后面跟的必须是**真实存在的可执行文件**:test 是 shell 内建,
#     得写成 `run-as pkg sh -c "test -f ..."`。
#   - /data/app 对 shell 用户不可读,拿 nativeLibraryDir 只能问 dumpsys;
#     解析时用 [^=]* 而不是 .* —— 路径里带 "=="(随机目录名),贪婪匹配
#     会把路径吃掉。
set -uo pipefail

ADB="${ADB:-adb}"
PKG="io.github.hotmanxp.lanagent"
APP_DIR="/data/user/0/$PKG"
ENGINE_PORT=9420

pass=0
fail=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; pass=$((pass+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; fail=$((fail+1)); }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# adb shell 里跑一段 sh,返回输出
sh_() { $ADB shell "$@" 2>&1 | tr -d '\r'; }

# 同一条命令,只看退出码(成功时无输出)
sh_ok() { $ADB shell "$@" >/dev/null 2>&1; }

step "1. 原生库(node 运行时)"
# libnode.so 由系统解到 nativeLibraryDir(legacy packaging,extractNativeLibs=true),
# 路径每次安装都可能变,得运行时问 dumpsys 拿 —— /data/app 对 shell 不可读。
NATIVE_DIR="$(sh_ "dumpsys package $PKG | grep legacyNativeLibraryDir" | sed -n 's/^[^=]*=//p' | head -1)"
NATIVE_DIR="${NATIVE_DIR%/}/arm64"
if [ -n "$NATIVE_DIR" ] && [ "$NATIVE_DIR" != "/arm64" ]; then
  ok "nativeLibraryDir = $NATIVE_DIR"
  for so in libnode.so libdshengine.so libdshnode.so libc++_shared.so librg.so; do
    if sh_ok "[ -f '$NATIVE_DIR/$so' ]"; then
      ok "$so 在"
    else
      bad "$so 缺 —— 跑 tools/fetch-native.sh 补齐"
    fi
  done
  # node --version:libdshnode.so 是 node 启动器
  VER="$(sh_ "cd '$NATIVE_DIR' && LD_LIBRARY_PATH='$NATIVE_DIR' ./libdshnode.so --version")"
  if printf '%s' "$VER" | grep -qE '^v[0-9]+\.'; then
    ok "node 跑起来了:$VER"
  else
    bad "node 起不来:$(printf '%s' "$VER" | head -1)"
  fi
else
  bad "拿不到 nativeLibraryDir —— APK 里没有原生库(tools/fetch-native.sh 没跑过?)"
fi

step "2. 引擎包(assets 解到 filesDir)"
ENGINE_DIR="$(sh_ "run-as $PKG sh -c 'ls -d $APP_DIR/files/engine/*/' | head -1" | sed 's|/$||')"
if [ -n "$ENGINE_DIR" ]; then
  ok "引擎目录 = $ENGINE_DIR"
  for f in server.mjs zai-server.mjs engine.sha256 android-env.cjs; do
    if sh_ok "run-as $PKG sh -c \"test -f '$ENGINE_DIR/$f'\""; then
      ok "$f 在"
    else
      bad "$f 缺"
    fi
  done
  if sh_ok "run-as $PKG sh -c \"test -f '$ENGINE_DIR/node_modules/@zn-ai/zn-agent-core/opencc-core.mjs'\""; then
    ok "core bundle 在(必须外置,不能打进单文件)"
  else
    bad "core bundle 不在 node_modules —— agent 内置工具会全废"
  fi
else
  bad "引擎没解开 —— App 里没跑过 EngineService"
fi

step "3. bash / rg 软链"
BIN_DIR="$APP_DIR/files/bin"
if sh_ok "run-as $PKG sh -c \"test -d '$BIN_DIR'\""; then
  ok "bin 目录 = $BIN_DIR"
  for link in bash rg; do
    TARGET="$(sh_ "run-as $PKG readlink '$BIN_DIR/$link'")"
    if [ -n "$TARGET" ]; then
      ok "$link → $TARGET"
    else
      bad "$link 软链没建 —— BashTool / Grep 工具会废"
    fi
  done
else
  bad "bin 目录不存在 —— 软链没建"
fi

step "4. 引擎进程"
if sh_ok "ps -A | grep -q 'lanagent'"; then
  ok "App 进程在"
else
  bad "App 没跑 —— 先打开 App"
fi
if sh_ok "ps -A | grep -q 'lanagent:engine'"; then
  ok ":engine 进程在"
else
  bad ":engine 进程不在 —— 引擎没起来"
fi
# 引擎起来的话 127.0.0.1:9420 在监听
if sh_ "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null" | grep -qi ":$(printf '%04X' $ENGINE_PORT)"; then
  ok "端口 $ENGINE_PORT 在监听"
else
  bad "端口 $ENGINE_PORT 没监听 —— 引擎没起来"
fi

step "5. HTTP 接口"
# 用 app 私有目录里的 node 运行时起个脚本发请求(设备上不一定有 curl)
PROBE="$APP_DIR/files/verify-probe.mjs"
sh_ "run-as $PKG sh -c 'cat > $PROBE'" <<'EOF' >/dev/null 2>&1
const base = 'http://127.0.0.1:9420'
const health = await fetch(base + '/api/health').then(r => r.text()).catch(e => 'ERR ' + e.message)
console.log('HEALTH ' + health)
const sessions = await fetch(base + '/api/agent/sessions').then(r => r.text()).catch(e => 'ERR ' + e.message)
console.log('SESSIONS ' + sessions.slice(0, 120))
const ctrl = new AbortController()
try {
  const res = await fetch(base + '/api/event', { signal: ctrl.signal })
  console.log('SSE ' + res.status)
  const rd = res.body.getReader(); const dec = new TextDecoder()
  let buf = ''
  for (let i = 0; i < 5; i++) {
    const { value, done } = await rd.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    if (buf.includes('server.connected')) { console.log('SSE_FRAME server.connected'); break }
  }
  ctrl.abort()
} catch (e) { console.log('SSE_ERR ' + e.message) }
EOF
if [ -n "$NATIVE_DIR" ] && [ "$NATIVE_DIR" != "/arm64" ]; then
  OUT="$(sh_ "run-as $PKG sh -c \"cd '$NATIVE_DIR' && LD_LIBRARY_PATH='$NATIVE_DIR' HOME=$APP_DIR/files/home ./libdshnode.so '$PROBE'\"")"
  printf '%s\n' "$OUT" | grep -E '^HEALTH ' | grep -q '"ok":true' && ok "/api/health → ok" || bad "/api/health → $(printf '%s' "$OUT" | grep '^HEALTH ' | head -1)"
  printf '%s\n' "$OUT" | grep -q '^SESSIONS ' && ok "/api/agent/sessions 有响应" || bad "/api/agent/sessions 没响应"
  printf '%s\n' "$OUT" | grep -q 'SSE_FRAME server.connected' && ok "SSE 吐出 server.connected" || bad "SSE 没吐 server.connected"
  sh_ "run-as $PKG rm -f '$PROBE'" >/dev/null 2>&1
fi

printf '\n\033[1m结果:%d 通过 / %d 失败\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
