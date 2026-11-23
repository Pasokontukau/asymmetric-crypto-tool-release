param(
    [string]$Zip = ""
)

# Read-only portable acceptance (P-31 / P-32):
#   Case A - program directory is read-only (Deny Write ACL on the extracted folder).
#            Expect: app starts, data falls back to %APPDATA%\CryptoAssistant\data,
#            settings persist there, DPAPI works, nothing is written beside the exe.
#   Case B - TEMP/TMP point at a read-only folder.
#            Expect: app starts, DPAPI works, native libraries extract to
#            %LOCALAPPDATA%\CryptoAssistant\temp instead of the unusable TEMP.
#
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File verify-portable-readonly.ps1
#      powershell -NoProfile -ExecutionPolicy Bypass -File verify-portable-readonly.ps1 -Zip <path>

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not $Zip) {
    $distRoot = Join-Path (Split-Path -Parent $root) "dist"
    # Highest released version ZIP under dist/; old rebuild-* folders are ignored.
    $candidates = @(Get-ChildItem -LiteralPath $distRoot -Recurse -Filter "CryptoAssistant-*-win64.zip" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^CryptoAssistant-\d+\.\d+\.\d+-win64\.zip$' } |
        Sort-Object { [version]($_.Name -replace '^CryptoAssistant-', '' -replace '-win64\.zip$', '') } -Descending)
    if ($candidates.Count -eq 0) {
        $candidates = @(Get-ChildItem -LiteralPath (Join-Path $root "target\portable") -Filter "CryptoAssistant-*-win64.zip" -ErrorAction SilentlyContinue)
    }
    if ($candidates.Count -eq 0) { throw "No portable ZIP found; pass -Zip <path>." }
    $Zip = $candidates[0].FullName
}
if (-not (Test-Path -LiteralPath $Zip)) { throw "ZIP not found: $Zip" }
Write-Host "ZIP: $Zip"

$jdk = Join-Path $env:USERPROFILE ".jdks\corretto-17"
$javac = Join-Path $jdk "bin\javac.exe"
$jarTool = Join-Path $jdk "bin\jar.exe"
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $jarTool)) { throw "JDK 17 not found at $jdk" }

$runId = [guid]::NewGuid().ToString("N")
$work = Join-Path $env:TEMP ("aegix-readonly-" + $runId)
$agentDir = Join-Path $work "agent"
$classes = Join-Path $agentDir "classes"
$cwd = Join-Path $agentDir "cwd"
$agentJar = Join-Path $agentDir "portable-smoke-agent.jar"
$manifest = Join-Path $agentDir "MANIFEST.MF"
New-Item -ItemType Directory -Path $classes -Force | Out-Null
New-Item -ItemType Directory -Path $cwd -Force | Out-Null

$appDataDir = Join-Path $env:APPDATA "CryptoAssistant"
$nativeTemp = Join-Path $env:LOCALAPPDATA "CryptoAssistant\temp"
$user = "$env:USERDOMAIN\$env:USERNAME"
$failures = @()

function Expand-Package([string]$target) {
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $target -Force
    $app = Join-Path $target "CryptoAssistant"
    if (-not (Test-Path -LiteralPath (Join-Path $app "CryptoAssistant.exe"))) {
        throw "Extracted package incomplete: $app"
    }
    return $app
}

function Invoke-Agent([string]$exe, [string]$resultFile, [int]$timeoutSeconds) {
    $stdout = Join-Path $agentDir ("app-stdout-" + [guid]::NewGuid().ToString("N") + ".log")
    $stderr = Join-Path $agentDir ("app-stderr-" + [guid]::NewGuid().ToString("N") + ".log")
    $old = $env:JAVA_TOOL_OPTIONS
    $process = $null
    try {
        $env:JAVA_TOOL_OPTIONS = "-javaagent:$agentJar=$resultFile"
        $process = Start-Process -FilePath $exe -WorkingDirectory $cwd -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $deadline = (Get-Date).AddSeconds($timeoutSeconds)
        while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $resultFile)) {
            Start-Sleep -Milliseconds 250
            if ($process.HasExited -and -not (Test-Path -LiteralPath $resultFile)) { break }
        }
        if (-not (Test-Path -LiteralPath $resultFile)) {
            $exit = if ($process.HasExited) { $process.ExitCode } else { "running" }
            $err = if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Raw } else { "" }
            throw "Agent produced no result (exit: $exit). stderr:`n$err"
        }
        return Get-Content -LiteralPath $resultFile -Raw
    }
    finally {
        if ($process -ne $null -and -not $process.HasExited) {
            $null = $process.WaitForExit(5000)
            if (-not $process.HasExited) { $process.Kill() }
        }
        $env:JAVA_TOOL_OPTIONS = $old
    }
}

function Parse-Report([string]$report) {
    $values = @{}
    foreach ($line in ($report -split "`r?`n")) {
        $index = $line.IndexOf("=")
        if ($index -gt 0) { $values[$line.Substring(0, $index)] = $line.Substring($index + 1) }
    }
    return $values
}

try {
    $probe = Expand-Package (Join-Path $work "probe")
    $probeJar = Join-Path $probe "app\cryptoassistant.jar"
    & $javac -encoding UTF-8 -cp $probeJar -d $classes (Join-Path $root "tools\e2e\PortableSmokeAgent.java")
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }
    Set-Content -LiteralPath $manifest -Encoding ASCII -Value @("Manifest-Version: 1.0", "Premain-Class: PortableSmokeAgent", "")
    & $jarTool --create --file $agentJar --manifest $manifest -C $classes . | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "jar failed" }

    # ---------------- Case A: read-only program directory ----------------
    Write-Host "=== Case A: read-only program directory ==="
    $appA = Expand-Package (Join-Path $work "readonly-app")
    $resultA = Join-Path $agentDir "result-a.txt"
    # Deny only create/modify (AD,WD,WA,WEA). A blanket (W) deny also blocks
    # process startup: Start-Process fails with access denied (measured).
    & icacls $appA /deny "${user}:(OI)(CI)(AD,WA,WEA,WD)" | Out-Null
    try {
        $reportA = Invoke-Agent (Join-Path $appA "CryptoAssistant.exe") $resultA 60
    }
    finally {
        & icacls $appA /remove:d $user | Out-Null
    }
    Write-Host $reportA
    $a = Parse-Report $reportA
    if ($a["ERROR"]) { $failures += "A: agent error $($a['ERROR'])" }
    if ($a["WINDOW"] -ne "true") { $failures += "A: main window not shown" }
    if ($a["DPAPI"] -ne "true") { $failures += "A: DPAPI unavailable" }
    $expectedData = [IO.Path]::GetFullPath((Join-Path $appDataDir "data")).TrimEnd("\")
    if ($a["DATA"] -and ([IO.Path]::GetFullPath($a["DATA"]).TrimEnd("\") -ne $expectedData)) {
        $failures += "A: data dir should fall back to $expectedData, got $($a['DATA'])"
    }
    if ($a["FONT_SAVED"] -ne "true") { $failures += "A: settings not persisted to the fallback data dir" }
    if (Test-Path -LiteralPath (Join-Path $appA "data")) { $failures += "A: data/ was created beside the read-only exe" }

    # ---------------- Case B: read-only TEMP ----------------
    Write-Host "=== Case B: TEMP/TMP point at a read-only folder ==="
    $appB = Expand-Package (Join-Path $work "readonly-temp\pkg")
    $tempDir = Join-Path $work "readonly-temp\temp"
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    $resultB = Join-Path $agentDir "result-b.txt"
    & icacls $tempDir /deny "${user}:(OI)(CI)(AD,WA,WEA,WD)" | Out-Null
    $oldTemp = $env:TEMP
    $oldTmp = $env:TMP
    try {
        $env:TEMP = $tempDir
        $env:TMP = $tempDir
        $reportB = Invoke-Agent (Join-Path $appB "CryptoAssistant.exe") $resultB 60
    }
    finally {
        $env:TEMP = $oldTemp
        $env:TMP = $oldTmp
        & icacls $tempDir /remove:d $user | Out-Null
    }
    Write-Host $reportB
    $b = Parse-Report $reportB
    if ($b["ERROR"]) { $failures += "B: agent error $($b['ERROR'])" }
    if ($b["WINDOW"] -ne "true") { $failures += "B: main window not shown" }
    if ($b["DPAPI"] -ne "true") { $failures += "B: DPAPI unavailable with read-only TEMP" }
    if (-not (Test-Path -LiteralPath $nativeTemp)) {
        $failures += "B: native library fallback dir missing: $nativeTemp"
    }
    else {
        $dlls = @(Get-ChildItem -LiteralPath $nativeTemp -Recurse -Filter *.dll -ErrorAction SilentlyContinue)
        if ($dlls.Count -eq 0) { $failures += "B: no native libraries extracted under $nativeTemp" }
        else { Write-Host "Native libs under fallback temp: $($dlls.Count)" }
    }
}
finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    Write-Host ""
    foreach ($item in $failures) { Write-Host "FAIL $item" }
    throw "PORTABLE READ-ONLY CHECKS FAILED ($($failures.Count))"
}
Write-Host ""
Write-Host "PORTABLE READ-ONLY PASSED"
