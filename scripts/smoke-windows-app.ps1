param(
    [string]$AppImagePath = (Join-Path $PSScriptRoot '../dist/windows/ECL'),
    [string]$EvidenceDirectory = (Join-Path $PSScriptRoot '../build/release-validation')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT') { throw 'Native EXE startup validation requires Windows.' }
$image = (Resolve-Path -LiteralPath $AppImagePath).Path
& (Join-Path $PSScriptRoot 'verify-windows-app.ps1') -AppImagePath $image -EvidenceDirectory $EvidenceDirectory
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
$isolated = Join-Path $evidence ('exe-smoke-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $isolated -Force | Out-Null
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = Join-Path $image 'ECL.exe'
$start.WorkingDirectory = $image
$start.UseShellExecute = $false
$start.Environment['APPDATA'] = $isolated
$start.Environment.Remove('JAVA_HOME') | Out-Null
$process = $null
$result = [ordered]@{ result = 'FAIL'; scope = 'native-exe-window-and-bundled-jvm-only' }
try {
    $process = [System.Diagnostics.Process]::Start($start)
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $process.Refresh()
        if ($process.HasExited) { throw "ECL.exe exited before showing a window (code $($process.ExitCode))." }
        if ($process.MainWindowHandle -ne [IntPtr]::Zero) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($process.MainWindowHandle -eq [IntPtr]::Zero) { throw 'ECL.exe did not show a window within 30 seconds.' }
    $expectedJvm = [System.IO.Path]::GetFullPath((Join-Path $image 'runtime/bin/server/jvm.dll'))
    $loadedJvm = @($process.Modules | Where-Object { $_.ModuleName -ieq 'jvm.dll' })
    if ($loadedJvm.Count -ne 1 -or $loadedJvm[0].FileName -ine $expectedJvm) {
        throw 'EXE did not load the candidate bundled JVM.'
    }
    $result['result'] = 'PASS'
    $result['bundledJvm'] = 'runtime/bin/server/jvm.dll'
    $result['exeSha256'] = (Get-FileHash -LiteralPath $start.FileName -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output 'WINDOWS_NATIVE_EXE_STARTUP_PASS'
} finally {
    if ($process) {
        if (-not $process.HasExited) {
            $process.CloseMainWindow() | Out-Null
            if (-not $process.WaitForExit(5000)) {
                $process.Kill()
                $process.WaitForExit()
            }
        }
        $process.Dispose()
    }
    $result['completedAt'] = [DateTime]::UtcNow.ToString('o')
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'exe-startup-result.json') -Encoding utf8
}
