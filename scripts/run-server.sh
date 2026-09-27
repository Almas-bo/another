#!/usr/bin/env bash
# Запуск игрового сервера (Unity-клиент подключается к http://127.0.0.1:8787).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d target/classes/city ] || "$(dirname "$0")/build.sh"
exec java -cp target/classes city.subroutine.server.GameServer "$@"
