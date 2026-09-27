<#
  Запуск игрового сервера (Unity-клиент подключается к http://127.0.0.1:8787).
  Сам находит/устанавливает JDK и пересобирает сервер, если исходники изменились.
#>
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$jdk = & (Join-Path $PSScriptRoot 'build.ps1')
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$jdk = @($jdk)[-1]
$Exe = ''
if ($env:OS -eq 'Windows_NT') { $Exe = '.exe' }
$env:JAVA_TOOL_OPTIONS = $null
& (Join-Path (Join-Path $jdk 'bin') ('java' + $Exe)) -cp (Join-Path $Root 'target/classes') city.subroutine.server.GameServer @args
exit $LASTEXITCODE
