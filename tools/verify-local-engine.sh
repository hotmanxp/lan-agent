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
# 0.29.0 起原生库**不在 APK 里** —— 用户在设置页点「下载引擎」时拉下来,
# 解到 app 私有目录 files/native/。所以这里查的是那个目录,不是 nativeLibraryDir。
NATIVE_DIR="$APP_DIR/files/native"
if sh_ok "run-as $PKG sh -c \"test -d '$NATIVE_DIR'\""; then
  ok "原生库目录 = $NATIVE_DIR"
  for so in libnode.so libdshengine.so libdshnode.so libc++_shared.so; do
    if sh_ok "run-as $PKG sh -c \"test -f '$NATIVE_DIR/$so'\""; then
      ok "$so 在"
    else
      bad "$so 缺 —— 在 App 设置页点「下载引擎」"
    fi
  done
  # librg.so 不再要求下载 —— 0.29.3 起它随 APK 走(nativeLibraryDir),
  # 见下面第 3 步。下载目录里那份还在的话只是多一个回落。
  # node --version:libdshnode.so 是 node 启动器(要 LD_LIBRARY_PATH 才找得到 libnode.so)
  VER="$(sh_ "run-as $PKG sh -c \"cd '$NATIVE_DIR' && LD_LIBRARY_PATH='$NATIVE_DIR' ./libdshnode.so --version\"")"
  if printf '%s' "$VER" | grep -qE '^v[0-9]+\.'; then
    ok "node 跑起来了:$VER"
  else
    bad "node 起不来:$(printf '%s' "$VER" | head -1)"
  fi
else
  bad "没有 files/native 目录 —— 引擎还没下载(设置页点「下载引擎」)"
fi

step "2. 引擎包(下载后解到 files/engine)"
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
  bad "引擎没解开 —— 在 App 设置页点「下载引擎」"
fi

step "3. bash / rg 软链"
BIN_DIR="$APP_DIR/files/bin"
if sh_ok "run-as $PKG sh -c \"test -d '$BIN_DIR'\""; then
  ok "bin 目录 = $BIN_DIR"
  for link in bash rg node; do
    TARGET="$(sh_ "run-as $PKG readlink '$BIN_DIR/$link'")"
    if [ -n "$TARGET" ]; then
      ok "$link → $TARGET"
    else
      bad "$link 软链没建 —— BashTool / Grep 工具会废"
    fi
  done

  # ⚠️ **软链存在 ≠ 能执行** —— 真机上踩过的坑。
  #
  # 放在 app 私有目录(files/native/librg.so,app_data_file 标签)里的 .so,
  # 部分 Android 版本(Android 15/16)的 SELinux 策略禁止 untrusted_app 执行
  # 自己数据目录里的文件:`deny untrusted_app app_data_file:file execute`。
  # 症状是引擎起来、BashTool 照常(它 exec 的是 /system/bin/sh),
  # 但 Grep / Glob 全废,报 `spawn rg EACCES`。
  #
  # 解法:librg.so 进 APK,`useLegacyPackaging=true` 让它解到 nativeLibraryDir
  # (apk_data_file 标签,平台自己也执行那里的 .so)。
  # 所以这里**必须真的 exec 一次**,不能只看软链在不在。
  RG_VER="$(sh_ "run-as $PKG sh -c \"'$BIN_DIR/rg' --version\"")"
  if printf '%s' "$RG_VER" | grep -qE '^ripgrep '; then
    ok "rg 能执行:$(printf '%s' "$RG_VER" | head -1)"
  else
    bad "rg 跑不起来(SELinux 拒绝执行?):$(printf '%s' "$RG_VER" | head -1)"
  fi

  # node(给 agent 在 Bash 里跑 JS 用)。启动器在 APK 的 nativeLibraryDir,
  # libnode.so 在下载目录 —— 靠 LD_LIBRARY_PATH 串起来,跟引擎进程的 env 一致。
  NODE_VER="$(sh_ "run-as $PKG sh -c \"LD_LIBRARY_PATH='$NATIVE_DIR' '$BIN_DIR/node' --version\"")"
  if printf '%s' "$NODE_VER" | grep -qE '^v[0-9]+\.'; then
    ok "node 能执行:$NODE_VER"
  else
    bad "node 跑不起来(LD_LIBRARY_PATH 没生效?):$(printf '%s' "$NODE_VER" | head -1)"
  fi
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
if sh_ok "run-as $PKG sh -c \"test -d '$NATIVE_DIR'\""; then
  OUT="$(sh_ "run-as $PKG sh -c \"cd '$NATIVE_DIR' && LD_LIBRARY_PATH='$NATIVE_DIR' HOME=$APP_DIR/files/home ./libdshnode.so '$PROBE'\"")"
  printf '%s\n' "$OUT" | grep -E '^HEALTH ' | grep -q '"ok":true' && ok "/api/health → ok" || bad "/api/health → $(printf '%s' "$OUT" | grep '^HEALTH ' | head -1)"
  printf '%s\n' "$OUT" | grep -q '^SESSIONS ' && ok "/api/agent/sessions 有响应" || bad "/api/agent/sessions 没响应"
  printf '%s\n' "$OUT" | grep -q 'SSE_FRAME server.connected' && ok "SSE 吐出 server.connected" || bad "SSE 没吐 server.connected"
  sh_ "run-as $PKG rm -f '$PROBE'" >/dev/null 2>&1
fi

# 6. 下载源可达性(可选):传 MANIFEST_URL 就查一下清单拉不拉得到。
#
# 为什么单独拎出来:引擎是「按需下载」的,下载源不通的表现是设置页里一句
# 「下载引擎失败:xxx」—— 排查时第一件事就是确认那个地址活着。
if [ -n "${MANIFEST_URL:-}" ]; then
  step "6. 下载源"
  BODY="$(curl -fsS --max-time 10 "$MANIFEST_URL" 2>/dev/null)"
  if printf '%s' "$BODY" | grep -q '"sha256"'; then
    ok "清单可达:$MANIFEST_URL"
    printf '%s' "$BODY" | grep -oE '"(file|size)": *"?[^,"}]+' | sed 's/^/  /'
  else
    bad "清单拉不到或格式不对:$MANIFEST_URL"
  fi
fi

printf '\n\033[1m结果:%d 通过 / %d 失败\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
