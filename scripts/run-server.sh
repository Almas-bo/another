#!/usr/bin/env bash
# Запуск игрового сервера (Unity-клиент подключается к http://127.0.0.1:8787).
# Сам находит/устанавливает JDK и пересобирает сервер, если исходники изменились.
set -euo pipefail
cd "$(dirname "$0")/.."
"$(dirname "$0")/build.sh"
JAVA_HOME="$("$(dirname "$0")/ensure-jdk.sh")"
exec env -u JAVA_TOOL_OPTIONS "$JAVA_HOME/bin/java" -cp target/classes city.subroutine.server.GameServer "$@"
