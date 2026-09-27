#!/usr/bin/env bash
# Находит JDK 21+ (именно JDK, с javac) или, с согласия, скачивает Eclipse Temurin 21 в .jdk/ репозитория.
# Печатает путь к JDK (JAVA_HOME) в stdout; сообщения — в stderr.
# Каталог .jdk/ общий с Unity-клиентом: JDK, установленный игрой, подхватывается скриптами и наоборот.
#
# Переменные окружения:
#   SUBROUTINE_AUTO_JDK=1   скачивать без вопроса (CI, неинтерактивный запуск)
#   SUBROUTINE_JDK_URL=…    свой адрес архива (зеркало); рядом должен лежать «<адрес>.sha256.txt»
#   SUBROUTINE_JDK_MANAGED_ONLY=1  искать только в .jdk/ (для тестов установки)
set -euo pipefail

REQUIRED=21
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANAGED="$ROOT/.jdk"

log() { echo "$*" >&2; }

major_version() {
    # «openjdk version "21.0.4"» → 21, «java version "1.8.0_392"» → 8
    local out
    out="$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS "$1" -version 2>&1 || true)"
    echo "$out" | sed -n 's/.*version "\([0-9][0-9]*\)\(\.\([0-9][0-9]*\)\)\{0,1\}.*/\1 \3/p' | head -n1 |
        awk '{ if ($1 == 1 && $2 != "") print $2; else print $1 }'
}

suitable() {
    local home="$1"
    [ -x "$home/bin/java" ] && [ -x "$home/bin/javac" ] || return 1
    local major
    major="$(major_version "$home/bin/java")"
    [ -n "$major" ] && [ "$major" -ge "$REQUIRED" ]
}

candidates() {
    if [ -d "$MANAGED" ]; then
        find "$MANAGED" -maxdepth 4 -path '*/bin/java' -not -path '*/.partial*' 2>/dev/null | sort -r | sed 's#/bin/java$##'
    fi
    [ "${SUBROUTINE_JDK_MANAGED_ONLY:-}" = "1" ] && return 0
    [ -n "${JAVA_HOME:-}" ] && echo "$JAVA_HOME"
    if command -v java >/dev/null 2>&1; then
        local java
        java="$(command -v java)"
        java="$(readlink -f "$java" 2>/dev/null || python3 -c 'import os,sys;print(os.path.realpath(sys.argv[1]))' "$java" 2>/dev/null || echo "$java")"
        dirname "$(dirname "$java")"
    fi
    for root in /usr/lib/jvm /Library/Java/JavaVirtualMachines /opt/homebrew/opt /usr/local/opt; do
        [ -d "$root" ] && find "$root" -maxdepth 3 -path '*/bin/java' 2>/dev/null | sort -r | sed 's#/bin/java$##'
    done
    return 0
}

while IFS= read -r home; do
    if [ -n "$home" ] && suitable "$home"; then
        echo "$home"
        exit 0
    fi
done < <(candidates)

# ---------------------------------------------------------------- установка Temurin 21
case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=mac ;;
    *) log "Неподдерживаемая ОС $(uname -s): установите JDK $REQUIRED вручную."; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) arch=x64 ;;
    arm64|aarch64) arch=aarch64 ;;
    *) log "Неподдерживаемая архитектура $(uname -m): установите JDK $REQUIRED вручную."; exit 1 ;;
esac
url="${SUBROUTINE_JDK_URL:-https://api.adoptium.net/v3/binary/latest/$REQUIRED/ga/$os/$arch/jdk/hotspot/normal/eclipse}"

log "Не найден JDK $REQUIRED+ (с javac)."
if [ "${SUBROUTINE_AUTO_JDK:-}" != "1" ]; then
    if [ -t 0 ] && [ -t 2 ]; then
        read -r -p "Скачать Eclipse Temurin $REQUIRED (~200 МБ, проверка SHA-256) в $MANAGED? [Y/n] " answer >&2
        case "${answer:-y}" in [yYдД]*) ;; *) log "Отменено. Установите JDK $REQUIRED и задайте JAVA_HOME."; exit 1 ;; esac
    else
        log "Запустите с SUBROUTINE_AUTO_JDK=1 для автоматической установки или установите JDK $REQUIRED."
        exit 1
    fi
fi

command -v curl >/dev/null || { log "Нужен curl."; exit 1; }
mkdir -p "$MANAGED"
archive="$MANAGED/.download.tar.gz"
partial="$MANAGED/.partial-$$"
cleanup() { rm -rf "$archive" "$partial"; }
trap cleanup EXIT

log "Скачивание: $url"
final="$(curl -fL --progress-bar -o "$archive" -w '%{url_effective}' "$url")"
final="${final%%\?*}"
expected="$(curl -fsSL "$final.sha256.txt" | awk '{print $1}')" || true
if [ "${#expected}" -ne 64 ]; then
    log "Нет контрольной суммы ($final.sha256.txt). Установка без проверки запрещена."
    exit 1
fi
if command -v sha256sum >/dev/null; then actual="$(sha256sum "$archive" | awk '{print $1}')"; else actual="$(shasum -a 256 "$archive" | awk '{print $1}')"; fi
if [ "$expected" != "$actual" ]; then
    log "Контрольная сумма не совпала: ожидалась $expected, получена $actual."
    exit 1
fi
log "SHA-256 совпадает."

mkdir -p "$partial"
tar -xzf "$archive" -C "$partial"
top="$(find "$partial" -mindepth 1 -maxdepth 1 -type d | head -n1)"
[ -n "$top" ] || { log "Пустой архив JDK."; exit 1; }
target="$MANAGED/$(basename "$top")"
rm -rf "$target"
mv "$top" "$target"
home="$(find "$target" -maxdepth 3 -path '*/bin/java' | head -n1 | sed 's#/bin/java$##')"
suitable "$home" || { log "Установленный JDK не запускается: $home"; exit 1; }
log "JDK установлен: $home"
echo "$home"
