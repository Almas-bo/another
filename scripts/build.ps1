<#
  Сборка сервера песочницы без Maven (Windows PowerShell 5.1 / PowerShell 7).
  JDK находится или, с согласия, скачивается (ensure-jdk.ps1). Инкрементально: пересборка, только если
  исходники новее метки target\classes\.build-stamp (её же проверяет Unity-клиент). -Force — собрать заново.
  Выводит путь к JDK в stdout (для run-server).
#>
param([switch]$Force)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root

$jdk = & (Join-Path $PSScriptRoot 'ensure-jdk.ps1')
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$jdk = @($jdk)[-1]
$Exe = ''
if ($env:OS -eq 'Windows_NT') { $Exe = '.exe' }
$classes = Join-Path $Root 'target/classes'
$stamp = Join-Path $classes '.build-stamp'
$sources = Get-ChildItem -LiteralPath (Join-Path $Root 'src/main/java') -Recurse -Filter *.java | Sort-Object FullName

$stale = $Force -or -not (Test-Path -LiteralPath $stamp)
if (-not $stale) {
    $built = [IO.File]::GetLastWriteTimeUtc($stamp)
    $stale = [bool]($sources | Where-Object { $_.LastWriteTimeUtc -gt $built } | Select-Object -First 1)
}
if (-not $stale) {
    [Console]::Error.WriteLine('Сервер собран и актуален: target/classes')
    Write-Output $jdk
    exit 0
}

if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
# Пути — в кавычках и с прямыми слэшами: javac на Windows понимает их, а пробелы в пути не ломают сборку.
$argfile = Join-Path $Root 'target/sources.txt'
$lines = $sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($argfile, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
$javac = Join-Path (Join-Path $jdk 'bin') ('javac' + $Exe)
$saved = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = $null
& $javac -encoding UTF-8 --release 21 -parameters -d $classes.Replace('\', '/') ('@' + $argfile.Replace('\', '/'))
$code = $LASTEXITCODE
$env:JAVA_TOOL_OPTIONS = $saved
if ($code -ne 0) { [Console]::Error.WriteLine("javac завершился с кодом $code"); exit $code }
[IO.File]::WriteAllText($stamp, [DateTime]::UtcNow.ToString('o'))
[Console]::Error.WriteLine("Готово: target/classes ($($sources.Count) файлов)")
Write-Output $jdk
