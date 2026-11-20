$ErrorActionPreference = "Stop"

# Portable package smoke test:
# - extract the current ZIP to a temp directory (outside the repo: the workspace is
#   Low integrity, and a Low-IL process cannot write %TEMP%, which would fake a
#   DPAPI failure that normal users never see)
# - launch the real CryptoAssistant.exe with a Java agent
# - wait for the real MainFrame, save font_size, report app.home/data dir
# - verify data/settings.properties is next to the exe, not in the launch CWD
#
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File verify-portable-smoke.ps1

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# Version-agnostic: pick the ZIP that package-portable.bat just produced.
$portableDir = Join-Path $root "target\portable"
$zip = @(Get-ChildItem -LiteralPath $portableDir -Filter "CryptoAssistant-*-win64.zip" -ErrorAction SilentlyContinue)
if ($zip.Count -ne 1) {
    throw "Expected exactly one CryptoAssistant-*-win64.zip under $portableDir, found $($zip.Count); run package-portable.bat first."
}
$zip = $zip[0].FullName

$jdk = Join-Path $env:USERPROFILE ".jdks\corretto-17"
$javac = Join-Path $jdk "bin\javac.exe"
$jarTool = Join-Path $jdk "bin\jar.exe"
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $jarTool)) {
    throw "JDK 17 not found at $jdk"
}

$runId = [guid]::NewGuid().ToString("N")
$work = Join-Path $env:TEMP ("aegix-portable-smoke-" + $runId)
New-Item -ItemType Directory -Path $work | Out-Null
Expand-Archive -LiteralPath $zip -DestinationPath $work -Force

$app = Join-Path $work "CryptoAssistant"
$exe = Join-Path $app "CryptoAssistant.exe"
$appJar = Join-Path $app "app\cryptoassistant.jar"
if (-not (Test-Path -LiteralPath $exe) -or -not (Test-Path -LiteralPath $appJar)) {
    throw "Extracted portable package is incomplete: $app"
}

$agentDir = Join-Path $env:TEMP ("aegix-portable-agent-" + $runId)
$classes = Join-Path $agentDir "classes"
$cwd = Join-Path $agentDir "cwd"
$result = Join-Path $agentDir "result.txt"
$agentJar = Join-Path $agentDir "portable-smoke-agent.jar"
$manifest = Join-Path $agentDir "MANIFEST.MF"
$stdout = Join-Path $agentDir "app-stdout.log"
$stderr = Join-Path $agentDir "app-stderr.log"
New-Item -ItemType Directory -Path $classes | Out-Null
New-Item -ItemType Directory -Path $cwd | Out-Null

$process = $null
$passed = $false
$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    & $javac -encoding UTF-8 -cp $appJar -d $classes (Join-Path $root "tools\e2e\PortableSmokeAgent.java")
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed"
    }

    Set-Content -LiteralPath $manifest -Encoding ASCII -Value @(
        "Manifest-Version: 1.0",
        "Premain-Class: PortableSmokeAgent",
        ""
    )
    & $jarTool --create --file $agentJar --manifest $manifest -C $classes .
    if ($LASTEXITCODE -ne 0) {
        throw "jar failed"
    }

    $env:JAVA_TOOL_OPTIONS = "-javaagent:$agentJar=$result"
    # Redirect the child's pipes: a GUI child inherits the caller's stdout, and the
    # terminal tool would otherwise wait for EOF long after the app exits.
    $process = Start-Process -FilePath $exe -WorkingDirectory $cwd -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $result)) {
        Start-Sleep -Milliseconds 200
        if ($process.HasExited -and -not (Test-Path -LiteralPath $result)) {
            break
        }
    }

    if (-not (Test-Path -LiteralPath $result)) {
        $exit = if ($process.HasExited) { $process.ExitCode } else { "running" }
        $errText = if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Raw } else { "" }
        throw "Smoke agent did not write a result (exit code: $exit)`nstderr:`n$errText"
    }

    $report = Get-Content -LiteralPath $result -Raw
    Write-Host $report

    $values = @{}
    foreach ($line in ($report -split "`r?`n")) {
        $index = $line.IndexOf("=")
        if ($index -gt 0) {
            $values[$line.Substring(0, $index)] = $line.Substring($index + 1)
        }
    }

    if ($values["ERROR"]) {
        throw "Smoke agent error: $($values['ERROR'])"
    }

    $expectedHome = [IO.Path]::GetFullPath($app).TrimEnd("\")
    $expectedData = [IO.Path]::GetFullPath((Join-Path $app "data")).TrimEnd("\")
    $actualHome = [IO.Path]::GetFullPath($values["HOME"]).TrimEnd("\")
    $actualData = [IO.Path]::GetFullPath($values["DATA"]).TrimEnd("\")

    if ($values["WINDOW"] -ne "true") {
        throw "MainFrame was not reported as showing"
    }
    if ($actualHome -ne $expectedHome) {
        throw "app.home mismatch: expected $expectedHome, got $actualHome"
    }
    if ($actualData -ne $expectedData) {
        throw "data dir mismatch: expected $expectedData, got $actualData"
    }
    if ($values["FONT_SAVED"] -ne "true") {
        throw "font_size=23 was not saved"
    }
    if ($values["DPAPI"] -ne "true") {
        throw "DPAPI is not available in the portable runtime"
    }
    if (Test-Path -LiteralPath (Join-Path $cwd "data")) {
        throw "Data was created in the launch CWD instead of beside the exe"
    }

    $settingsFile = Join-Path $app "data\settings.properties"
    if (-not (Test-Path -LiteralPath $settingsFile)) {
        throw "Settings file not found: $settingsFile"
    }
    if (-not (Select-String -LiteralPath $settingsFile -Pattern "^font_size=23$" -Quiet)) {
        throw "font_size=23 not found in $settingsFile"
    }

    Write-Host "PORTABLE SMOKE PASSED"
    $passed = $true
}
finally {
    if ($process -ne $null -and -not $process.HasExited) {
        $null = $process.WaitForExit(5000)
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $null = $process.WaitForExit(5000)
        }
    }
    $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
    if ($passed) {
        foreach ($dir in @($work, $agentDir)) {
            for ($attempt = 0; $attempt -lt 10; $attempt++) {
                try {
                    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction Stop
                    break
                } catch {
                    Start-Sleep -Milliseconds 300
                }
            }
            if (Test-Path -LiteralPath $dir) {
                Write-Warning "Could not remove $dir (a file may still be locked)"
            }
        }
    } else {
        Write-Host "Artifacts kept for diagnosis:"
        Write-Host "  app:   $app"
        Write-Host "  agent: $agentDir"
        if (Test-Path -LiteralPath $stdout) { Write-Host "--- app stdout last 20 ---"; Get-Content -LiteralPath $stdout -Tail 20 }
        if (Test-Path -LiteralPath $stderr) { Write-Host "--- app stderr last 20 ---"; Get-Content -LiteralPath $stderr -Tail 20 }
        $logs = Join-Path $app "data\logs"
        if (Test-Path -LiteralPath $logs) {
            Get-ChildItem -LiteralPath $logs -Filter "*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1 | ForEach-Object {
                Write-Host "--- app log: $($_.FullName) ---"
                Get-Content -LiteralPath $_.FullName -Tail 40
            }
        }
    }
}
