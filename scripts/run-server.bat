@echo off
rem Запуск игрового сервера (Unity-клиент подключается к http://127.0.0.1:8787).
chcp 65001 >nul
cd /d "%~dp0\.."
if not exist target\classes\city call "%~dp0build.bat" || exit /b 1
java -cp target\classes city.subroutine.server.GameServer %*
