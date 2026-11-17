#!/usr/bin/env bash
#
# 03-rust-bootstrap.sh - compile native Rust ARM64 JNI bootstrap engine (libds_bootstrap.so)
# and stage lib/arm64-v8a/{libzstd.so,libds_bootstrap.so}.
#

log "[2/12] compile native Rust ARM64 JNI bootstrap engine (libds_bootstrap.so)"

RUST_BOOTSTRAP_DIR="$TOOLS_DIR/rust-bootstrap"
PREBUILT_ZSTD="$RUST_BOOTSTRAP_DIR/prebuilt/arm64-v8a/libzstd.so"
JNI_SHIM_C="$RUST_BOOTSTRAP_DIR/src/jni_shim.c"
RUST_LIB_RS="$RUST_BOOTSTRAP_DIR/src/lib.rs"

[ -f "$PREBUILT_ZSTD" ] || die "prebuilt Bionic libzstd.so missing: $PREBUILT_ZSTD"
[ -f "$JNI_SHIM_C" ]    || die "jni_shim.c missing: $JNI_SHIM_C"
[ -f "$RUST_LIB_RS" ]   || die "lib.rs missing: $RUST_LIB_RS"

# [CI] En el dispositivo se compila nativo; en CI se hace cadena cruzada
# con el NDK, apuntando a aarch64-linux-android.
RUST_TARGET="${DSH_RUST_TARGET:-}"
CLANG_BIN="${DSH_CC:-clang}"
RUST_LINKER="${DSH_RUST_LINKER:-}"
CLANG_TARGET_ARGS=""
if [ -n "$RUST_TARGET" ]; then
    CLANG_TARGET_ARGS="--target=aarch64-linux-android${DSH_API_LEVEL:-28}"
    log "  cadena cruzada: target=$RUST_TARGET clang=$CLANG_BIN"
fi

RUST_WORK_BASE="${TMPDIR:-${PREFIX:-/data/user/0/com.agy/files/usr}/tmp}"
mkdir -p "$RUST_WORK_BASE"
RUST_WORK="$(mktemp -d "$RUST_WORK_BASE/ds-rust-bootstrap.XXXXXX")"
trap 'rm -rf "$RUST_WORK"' EXIT

SYS_INC="${PREFIX:-/data/user/0/com.agy/files/usr}/include"
cp -f "$PREBUILT_ZSTD" "$RUST_WORK/libzstd.so"

$CLANG_BIN $CLANG_TARGET_ARGS -O3 -fPIC -Wall -Wextra -Werror \
    -I"$SYS_INC" \
    -c "$JNI_SHIM_C" \
    -o "$RUST_WORK/jni_shim.o"

RUST_EXTRA=()
if [ -n "$RUST_TARGET" ]; then
    RUST_EXTRA+=(--target "$RUST_TARGET")
    [ -n "$RUST_LINKER" ] && RUST_EXTRA+=(-C "linker=$RUST_LINKER")
fi

rustc --edition 2021 --crate-type cdylib \
    "${RUST_EXTRA[@]}" \
    -C opt-level=3 \
    -C codegen-units=1 \
    -C lto=fat \
    -C target-feature=+lse,+crc \
    -C strip=symbols \
    -C link-arg="$RUST_WORK/jni_shim.o" \
    -C link-arg=-Wl,-soname,libds_bootstrap.so \
    -L "$RUST_WORK" \
    "$RUST_LIB_RS" \
    -o "$RUST_WORK/libds_bootstrap.so"

patchelf --remove-rpath "$RUST_WORK/libds_bootstrap.so"

STAGE_JNI="$STAGE_APK/lib/arm64-v8a"
mkdir -p "$STAGE_JNI"
cp -f "$RUST_WORK/libzstd.so" "$STAGE_JNI/libzstd.so"
cp -f "$RUST_WORK/libds_bootstrap.so" "$STAGE_JNI/libds_bootstrap.so"

rm -rf "$RUST_WORK"
trap - EXIT
log "  staged lib/arm64-v8a/{libzstd.so,libds_bootstrap.so}"
