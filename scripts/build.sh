#!/usr/bin/env bash
# Сборка сервера песочницы без Maven: нужен только JDK 21+.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v javac >/dev/null || { echo "Не найден javac. Установите JDK 21 (не JRE)."; exit 1; }
rm -rf target/classes
mkdir -p target/classes
find src/main/java -name "*.java" > target/sources.txt
javac -encoding UTF-8 --release 21 -parameters -d target/classes @target/sources.txt
echo "Готово: target/classes"
