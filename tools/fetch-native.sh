#!/usr/bin/env bash
# 拉取本地引擎要的原生库(Node 运行时 + JNI)到 app/src/main/jniLibs/arm64-v8a/。
#
# ## 为什么单独一个脚本
#
# libnode.so 是 82MB。整个塞进 git 显然不行,但也不能塞进 Gradle 的构建流程里
# —— 每次 assembleDebug 都下 82MB 太慢,而且构建机未必有网。所以单独一个脚本:
# 拉一次落到 jniLibs/,之后 assembleDebug 直接用(AGP 默认就吃 src/main/jniLibs)。
#
# ## 二进制从哪来
#
# 上游 jackwener/DSH-Android 用 GitHub Release 发布预编译的 arm64 原生库
# (native/prebuilt.sh)。release tag 由「所有原生输入」的哈希推导:
#
#   tag = native-node<NODE_VERSION>-<sha256(build.sh + patches + engine-jni + dshnode)[:10]>
#
# 上游改了任何一份原生输入,tag 就变 —— 所以 tag 钉死在这儿,升级时重算。
#
# ## 用法
#
#   tools/fetch-native.sh                          # 从 GitHub release 下载
#   tools/fetch-native.sh --from /path/to/arm64-v8a # 从本地目录拷(离线 / 网络不通时)
#   tools/fetch-native.sh --download --from <dir>   # 先试下载,失败退本地
#
# 环境变量:
#   DSH_NATIVE_TAG   覆盖 release tag
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"

# 上游 DSH-Android 的预编译 release(Node 24.22.1 + 那一批原生输入的哈希)
TAG="${DSH_NATIVE_TAG:-native-node24.22.1-c5b68634a6}"
RELEASE_URL="https://github.com/jackwener/DSH-Android/releases/download/${TAG}/${TAG}.tar.gz"

# 钉死的校验和 —— 下载来的和本地拷来的都要过这一关。
# 换版本时先更新这里,否则脚本会直接拒绝(宁可装不上,也不能装个错的 .so 进 APK)。
CHECKSUMS='0c52cfab2df0d957d8b346a2bdc5ae8d71feca2591924d77e1cd724d5bf74352  libc++_shared.so
08a75e4f6f159b38af94e9b0982991a2fb128ee6298361ebb1dccf4eb4392d5f  libdsh_flock.so
8211bfe1f14051b636160c9fcc95bebaae0f60f1e4742b0dcf8740901b06c97a  libdsh_pty.so
e514abb095a68f26df08e34e0f99d272b0cada67aa0cc0cbe9aade7438d56433  libdshengine.so
d24ea324f6b02c04cf50956c300b405f407ce966fa6b01188df189e332a314f9  libdshnode.so
8d440bc2d34b852d7386db11c1fb2c534aba7d1024737e8fdd671f00aa308bc1  libnode.so
c14cdb389f34e504d69e386cfc67d5c5d9a730a990de03ca6910b2a15e30386a  librg.so'

# 最小集:没有这四个引擎根本起不来。
# librg / libdsh_pty / libdsh_flock 是给工具链用的(grep / PTY / 文件锁),先带着。
REQUIRED='libnode.so libc++_shared.so libdshengine.so libdshnode.so'

MODE="download"
LOCAL_DIR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --from) LOCAL_DIR="$2"; shift 2 ;;
    --download) MODE="download"; shift ;;
    --help|-h) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "不认识的参数: $1" >&2; exit 2 ;;
  esac
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_all() {
  local dir="$1" bad=0
  while read -r want name; do
    [ -n "$name" ] || continue
    if [ ! -f "$dir/$name" ]; then
      echo "✗ 缺 $name" >&2
      bad=1
      continue
    fi
    got="$(sha256_of "$dir/$name")"
    if [ "$got" != "$want" ]; then
      echo "✗ $name 校验和不符" >&2
      echo "    期望 $want" >&2
      echo "    实际 $got" >&2
      bad=1
      continue
    fi
    echo "  ✓ $name  ${got:0:16}"
  done <<< "$CHECKSUMS"
  [ "$bad" -eq 0 ] || return 1
}

install() {
  local src="$1"
  mkdir -p "$DEST"
  for f in libnode.so libc++_shared.so libdshengine.so libdshnode.so libdsh_flock.so libdsh_pty.so librg.so; do
    [ -f "$src/$f" ] && cp "$src/$f" "$DEST/$f"
  done
}

download() {
  # 镜像列表:gh-proxy 优先,失败退直连。
  #
  # 为什么镜像在前:这台机器上 github release 直连能连上但只有 ~60KB/s
  # (31MB 要 8 分钟),curl 会**成功**返回 —— 失败才轮得到镜像,而直连
  # 不会失败。所以镜像必须排前面,否则镜像形同虚设。
  #
  # 为什么敢用第三方镜像:完整性不靠传输通道保证 —— 靠下面的 sha256 校验。
  # 镜像送来的字节流算错 sha256 一律拒绝,镜像换不来任何后门。
  local urls=(
    "https://gh-proxy.com/$RELEASE_URL"
    "$RELEASE_URL"
  )
  local ok=0
  for u in "${urls[@]}"; do
    echo "下载 $u"
    # --http1.1:这台机器上 github 的 release 下载走 HTTP/2 会 PROTOCOL_ERROR
    # (curl 报 "HTTP/2 stream 1 was not closed cleanly"),强制降到 1.1 就稳。
    if curl -fsSL --http1.1 --retry 2 --connect-timeout 15 "$u" -o "$TMP/pkg.tar.gz"; then
      ok=1
      break
    fi
    echo "  ✗ 失败,换下一个源" >&2
  done
  [ "$ok" -eq 1 ] || return 1
  mkdir -p "$TMP/x"
  tar -C "$TMP/x" -xzf "$TMP/pkg.tar.gz"
  local d
  d="$(find "$TMP/x" -type d -name 'arm64-v8a' | head -1)"
  [ -n "$d" ] || { echo "✗ 解压出来的包里没有 arm64-v8a 目录" >&2; return 1; }
  verify_all "$d" || return 1
  install "$d"
  return 0
}

echo "拉原生库 → $DEST"

if [ "$MODE" = "download" ]; then
  if download; then
    echo "已安装(来自 release $TAG)"
  else
    echo "⚠️ 下载失败" >&2
    if [ -n "$LOCAL_DIR" ]; then
      echo "  退回本地目录 $LOCAL_DIR" >&2
      verify_all "$LOCAL_DIR" || { echo "✗ 本地目录校验失败,放弃" >&2; exit 1; }
      install "$LOCAL_DIR"
      echo "已安装(来自本地目录 $LOCAL_DIR)"
    else
      echo "  网络不通的话用 --from <目录> 从本地拷一份" >&2
      exit 1
    fi
  fi
else
  [ -n "$LOCAL_DIR" ] || { echo "--from 模式必须给目录" >&2; exit 2; }
  [ -d "$LOCAL_DIR" ] || { echo "目录不存在: $LOCAL_DIR" >&2; exit 1; }
  verify_all "$LOCAL_DIR" || { echo "✗ 本地目录校验失败,放弃" >&2; exit 1; }
  install "$LOCAL_DIR"
  echo "已安装(来自本地目录 $LOCAL_DIR)"
fi

# 最小集必须齐 —— 缺一个都是「装上了但引擎起不来」的静默失败
missing=""
for f in $REQUIRED; do
  [ -f "$DEST/$f" ] || missing="$missing $f"
done
if [ -n "$missing" ]; then
  echo "✗ 最小集不全,缺:$missing" >&2
  exit 1
fi

echo
echo "已安装 → $DEST"
du -sh "$DEST" | awk '{print "  总体积 " $1}'
ls -1 "$DEST" | sed 's/^/  /'
