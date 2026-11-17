#!/usr/bin/env bash
#
# 03-rust-bootstrap.sh - compile native Rust ARM64 JNI bootstrap engine (libds_bootstrap.so)
# and stage lib/arm64-v8a/{libzstd.so,libds_bootstrap.so}.
#

log "[2/12] compile native Rust ARM64 JNI bootstrap engine (libds_bootstrap.so)"

# [CI] El motor nativo se compila EN el dispositivo: necesita el prefijo de
# Android (cabeceras Bionic) y un rustc con destino aarch64-linux-android.
# En un runner x86_64 no es posible sin cadena de compilacion cruzada.
# Sin este .so la app usa la extraccion en Java, mas lenta pero funcional.
if [ "${DSH_SKIP_RUST:-0}" = "1" ]; then
    log "[2/12] motor Rust OMITIDO (DSH_SKIP_RUST=1) — se usara extraccion Java"
    return 0 2>/dev/null || exit 0
fi

RUST_BOOTSTRAP_DIR="$TOOLS_DIR/rust-bootstrap"
PREBUILT_ZSTD="$RUST_BOOTSTRAP_DIR/prebuilt/arm64-v8a/libzstd.so"
JNI_SHIM_C="$RUST_BOOTSTRAP_DIR/src/jni_shim.c"
RUST_LIB_RS="$RUST_BOOTSTRAP_DIR/src/lib.rs"

[ -f "$PREBUILT_ZSTD" ] || die "prebuilt Bionic libzstd.so missing: $PREBUILT_ZSTD"
[ -f "$JNI_SHIM_C" ]    || die "jni_shim.c missing: $JNI_SHIM_C"
[ -f "$RUST_LIB_RS" ]   || die "lib.rs missing: $RUST_LIB_RS"

RUST_WORK_BASE="${TMPDIR:-${PREFIX:-/data/user/0/com.agy/files/usr}/tmp}"
mkdir -p "$RUST_WORK_BASE"
RUST_WORK="$(mktemp -d "$RUST_WORK_BASE/ds-rust-bootstrap.XXXXXX")"
trap 'rm -rf "$RUST_WORK"' EXIT

SYS_INC="${PREFIX:-/data/user/0/com.agy/files/usr}/include"
cp -f "$PREBUILT_ZSTD" "$RUST_WORK/libzstd.so"

clang -O3 -fPIC -Wall -Wextra -Werror \
    -I"$SYS_INC" \
    -c "$JNI_SHIM_C" \
    -o "$RUST_WORK/jni_shim.o"

rustc --edition 2021 --crate-type cdylib \
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
