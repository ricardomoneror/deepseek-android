#!/usr/bin/env bash
#
# 07-javac.sh - compile OpenCode Android Java host sources + generated R/BuildConfig (UTF-8).
#

log "[7/11] javac (UTF-8)"
mapfile -d '' JAVA_SOURCES < <(find "$JAVA_DIR" -name '*.java' -print0 | sort -z)
[ "${#JAVA_SOURCES[@]}" -gt 0 ] || die "no java sources under $JAVA_DIR"
mapfile -d '' GEN_SOURCES < <(find "$GEN_DIR" -name '*.java' -print0 | sort -z)
[ "${#GEN_SOURCES[@]}" -gt 0 ] || die "no generated sources under $GEN_DIR"

log "  compiling ${#JAVA_SOURCES[@]} app sources + ${#GEN_SOURCES[@]} generated sources"
# [CI] El r8.jar del repo es R8 8.2, que no entiende class files de Java 25
# (major 69). Se fija Java 17 (major 61), que si soporta.
JAVA_RELEASE="${DSH_JAVA_RELEASE:-17}"
javac -encoding UTF-8 -J-Dfile.encoding=UTF-8 -J-Dsun.jnu.encoding=UTF-8 \
    --release "$JAVA_RELEASE" \
    -d "$OBJ_DIR" \
    -cp "$ANDROID_JAR" \
    "${GEN_SOURCES[@]}" \
    "${JAVA_SOURCES[@]}"
