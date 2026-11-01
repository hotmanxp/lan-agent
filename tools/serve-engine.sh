#!/usr/bin/env bash
# 起一个静态服务,把「引擎下载包」发出去 —— App 设置页的「引擎下载源」填它打印的地址。
#
# ## 它解决什么
#
# 0.29.0 起引擎包与原生库不再打进 APK,改成按需下载。当前下载源是**局域网上的
# 这台机器**(将来换 GitHub release 只改 manifest 里的 URL,App 侧不用动)。
#
# ## 用法
#
#   tools/serve-engine.sh              # 默认 8766
#   PORT=9000 tools/serve-engine.sh
#
# 跑之前先打包:
#   tools/fetch-native.sh && tools/build-engine-pkg.mjs && tools/pack-engine-bundle.mjs
#
# 打印出来的 manifest 地址填进 App:设置 → 本机引擎 → 引擎下载源。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUNDLE_DIR="$REPO_ROOT/dist/engine-bundle"
PORT="${PORT:-8766}"

if [ ! -f "$BUNDLE_DIR/engine-manifest.json" ]; then
  echo "✗ 没有打包好的引擎包:$BUNDLE_DIR" >&2
  echo "  先跑: tools/fetch-native.sh && tools/build-engine-pkg.mjs && tools/pack-engine-bundle.mjs" >&2
  exit 1
fi

# 端口占用就直接说 —— 端口被别的项目占了(这台机器上 8765 常年被占),
# 用户填错地址的表现是「下载一直失败」,很难一眼看出是端口问题。
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "✗ 端口 $PORT 已被占用。换一个:PORT=9000 tools/serve-engine.sh" >&2
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | tail -n +2 >&2
  exit 1
fi

# 局域网 IP:ipconfig getifaddr en0 在这台机器上返回空串,必须用 ifconfig 兜底。
IP=$(ifconfig | awk '/inet /{print $2}' | grep -v '^127' | grep '^192\.168' | head -1)
[ -z "$IP" ] && IP=$(ifconfig | awk '/inet /{print $2}' | grep -v '^127' | head -1)
if [ -z "$IP" ]; then
  echo "✗ 找不到本机局域网 IP" >&2
  exit 1
fi

echo "引擎下载包静态服务"
echo "  目录      $BUNDLE_DIR"
echo "  端口      $PORT"
echo
echo "App 设置页的「引擎下载源」填这一行(带时间戳免得 App 拿到缓存的 manifest):"
echo
echo "  http://$IP:$PORT/engine-manifest.json"
echo
echo "按 Ctrl-C 停止。"

cd "$BUNDLE_DIR"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
