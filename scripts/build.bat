@echo off
rem Сборка сервера песочницы без Maven. JDK находится или скачивается автоматически (см. ensure-jdk.ps1).
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %* >nul
exit /b %ERRORLEVEL%
