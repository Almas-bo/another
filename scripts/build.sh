#!/usr/bin/env bash
# Сборка сервера песочницы без Maven. JDK находится (или, с согласия, скачивается) автоматически.
# Инкрементально: пересборка, только если исходники новее метки target/classes/.build-stamp
# (ту же метку пишет и проверяет Unity-клиент). --force — собрать заново в любом случае.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="$("$(dirname "$0")/ensure-jdk.sh")"
export JAVA_HOME
stamp=target/classes/.build-stamp

if [ "${1:-}" != "--force" ] && [ -f "$stamp" ] && [ -z "$(find src/main/java -name '*.java' -newer "$stamp" | head -n1)" ]; then
    echo "Сервер собран и актуален: target/classes" >&2
    exit 0
fi

rm -rf target/classes
mkdir -p target/classes
find src/main/java -name "*.java" | sort | sed 's/.*/"&"/' > target/sources.txt
env -u JAVA_TOOL_OPTIONS "$JAVA_HOME/bin/javac" -encoding UTF-8 --release 21 -parameters -d target/classes @target/sources.txt
date -u +%Y-%m-%dT%H:%M:%SZ > "$stamp"
echo "Готово: target/classes ($(env -u JAVA_TOOL_OPTIONS "$JAVA_HOME/bin/java" -version 2>&1 | head -n1))" >&2
