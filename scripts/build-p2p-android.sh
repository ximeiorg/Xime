#!/usr/bin/env bash
# 把 libximep2p（独立仓库，协议 crate 名为 xime-p2p）交叉编译成 Android JNI 动态库。
#
#   bash scripts/build-p2p-android.sh              # 四个 ABI 全编
#   ABI=arm64-v8a bash scripts/build-p2p-android.sh
#
# 产物：app/src/main/jniLibs/<abi>/libxime_p2p_jni.so（不入库）
# 源码：默认 third_party/libximep2p（独立协议仓库），找不到时依次回退到
#       同级 ../libximep2p、app/src/main/jni/libximecore（开发机 submodule
#       形态）、同级 ../libximecore；也可用 XIME_P2P_SRC 显式覆盖。
#       源码仓库必须含 crates/{xime-sync-domain,xime-p2p,xime-p2p-c}。
#
# JNI 绑定 crate 在 app/src/main/rust/xime-p2p-jni（符号名绑定 Kotlin 包名，
# 所以不放进共享仓库）。这里把它和 libximep2p 的三个 crate 拼成一个临时
# workspace 编译：不改动任何仓库的 Cargo.toml / Cargo.lock。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── 源码位置 ────────────────────────────────────────────────
if [ -n "${XIME_P2P_SRC:-}" ]; then
  SRC="$XIME_P2P_SRC"
else
  SRC=""
  for candidate in \
    "$ROOT/third_party/libximep2p" \
    "$ROOT/../libximep2p" \
    "$ROOT/app/src/main/jni/libximecore" \
    "$ROOT/../libximecore"; do
    if [ -d "$candidate/crates/xime-p2p-c" ]; then
      SRC="$candidate"
      break
    fi
  done
fi
if [ ! -d "$SRC/crates/xime-p2p-c" ]; then
  echo "找不到 p2p 协议源码（${SRC:-未指定}），请先初始化 submodule 或用 XIME_P2P_SRC 指定" >&2
  exit 1
fi
echo "==> 协议源码：$SRC"

JNI_CRATE="$ROOT/app/src/main/rust/xime-p2p-jni"
if [ ! -f "$JNI_CRATE/Cargo.toml" ]; then
  echo "找不到 JNI 绑定 crate：$JNI_CRATE" >&2
  exit 1
fi

# ── NDK ────────────────────────────────────────────────────
# 与 app/build.gradle.kts 的 ndkVersion 保持一致
NDK_VERSION="${NDK_VERSION:-29.0.14206865}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1)"
fi
# CI / F-Droid 构建机常常只有 sdkmanager 在 PATH 里，没有导出 SDK 根目录：
# 从 sdkmanager 的位置反推（$SDK/cmdline-tools/<ver>/bin/sdkmanager）。
if { [ -z "$SDK" ] || [ ! -d "$SDK" ]; } && command -v sdkmanager >/dev/null 2>&1; then
  SDK="$(cd "$(dirname "$(dirname "$(dirname "$(command -v sdkmanager)")")")" && pwd)"
fi
SDK="${SDK:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  if [ -d "$SDK/ndk/$NDK_VERSION" ]; then
    NDK="$SDK/ndk/$NDK_VERSION"
  else
    NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
  fi
fi
# 目标版本缺失时自己装一份：CI 与 F-Droid 的构建机上 NDK 通常不预装，
# 而 gradle 的 ndkVersion 只保证 AGP 那条路径，不保证脚本这条。
if [ ! -d "$SDK/ndk/$NDK_VERSION" ] && command -v sdkmanager >/dev/null 2>&1; then
  echo "==> 安装 NDK $NDK_VERSION"
  yes | sdkmanager "ndk;$NDK_VERSION" >/dev/null 2>&1 || true
  [ -d "$SDK/ndk/$NDK_VERSION" ] && NDK="$SDK/ndk/$NDK_VERSION"
fi
[ -d "$NDK" ] || { echo "找不到 NDK：$NDK" >&2; exit 1; }

# 预编译工具链目录随开发机系统变化：macOS（含 Apple Silicon）是
# darwin-x86_64，Linux CI 是 linux-x86_64。以前写死 linux-x86_64，
# 在 macOS 上必然报「找不到 NDK 工具链」。
HOST_TAG=""
for tag in darwin-x86_64 linux-x86_64 linux-aarch64 windows-x86_64; do
  if [ -x "$NDK/toolchains/llvm/prebuilt/$tag/bin/llvm-ar" ]; then
    HOST_TAG="$tag"
    break
  fi
done
[ -n "$HOST_TAG" ] || { echo "NDK 缺少预编译工具链：$NDK/toolchains/llvm/prebuilt" >&2; exit 1; }
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
echo "==> NDK：$NDK（$HOST_TAG）"

# 与 minSdk 一致：clang wrapper 的 API 级别
API_LEVEL="${API_LEVEL:-28}"

WORK="$ROOT/build/p2p-src"
TARGET_DIR="$ROOT/build/p2p-target"
OUT="$ROOT/app/src/main/jniLibs"

# ── 拼接临时 workspace ─────────────────────────────────────
prepare_workspace() {
  echo "==> 准备临时 cargo workspace（$WORK）"
  rm -rf "$WORK"
  mkdir -p "$WORK/crates"
  for crate in xime-sync-domain xime-p2p xime-p2p-c; do
    cp -a "$SRC/crates/$crate" "$WORK/crates/$crate"
  done
  cp -a "$JNI_CRATE" "$WORK/crates/xime-p2p-jni"

  # 必须继承源仓库的 [workspace.package] / [workspace.dependencies]：
  # xime-p2p-c 里大量 `version.workspace = true`、`serde.workspace = true`，
  # 只写 [workspace] 会让 cargo 直接报 "workspace.package was not defined"。
  # 这里复制整份 Cargo.toml，仅把成员裁剪到 P2P 链路 + JNI 绑定。
  python3 - "$SRC/Cargo.toml" "$WORK/Cargo.toml" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
members = '''members = [
    "crates/xime-sync-domain",
    "crates/xime-p2p",
    "crates/xime-p2p-c",
    "crates/xime-p2p-jni",
]'''
text, n = re.subn(r"^members\s*=\s*\[.*?^\]", members, text,
                  count=1, flags=re.S | re.M)
if n == 0:
    raise SystemExit("源 Cargo.toml 缺少 members，无法生成裁剪 workspace")
text = re.sub(r"^default-members\s*=\s*\[.*?^\]",
              'default-members = [\n    "crates/xime-p2p-jni",\n]',
              text, count=1, flags=re.S | re.M)
open(dst, "w", encoding="utf-8").write(text)
PY
  if [ -f "$SRC/Cargo.lock" ]; then
    cp "$SRC/Cargo.lock" "$WORK/Cargo.lock"
  fi
}

# ── 逐 ABI 编译 ────────────────────────────────────────────
build_abi() {
  local abi="$1" target="$2" clang_name="$3"
  local cc="$BIN/$clang_name"
  local target_upper target_cc
  target_upper="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  target_cc="${target//-/_}"

  echo "==> $abi（$target）"
  rustup target add "$target" >/dev/null 2>&1 || true

  env \
    "CARGO_TARGET_${target_upper}_LINKER=$cc" \
    "CC_${target_cc}=$cc" \
    "AR_${target_cc}=$BIN/llvm-ar" \
    "RANLIB_${target_cc}=$BIN/llvm-ranlib" \
    "RUSTFLAGS=--remap-path-prefix=$SRC= --remap-path-prefix=$WORK= -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C strip=symbols" \
    cargo build -p xime-p2p-jni \
      --target "$target" \
      --release \
      --manifest-path "$WORK/Cargo.toml" \
      --target-dir "$TARGET_DIR"

  mkdir -p "$OUT/$abi"
  cp "$TARGET_DIR/$target/release/libxime_p2p_jni.so" "$OUT/$abi/libxime_p2p_jni.so"
  ls -lh "$OUT/$abi/libxime_p2p_jni.so"
}

prepare_workspace

case "${ABI:-all}" in
  arm64-v8a)   build_abi arm64-v8a   aarch64-linux-android      "aarch64-linux-android${API_LEVEL}-clang" ;;
  armeabi-v7a) build_abi armeabi-v7a armv7-linux-androideabi    "armv7a-linux-androideabi${API_LEVEL}-clang" ;;
  x86)         build_abi x86         i686-linux-android         "i686-linux-android${API_LEVEL}-clang" ;;
  x86_64)      build_abi x86_64      x86_64-linux-android       "x86_64-linux-android${API_LEVEL}-clang" ;;
  all)
    build_abi arm64-v8a   aarch64-linux-android   "aarch64-linux-android${API_LEVEL}-clang"
    build_abi armeabi-v7a armv7-linux-androideabi "armv7a-linux-androideabi${API_LEVEL}-clang"
    build_abi x86         i686-linux-android      "i686-linux-android${API_LEVEL}-clang"
    build_abi x86_64      x86_64-linux-android    "x86_64-linux-android${API_LEVEL}-clang"
    ;;
  *)
    echo "不支持的 ABI：$ABI" >&2
    exit 1
    ;;
esac

echo "完成：$OUT"
