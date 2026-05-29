#!/bin/sh
# sonar-cli.sh — bootstrap wrapper for the sonar-predictor distribution.
#
# Responsibilities (the bundle's own bin/sonar does NOT do these):
#   1. Fetch the dist zip from a corporate Nexus raw repo and extract it
#      into ~/.sonar-predictor/dist/<version>/sonar-predictor/.
#   2. Ensure a Java 21+ runtime is available. If JAVA_HOME or PATH already
#      has one, use it. Otherwise fetch a Temurin JDK 21 tarball from the
#      same Nexus and cache it under ~/.sonar-predictor/jdk/21/.
#   3. Hand off to the bundle's bin/sonar, which spawns the daemon child
#      process. Only one entrypoint to invoke — bin/sonar — so this wrapper
#      covers both "sonar CLI" and "sonar daemon" use cases.
#
# Required env:
#   SONAR_NEXUS_BASE      Nexus raw repo base URL (no trailing slash), e.g.
#                         https://nexus.example.com/repository/raw-internal
#
# Optional env:
#   SONAR_PREDICTOR_VERSION   Default: 0.3.0-SNAPSHOT
#   SONAR_PREDICTOR_HOME      Default: $HOME/.sonar-predictor
#   SONAR_PREDICTOR_FORCE     "1" to re-download even if cached
#
# Integrity verification (fail-closed by default):
#   Every artifact (dist zip, JDK tarball) is SHA-256-verified against a pinned
#   expected value BEFORE it is extracted or moved into place. This mirrors the
#   download->verify->promote contract the Java Downloader enforces, and matters
#   most for the JDK: it is the trust root that runs every downstream in-JVM
#   check, so an unverified JDK makes those checks moot.
#
#   SONAR_DIST_SHA256         Expected lowercase-hex SHA-256 of the dist zip.
#   SONAR_JDK_SHA256          Expected lowercase-hex SHA-256 of the JDK tarball.
#   If unset, the expected hash is read from a sibling checksum file at the SAME
#   base URL the artifact came from (SHA256SUMS for the dist — see publish.yml;
#   <tarball>.sha256.txt for Adoptium JDKs).
#   SONAR_ALLOW_UNVERIFIED    "1" to proceed when NO expected hash can be
#                             obtained. Prints a loud warning. Default (unset)
#                             refuses — no unverified bytes are extracted.
#
# Nexus path conventions (rename via search-replace if your layout differs):
#   {base}/sonar-predictor/{version}/sonar-predictor-dist-{version}.zip
#   {base}/sonar-predictor/{version}/SHA256SUMS  (sibling; lists dist-{version}.zip)
#   {base}/temurin/21/{os}-{arch}.tar.gz        (linux, mac)
#   {base}/temurin/21/{os}-{arch}.tar.gz.sha256.txt  (Adoptium sibling checksum)
#   {base}/temurin/21/{os}-{arch}.zip           (windows — handled by .ps1)
#
# POSIX sh — no bashisms. Tested with dash, bash, zsh.
set -eu

VERSION="${SONAR_PREDICTOR_VERSION:-0.3.0-SNAPSHOT}"
HOME_DIR="${SONAR_PREDICTOR_HOME:-$HOME/.sonar-predictor}"
MIN_JAVA_MAJOR=21

# --- Pre-flight ------------------------------------------------------------
die() { echo "sonar-cli: $*" >&2; exit 2; }

[ -n "${SONAR_NEXUS_BASE:-}" ] || die "SONAR_NEXUS_BASE is required (Nexus raw repo base URL, no trailing slash)."
NEXUS_BASE="${SONAR_NEXUS_BASE%/}"

# --- OS / arch detection ---------------------------------------------------
detect_os() {
    case "$(uname -s)" in
        Linux)   echo linux ;;
        Darwin)  echo mac ;;
        *)       die "unsupported OS: $(uname -s). On Windows use scripts/sonar-cli.ps1." ;;
    esac
}

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64)   echo x64 ;;
        aarch64|arm64)  echo aarch64 ;;
        *)              die "unsupported arch: $(uname -m). Expected x86_64 or aarch64/arm64." ;;
    esac
}

OS=$(detect_os)
ARCH=$(detect_arch)

# --- Tool selection --------------------------------------------------------
# Prefer curl; fall back to wget. Neither = bail (we can't bootstrap offline).
DOWNLOADER=""
if command -v curl >/dev/null 2>&1; then
    DOWNLOADER=curl
elif command -v wget >/dev/null 2>&1; then
    DOWNLOADER=wget
else
    die "neither curl nor wget is on PATH — install one to bootstrap."
fi

command -v unzip >/dev/null 2>&1 || die "unzip is required (extracts the dist zip)."
command -v tar   >/dev/null 2>&1 || die "tar is required (extracts the JDK tarball)."

# SHA-256 tool: sha256sum (Linux) or `shasum -a 256` (macOS). Required to
# verify artifact integrity before extract.
SHA256_TOOL=""
if command -v sha256sum >/dev/null 2>&1; then
    SHA256_TOOL="sha256sum"
elif command -v shasum >/dev/null 2>&1; then
    SHA256_TOOL="shasum -a 256"
else
    die "neither sha256sum nor shasum is on PATH — required to verify artifact integrity."
fi

# --- Download helpers ------------------------------------------------------
# fetch URL TARGET — atomic write via .partial; fails loudly on HTTP error.
fetch() {
    _url="$1"
    _target="$2"
    _tmp="${_target}.partial"
    mkdir -p "$(dirname "$_target")"
    rm -f "$_tmp"
    echo "  fetching $_url"
    case "$DOWNLOADER" in
        curl)
            curl --fail --silent --show-error --location \
                 --output "$_tmp" "$_url" \
                || { rm -f "$_tmp"; die "download failed: $_url"; }
            ;;
        wget)
            wget --quiet --output-document="$_tmp" "$_url" \
                || { rm -f "$_tmp"; die "download failed: $_url"; }
            ;;
    esac
    mv "$_tmp" "$_target"
}

# fetch_optional URL TARGET — like fetch, but returns non-zero instead of
# dying when the URL is absent (used for sibling checksum files, which may not
# exist on every mirror). Leaves no partial behind on failure.
fetch_optional() {
    _url="$1"
    _target="$2"
    _tmp="${_target}.partial"
    mkdir -p "$(dirname "$_target")"
    rm -f "$_tmp"
    case "$DOWNLOADER" in
        curl)
            curl --fail --silent --show-error --location \
                 --output "$_tmp" "$_url" >/dev/null 2>&1 \
                || { rm -f "$_tmp"; return 1; }
            ;;
        wget)
            wget --quiet --output-document="$_tmp" "$_url" >/dev/null 2>&1 \
                || { rm -f "$_tmp"; return 1; }
            ;;
    esac
    mv "$_tmp" "$_target"
}

# --- Integrity verification ------------------------------------------------
# sha256_of FILE — prints the lowercase-hex SHA-256 of FILE using whichever
# standard tool is present (sha256sum on Linux, shasum -a 256 on macOS).
sha256_of() {
    _f="$1"
    if [ -n "$SHA256_TOOL" ]; then
        # Word-splitting of SHA256_TOOL is intentional (e.g. "shasum -a 256").
        # shellcheck disable=SC2086
        $SHA256_TOOL "$_f" | awk '{print $1}'
    else
        die "no SHA-256 tool available (need sha256sum or shasum)."
    fi
}

# checksum_for_artifact SUMS_FILE ARTIFACT_NAME — extracts the expected hash
# for ARTIFACT_NAME from a checksum file. Handles both the multi-line GNU
# "<hash>  <name>" SHA256SUMS format and a single bare/"<hash>  <name>" line as
# Adoptium publishes in <tarball>.sha256.txt. Prints the lowercase hash, or
# nothing if not found.
checksum_for_artifact() {
    _sums="$1"
    _name="$2"
    # Prefer a line that names the artifact; fall back to a lone bare hash
    # (Adoptium's per-file .sha256.txt is sometimes just "<hash>  <name>").
    _line=$(grep -E "[[:space:]]\*?${_name}\$" "$_sums" 2>/dev/null | head -n 1)
    if [ -z "$_line" ]; then
        # Single-entry file: take the first field of the first non-empty line.
        _line=$(grep -E '^[0-9a-fA-F]{64}([[:space:]]|$)' "$_sums" 2>/dev/null | head -n 1)
    fi
    [ -n "$_line" ] || return 0
    printf '%s' "$_line" | awk '{print $1}' | tr 'A-Z' 'a-z'
}

# expected_sha ENV_OVERRIDE ARTIFACT_URL ARTIFACT_NAME SUMS_URL CACHE_DIR
#   Resolves the expected SHA-256 in priority order:
#     1. ENV_OVERRIDE (already-resolved value passed by caller), if non-empty;
#     2. the sibling checksum file fetched from SUMS_URL.
#   Prints the lowercase-hex hash on success, or nothing if none could be
#   obtained. Never extracts/promotes anything.
expected_sha() {
    _override="$1"
    _name="$3"
    _sums_url="$4"
    _cache="$5"
    if [ -n "$_override" ]; then
        printf '%s' "$_override" | tr 'A-Z' 'a-z'
        return 0
    fi
    _sums_file="$_cache/$(basename "$_sums_url")"
    if fetch_optional "$_sums_url" "$_sums_file"; then
        checksum_for_artifact "$_sums_file" "$_name"
    fi
}

# verify_artifact FILE ARTIFACT_URL ARTIFACT_NAME ENV_OVERRIDE SUMS_URL
#   Verifies FILE's SHA-256 against the resolved expected value BEFORE any
#   extract/move. On mismatch: deletes FILE and dies non-zero, naming expected
#   vs actual. If no expected hash is obtainable: dies unless
#   SONAR_ALLOW_UNVERIFIED=1, in which case it prints a loud warning and
#   returns. Never returns success on a mismatch.
verify_artifact() {
    _file="$1"
    _art_url="$2"
    _name="$3"
    _override="$4"
    _sums_url="$5"
    _cache=$(dirname "$_file")

    _exp=$(expected_sha "$_override" "$_art_url" "$_name" "$_sums_url" "$_cache")
    if [ -z "$_exp" ]; then
        if [ "${SONAR_ALLOW_UNVERIFIED:-}" = "1" ]; then
            echo "sonar-cli: WARNING: no SHA-256 available for $_name; proceeding UNVERIFIED" >&2
            echo "sonar-cli: WARNING: SONAR_ALLOW_UNVERIFIED=1 bypasses integrity checks — this is NOT recommended." >&2
            return 0
        fi
        rm -f "$_file"
        die "no expected SHA-256 for $_name (set SONAR_DIST_SHA256/SONAR_JDK_SHA256, publish a sibling checksum file, or set SONAR_ALLOW_UNVERIFIED=1 to override). Refusing to extract unverified bytes."
    fi
    _act=$(sha256_of "$_file")
    if [ "$_act" != "$_exp" ]; then
        rm -f "$_file"
        die "SHA-256 mismatch for $_name: expected $_exp but got $_act. Refusing to extract."
    fi
    echo "  verified $_name (sha256 $_exp)"
}

# --- Bundle: ensure ~/.sonar-predictor/dist/<v>/sonar-predictor/bin/sonar --
DIST_DIR="$HOME_DIR/dist/$VERSION"
BUNDLE_DIR="$DIST_DIR/sonar-predictor"
BUNDLE_MARKER="$BUNDLE_DIR/bin/sonar"

ensure_bundle() {
    if [ "${SONAR_PREDICTOR_FORCE:-}" != "1" ] && [ -x "$BUNDLE_MARKER" ]; then
        return 0
    fi
    echo "sonar-cli: provisioning sonar-predictor $VERSION into $DIST_DIR"
    _zip="$HOME_DIR/cache/sonar-predictor-dist-$VERSION.zip"
    _name="sonar-predictor-dist-$VERSION.zip"
    _url="$NEXUS_BASE/sonar-predictor/$VERSION/$_name"
    _sums_url="$NEXUS_BASE/sonar-predictor/$VERSION/SHA256SUMS"
    fetch "$_url" "$_zip"
    # Verify integrity BEFORE extracting or touching the install location.
    verify_artifact "$_zip" "$_url" "$_name" "${SONAR_DIST_SHA256:-}" "$_sums_url"
    rm -rf "$DIST_DIR"
    mkdir -p "$DIST_DIR"
    unzip -q "$_zip" -d "$DIST_DIR" || die "could not extract $_zip"
    [ -x "$BUNDLE_MARKER" ] || die "bundle missing bin/sonar after extract: $BUNDLE_DIR"
}

# --- Java 21+ detection (matches bundle's bin/sonar logic) -----------------
java_major() {
    _j="$1"
    [ -x "$_j" ] || return 1
    _v=$("$_j" -version 2>&1 | head -n 1 | sed -e 's/.*version "//' -e 's/".*//')
    [ -n "$_v" ] || return 1
    case "$_v" in
        1.*) echo "$_v" | cut -d. -f2 ;;   # legacy 1.8 -> 8
        *)   echo "$_v" | cut -d. -f1 ;;   # 17.0.10  -> 17
    esac
}

is_java_min_plus() {
    _m=$(java_major "$1" 2>/dev/null) || return 1
    [ -n "$_m" ] || return 1
    [ "$_m" -ge "$MIN_JAVA_MAJOR" ] 2>/dev/null || return 1
}

# Prints a Java 21+ executable path, or nothing if none found in standard
# locations (the same search the bundle's bin/sonar does — kept symmetric so
# a user with a system JDK never pays the JDK-download cost).
find_system_java() {
    if [ -n "${JAVA_HOME:-}" ] && is_java_min_plus "$JAVA_HOME/bin/java"; then
        echo "$JAVA_HOME/bin/java"; return 0
    fi
    _path_java=$(command -v java 2>/dev/null || true)
    if [ -n "$_path_java" ] && is_java_min_plus "$_path_java"; then
        echo "$_path_java"; return 0
    fi
    for _cand in \
        /usr/lib/jvm/*/bin/java \
        /usr/java/*/bin/java \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
        "${HOME:-/nonexistent}"/.sdkman/candidates/java/*/bin/java \
        /opt/java/*/bin/java \
        /opt/*/bin/java
    do
        [ -x "$_cand" ] || continue
        if is_java_min_plus "$_cand"; then
            echo "$_cand"; return 0
        fi
    done
    return 1
}

# --- JDK provisioning (only if no system Java 21+) -------------------------
JDK_DIR="$HOME_DIR/jdk/21/$OS-$ARCH"
JDK_MARKER="$JDK_DIR/bin/java"

ensure_cached_jdk() {
    if [ -x "$JDK_MARKER" ] && is_java_min_plus "$JDK_MARKER"; then
        return 0
    fi
    echo "sonar-cli: provisioning Temurin JDK 21 ($OS-$ARCH) into $JDK_DIR"
    _archive="$HOME_DIR/cache/temurin-21-$OS-$ARCH.tar.gz"
    _name="$OS-$ARCH.tar.gz"
    _url="$NEXUS_BASE/temurin/21/$_name"
    _sums_url="$_url.sha256.txt"
    fetch "$_url" "$_archive"
    # Verify integrity BEFORE extracting — the JDK is the trust root that runs
    # every downstream in-JVM SHA-256 check, so this gate is the critical one.
    verify_artifact "$_archive" "$_url" "$_name" "${SONAR_JDK_SHA256:-}" "$_sums_url"

    # Extract into a staging dir, then move the (single) top-level JDK dir
    # contents into $JDK_DIR. Temurin tarballs nest one level (jdk-21.0.5+11/),
    # so we strip it.
    _stage="$HOME_DIR/cache/jdk-stage-$$"
    rm -rf "$_stage"
    mkdir -p "$_stage"
    tar -xzf "$_archive" -C "$_stage" || die "could not extract $_archive"

    # macOS tarballs have a Contents/Home/ layer; Linux tarballs don't.
    _root=$(find "$_stage" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    [ -n "$_root" ] || die "JDK tarball appears empty: $_archive"
    if [ -d "$_root/Contents/Home" ]; then
        _jdk_home="$_root/Contents/Home"
    else
        _jdk_home="$_root"
    fi
    [ -x "$_jdk_home/bin/java" ] || die "JDK tarball missing bin/java: $_archive"

    rm -rf "$JDK_DIR"
    mkdir -p "$(dirname "$JDK_DIR")"
    mv "$_jdk_home" "$JDK_DIR"
    rm -rf "$_stage"

    is_java_min_plus "$JDK_MARKER" || die "cached JDK is not version $MIN_JAVA_MAJOR+: $JDK_MARKER"
}

# --- Orchestrate -----------------------------------------------------------
ensure_bundle

JAVA=$(find_system_java || true)
if [ -z "$JAVA" ]; then
    ensure_cached_jdk
    JAVA_HOME="$JDK_DIR"
    export JAVA_HOME
    # The bundle's bin/sonar discovers Java via JAVA_HOME / PATH / common
    # locations; setting JAVA_HOME is the lowest-friction handoff.
fi

# --- Hand off --------------------------------------------------------------
# Quote everything; exec replaces this shell so signals propagate cleanly.
exec "$BUNDLE_MARKER" "$@"
