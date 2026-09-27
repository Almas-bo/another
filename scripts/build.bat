@echo off
rem Сборка сервера песочницы без Maven: нужен только JDK 21+.
chcp 65001 >nul
cd /d "%~dp0\.."
where javac >nul 2>nul || (echo Не найден javac. Установите JDK 21, не JRE. & exit /b 1)
if exist target\classes rmdir /s /q target\classes
mkdir target\classes
dir /s /b src\main\java\*.java > target\sources.txt
javac -encoding UTF-8 --release 21 -parameters -d target\classes @target\sources.txt || exit /b 1
echo Готово: target\classes
