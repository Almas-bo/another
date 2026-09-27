<#
  Находит JDK 21+ (именно JDK, с javac) или, с согласия, скачивает Eclipse Temurin 21 в .jdk\ репозитория.
  Печатает путь к JDK (JAVA_HOME) в stdout. Совместим с Windows PowerShell 5.1 и PowerShell 7.
  Каталог .jdk\ общий с Unity-клиентом.

  Переменные окружения:
    SUBROUTINE_AUTO_JDK=1          скачивать без вопроса
    SUBROUTINE_JDK_URL=...         свой адрес архива; рядом должен лежать "<адрес>.sha256.txt"
    SUBROUTINE_JDK_MANAGED_ONLY=1  искать только в .jdk\ (для тестов установки)
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Required = 21
$Root = Split-Path -Parent $PSScriptRoot
$Managed = Join-Path $Root '.jdk'
$OnWindows = ($env:OS -eq 'Windows_NT')
$Exe = ''
if ($OnWindows) { $Exe = '.exe' }

function Write-Info([string]$Text) { [Console]::Error.WriteLine($Text) }

function Get-JavaMajor([string]$Java) {
    # cmd/sh вместо прямого вызова: в PowerShell 5.1 вывод java в stderr превращается в ошибки
    $saved = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = $null
    try {
        if ($OnWindows) { $out = cmd /c "`"$Java`" -version 2>&1" | Out-String }
        else { $out = sh -c "'$Java' -version 2>&1" | Out-String }
    } catch { return -1 }
    finally { $env:JAVA_TOOL_OPTIONS = $saved }
    if ($out -match 'version "(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
        return $major
    }
    return -1
}

function Test-Jdk([string]$JdkHome) {
    if (-not $JdkHome) { return $false }
    $java = Join-Path (Join-Path $JdkHome 'bin') ('java' + $Exe)
    $javac = Join-Path (Join-Path $JdkHome 'bin') ('javac' + $Exe)
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or -not (Test-Path -LiteralPath $javac -PathType Leaf)) { return $false }
    return (Get-JavaMajor $java) -ge $Required
}

function Get-JdkHomes([string]$Dir, [int]$Depth) {
    $result = @()
    if (-not (Test-Path -LiteralPath $Dir -PathType Container)) { return $result }
    if (Test-Path -LiteralPath (Join-Path (Join-Path $Dir 'bin') ('java' + $Exe)) -PathType Leaf) { return @($Dir) }
    if ($Depth -le 0) { return $result }
    foreach ($child in (Get-ChildItem -LiteralPath $Dir -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
        if ($child.Name -like '.partial*') { continue }
        $result += Get-JdkHomes $child.FullName ($Depth - 1)
    }
    return $result
}

$candidates = @()
$candidates += Get-JdkHomes $Managed 4
if ($env:SUBROUTINE_JDK_MANAGED_ONLY -ne '1') {
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $onPath = Get-Command ('java' + $Exe) -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $candidates += (Split-Path -Parent (Split-Path -Parent $onPath.Source)) }
    if ($OnWindows) {
        $pf = $env:ProgramFiles
        foreach ($vendor in 'Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft') {
            $candidates += Get-JdkHomes (Join-Path $pf $vendor) 2
        }
    }
}
foreach ($candidate in $candidates) {
    if (Test-Jdk $candidate) { Write-Output $candidate; exit 0 }
}

# ------------------------------------------------------------------ установка Temurin 21
$arch = 'x64'
if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { $arch = 'aarch64' }
$url = $env:SUBROUTINE_JDK_URL
if (-not $url) { $url = "https://api.adoptium.net/v3/binary/latest/$Required/ga/windows/$arch/jdk/hotspot/normal/eclipse" }

Write-Info "Не найден JDK $Required+ (с javac)."
if ($env:SUBROUTINE_AUTO_JDK -ne '1') {
    if (-not [Environment]::UserInteractive -or [Console]::IsInputRedirected) {
        Write-Info "Задайте SUBROUTINE_AUTO_JDK=1 для автоматической установки или установите JDK $Required."
        exit 1
    }
    $answer = Read-Host "Скачать Eclipse Temurin $Required (~200 МБ, проверка SHA-256) в $Managed? [Y/n]"
    if ($answer -and $answer -notmatch '^[yYдД]') { Write-Info 'Отменено.'; exit 1 }
}

[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Force -Path $Managed | Out-Null
$archive = Join-Path $Managed '.download.zip'
$partial = Join-Path $Managed ('.partial-' + [Guid]::NewGuid().ToString('N'))
try {
    Write-Info "Скачивание: $url"
    $request = [Net.HttpWebRequest]::Create($url)
    $response = $request.GetResponse()
    $final = $response.ResponseUri.GetLeftPart([UriPartial]::Path)
    $stream = $response.GetResponseStream()
    $file = [IO.File]::Create($archive)
    try { $stream.CopyTo($file) } finally { $file.Dispose(); $stream.Dispose(); $response.Dispose() }

    try { $sumText = (New-Object Net.WebClient).DownloadString("$final.sha256.txt") }
    catch { Write-Info "Нет контрольной суммы ($final.sha256.txt). Установка без проверки запрещена."; exit 1 }
    $expected = ($sumText.Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
    if ($expected.Length -ne 64 -or $expected -ne $actual) {
        Write-Info "Контрольная сумма не совпала: ожидалась $expected, получена $actual."
        exit 1
    }
    Write-Info 'SHA-256 совпадает.'

    Expand-Archive -LiteralPath $archive -DestinationPath $partial -Force
    $top = Get-ChildItem -LiteralPath $partial -Directory | Select-Object -First 1
    if (-not $top) { Write-Info 'Пустой архив JDK.'; exit 1 }
    $target = Join-Path $Managed $top.Name
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    Move-Item -LiteralPath $top.FullName -Destination $target
    $jdkHome = Get-JdkHomes $target 3 | Select-Object -First 1
    if (-not $OnWindows -and $jdkHome) { chmod +x (Join-Path (Join-Path $jdkHome 'bin') '*') }
    if (-not (Test-Jdk $jdkHome)) { Write-Info "Установленный JDK не запускается: $jdkHome"; exit 1 }
    Write-Info "JDK установлен: $jdkHome"
    Write-Output $jdkHome
}
finally {
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Recurse -Force }
}
