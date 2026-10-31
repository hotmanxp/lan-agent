#!/usr/bin/env bash
# verify-engine-on-device.sh — 在真机/模拟器上验证「本地引擎」这条链路。
#
# ## 为什么要有这个脚本
#
# 这条链路的每一环都可能**静默失效**:引擎起不来、模型调不通、bash 找不到、
# rg 找不到、SSE 不吐帧 —— 而且症状都是「UI 上啥也没有」,不跑一遍根本不知道
# 卡在哪一环。与其在 App 里点半天,不如在设备上直接跑一遍协议级验证。
#
# 验证 5 件事:
#   1. libdshnode.so 能跑(Node 版本对不对)
#   2. 引擎起得来(/api/health 200)
#   3. SSE 立刻吐 server.connected
#   4. 真发一条消息,拿到 runtime.delta
#   5. BashTool 真能执行命令
#
# ## 用法
#
#   tools/verify-engine-on-device.sh                 # 用 adb 设备列表里第一台
#   tools/verify-engine-on-device.sh <serial>        # 指定设备
#   tools/verify-engine-on-device.sh --no-prompt     # 不发消息(跳过 4/5)
#
# 前提:设备已装本 App 的 debug APK(引擎包和 .so 都在 APK 里)。
#
# ## 环境变量
#
#   ENGINE_PORT   引擎端口(默认 9420)
#   API_KEY_ENV   用哪个 key 跑真实模型(默认 MINIMAX_API_KEY)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG="io.github.hotmanxp.lanagent"
PORT="${ENGINE_PORT:-9420}"
API_KEY_ENV="${API_KEY_ENV:-MINIMAX_API_KEY}"
DO_PROMPT=1
SERIAL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-prompt) DO_PROMPT=0; shift ;;
    -*) echo "未知参数: $1" >&2; exit 2 ;;
    *) SERIAL="$1"; shift ;;
  esac
done

if [[ -n "$SERIAL" ]]; then
  ADB="$ADB -s $SERIAL"
fi

WORK="/data/local/tmp/engine-verify"

fail() { echo "x $*" >&2; exit 1; }
ok()   { echo "ok $*"; }

# ── 1. Node 运行时 ────────────────────────────────────────────────────────
# libnode.so 在 App 的 nativeLibraryDir 里。那个目录在 /data/app/.../lib/arm64,
# 是 world-readable 的,adb shell 读得到。
APK_PATH="$($ADB shell "pm path $PKG" 2>/dev/null | sed 's/package://' | tr -d '\r' | head -1)"
[[ -n "$APK_PATH" ]] || fail "App 没装(pm path 为空)"
LIB_DIR="$APK_PATH/lib/arm64"

VERSION="$($ADB shell "LD_LIBRARY_PATH=$LIB_DIR $LIB_DIR/libdshnode.so --version" 2>/dev/null | tr -d '\r')"
[[ "$VERSION" == v24.* ]] || fail "Node 版本不对: $VERSION"
ok "Node 运行时 $VERSION"

# ── 准备运行目录 ──────────────────────────────────────────────────────────
$ADB shell "rm -rf $WORK && mkdir -p $WORK/home/ws $WORK/home/.zai $WORK/home/bin $WORK/home/tmp"
$ADB shell "ln -sf /system/bin/sh $WORK/home/bin/bash" || fail "建 bash 软链失败"

# 引擎包从 APK 里抽:App 的 assets 在包内,直接取比走 App 的解包路径更可控。
rm -rf /tmp/engine-verify.apk /tmp/engine-verify-x
$ADB pull "$APK_PATH" /tmp/engine-verify.apk >/dev/null 2>&1 || fail "拉 APK 失败"
mkdir -p /tmp/engine-verify-x
(cd /tmp/engine-verify-x && unzip -q -o /tmp/engine-verify.apk "assets/engine/*")
[[ -f /tmp/engine-verify-x/assets/engine/server.mjs ]] || fail "APK 里没有引擎包"
$ADB push /tmp/engine-verify-x/assets/engine "$WORK/" >/dev/null || fail "推引擎包失败"
ok "引擎包就位"

# provider 配置(密钥不落设备)
$ADB push "$HOME/.zai.json" "$WORK/home/.zai.json" >/dev/null 2>&1 || true
$ADB push "$HOME/.zai/settings.json" "$WORK/home/.zai/settings.json" >/dev/null 2>&1 || true

# probe 脚本单独一个文件 —— 嵌在 heredoc 里引号转义会变得没法读。
$ADB push "$REPO_ROOT/tools/engine-probe.mjs" "$WORK/probe.mjs" >/dev/null \
  || fail "推 probe 脚本失败"

# ── 起引擎 ────────────────────────────────────────────────────────────────
API_KEY="${!API_KEY_ENV:-}"
$ADB shell "cd $WORK && LD_LIBRARY_PATH=$LIB_DIR HOME=$WORK/home ZAI_DATA_DIR=$WORK/home/.zai \
  WS=$WORK/home/ws TMPDIR=$WORK/home/tmp PORT=$PORT \
  CLAUDE_CODE_SHELL=$WORK/home/bin/bash PATH=$WORK/home/bin:/system/bin:/system/xbin \
  DEEPSEEK_API_KEY=$DEEPSEEK_API_KEY $API_KEY_ENV=$API_KEY \
  $LIB_DIR/libdshnode.so engine/server.mjs > $WORK/srv.log 2>&1 &"

for _ in $(seq 1 40); do
  sleep 1
  $ADB shell "grep -q LISTENING $WORK/srv.log" 2>/dev/null && break
done
$ADB shell "grep -q LISTENING $WORK/srv.log" 2>/dev/null || {
  echo "--- 引擎日志 ---" >&2
  $ADB shell "cat $WORK/srv.log" >&2
  fail "引擎没起来"
}
ok "引擎起来了(LISTENING)"

# ── 2/3/4/5. 协议级验证(用设备上的 Node 发请求)──────────────────────────
PROMPT_FLAG=""
PROVIDER_ID=""
MODEL=""
if [[ $DO_PROMPT -eq 1 ]]; then
  # providerId / model 从本机 ~/.zai.json 拿第一个 anthropic profile
  PAIR="$(python3 - <<'PY'
import json, os
d = json.load(open(os.path.expanduser('~/.zai.json')))
for p in d.get('providerProfiles', []):
    if p.get('provider') == 'anthropic':
        print(p['id'], (p.get('model') or '').split(',')[0])
        break
PY
)"
  PROVIDER_ID="$(echo "$PAIR" | cut -d' ' -f1)"
  MODEL="$(echo "$PAIR" | cut -d' ' -f2)"
  [[ -n "$PROVIDER_ID" ]] || fail "~/.zai.json 里没有 anthropic profile"
  PROMPT_FLAG="PROMPT=1"
fi

OUT="$($ADB shell "cd $WORK && LD_LIBRARY_PATH=$LIB_DIR HOME=$WORK/home ZAI_DATA_DIR=$WORK/home/.zai \
  P=$PORT $PROMPT_FLAG MODEL=$MODEL PROVIDER_ID=$PROVIDER_ID \
  $LIB_DIR/libdshnode.so probe.mjs" 2>&1 | tr -d '\r')"
echo "$OUT"

grep -q 'HEALTH {"ok":true' <<<"$OUT" || fail "health 不对"
ok "HTTP 端点"
grep -q 'SSE server.connected OK' <<<"$OUT" || fail "SSE 没吐 server.connected"
ok "SSE 首帧"
if [[ $DO_PROMPT -eq 1 ]]; then
  grep -q 'HELLO_FROM_DEVICE' <<<"$OUT" || fail "模型/工具链路没跑通(没看到 HELLO_FROM_DEVICE)"
  ok "真实模型 + BashTool"
fi

$ADB shell "pkill -f libdshnode" 2>/dev/null || true
echo
echo "全部通过。"
