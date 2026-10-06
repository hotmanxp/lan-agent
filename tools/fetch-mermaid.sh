#!/usr/bin/env bash
# 安装 assets 里的 mermaid 运行时(官方 UMD 全量 bundle)。
#
# ## 为什么需要这个脚本
#
# lan-agent 的 mermaid.min.js 必须和 opencc-web 的 `packages/zai` 用**同一个版本**。
# 服务端 prompt(`zn-agent-core/src/opencc-src/constants/prompts.ts`)里那份「哪些图型
# 能渲染」的清单是照着某个 mermaid 版本的 detector 注册表写的 —— 两端版本一错开,
# 模型就会开始画 app 端认不出的图型,而渲染失败的表现是**静默回退成代码块**
# (见 AGENTS.md §26),用户和排查的人都不会想到是版本问题。
#
# 换 mermaid 版本的正确顺序:先升 opencc-web 并重启实例(吃到新 prompt),再跑本脚本
# 升 app 端,最后两边一起验。
#
# ## 取件顺序
#
# 先从 opencc-web 的 pnpm store 里找(那是 web 真正在用的那份,「同版本」由此保证),
# 找不到再从 npm registry 下。registry 在这台机器上未必通,别把它当唯一路径。
#
# 用法:
#   tools/fetch-mermaid.sh                # 装 opencc-web 当前在用的版本
#   tools/fetch-mermaid.sh 12.1.0         # 装指定版本(本地 store 没有就下 registry)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/mermaid/mermaid.min.js"
WEB_ROOT="${OPENCC_WEB_ROOT:-/Users/ethan/code/opencc-web}"
WEB_PKG="$WEB_ROOT/packages/zai/package.json"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

resolve_from_web() {
  [ -f "$WEB_PKG" ] || return 1
  local ver
  ver="$(node -e "process.stdout.write(require('$WEB_PKG').dependencies.mermaid.replace(/^[\^~]/, ''))")" || return 1
  local src
  src="$(ls -d "$WEB_ROOT"/node_modules/.pnpm/mermaid@"$ver"/node_modules/mermaid/dist/mermaid.min.js 2>/dev/null | head -1)" || return 1
  [ -s "$src" ] || return 1
  echo "$ver" > "$TMP/version"
  cp "$src" "$TMP/mermaid.min.js"
}

resolve_from_npm() {
  local ver="$1"
  [ -n "$ver" ] || return 1
  echo "下载 mermaid@$ver" >&2
  curl -fsSL "https://registry.npmjs.org/mermaid/-/mermaid-${ver}.tgz" -o "$TMP/mermaid.tgz" || return 1
  tar -xzf "$TMP/mermaid.tgz" -C "$TMP" package/dist/mermaid.min.js || return 1
  [ -s "$TMP/package/dist/mermaid.min.js" ] || return 1
  cp "$TMP/package/dist/mermaid.min.js" "$TMP/mermaid.min.js"
  echo "$ver" > "$TMP/version"
}

if [ $# -ge 1 ]; then
  resolve_from_npm "$1" || { echo "装不了 $1:本地 store 没有,registry 也不通" >&2; exit 1; }
else
  resolve_from_web || { echo "opencc-web 的 pnpm store 里没找到 mermaid,跑 pnpm install 之后再来" >&2; exit 1; }
fi

VER="$(cat "$TMP/version")"

# 装之前先验两条硬条件,不然装完了要到真机上才发现 bundle 不对:
#   1. 非空
#   2. 末尾把 API 挂到 globalThis —— renderer.html 里的 <script src> 靠的就是这个
tail -c 200 "$TMP/mermaid.min.js" | grep -q 'globalThis' || {
  echo "bundle 末尾没有 globalThis 导出,这版 mermaid 的打包形态变了,renderer.html 要跟着改" >&2
  exit 1
}

mkdir -p "$(dirname "$DEST")"
cp "$TMP/mermaid.min.js" "$DEST"

echo "已安装 mermaid@$VER → $DEST"
echo "  字节数: $(wc -c < "$DEST" | tr -d ' ')"
echo "  sha256: $(shasum -a 256 "$DEST" | awk '{print $1}')"
