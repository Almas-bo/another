@echo off
rem Запуск игрового сервера (Unity-клиент подключается к http://127.0.0.1:8787).
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-server.ps1" %*
exit /b %ERRORLEVEL%
