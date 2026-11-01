param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AppImagePath,
    [string]$CandidateRevision = 'unknown',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repository = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JavaHome) { throw 'Set JAVA_HOME to a JDK 21 installation, or pass -JavaHome.' }
$java = Join-Path $JavaHome 'bin/java.exe'
$compiler = Join-Path $JavaHome 'bin/javac.exe'
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $compiler)) {
    throw 'A complete JDK is required (java.exe and javac.exe).'
}

Push-Location $repository
$previousAppData = $env:APPDATA
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    if ($AppImagePath) {
        $image = (Resolve-Path -LiteralPath $AppImagePath).Path
        & (Join-Path $PSScriptRoot 'verify-windows-app.ps1') -AppImagePath $image
        if (-not $?) { throw 'Candidate image validation failed.' }
        $libraries = Join-Path $image 'app'
    } elseif (-not $SkipBuild) {
        & (Join-Path $repository 'gradlew.bat') installDist
        if ($LASTEXITCODE -ne 0) { throw 'installDist failed.' }
    }
    if (-not $AppImagePath) { $libraries = Join-Path $repository 'ecl-boot/build/install/ECL/lib' }
    if (-not (Test-Path -LiteralPath $libraries)) { throw 'Run installDist before using -SkipBuild.' }
    $validationRoot = Join-Path $repository ('build/runtime-validation/' + [guid]::NewGuid().ToString())
    $classes = Join-Path $validationRoot 'classes'
    $isolatedAppData = Join-Path $validationRoot 'appdata'
    New-Item -ItemType Directory -Path $classes, $isolatedAppData -Force | Out-Null
    $candidateHashes = Get-ChildItem -LiteralPath $libraries -Filter '*.jar' | Sort-Object Name | ForEach-Object {
        '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
    }
    $candidateHashes | Set-Content -LiteralPath (Join-Path $validationRoot 'application-jars.sha256') -Encoding utf8
    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine the validation source revision.' }
    $dirty = [bool](& git status --porcelain)
    $source = Join-Path $repository 'ecl-gui/src/test/java/com/ecl/ui/LauncherRuntimeValidation.java'
    & $compiler --release 21 -encoding UTF-8 -cp (Join-Path $libraries '*') -d $classes $source
    if ($LASTEXITCODE -ne 0) { throw 'Runtime validation harness compilation failed.' }
    $env:APPDATA = $isolatedAppData
    Write-Output ('RUNTIME_VALIDATION_DIRECTORY ' + $validationRoot)
    & $java '-Dorg.slf4j.simpleLogger.defaultLogLevel=off' "-Duser.home=$isolatedAppData" `
        "-Decl.validation.harnessRevision=$revision" "-Decl.validation.harnessDirty=$dirty" "-Decl.validation.candidateRevision=$CandidateRevision" `
        -cp ($classes + ';' + (Join-Path $libraries '*')) com.ecl.ui.LauncherRuntimeValidation $validationRoot
    $validationExitCode = $LASTEXITCODE
    Write-Output ('RUNTIME_VALIDATION_EVIDENCE ' + (Join-Path $validationRoot 'result.properties'))
    Write-Output 'Do not upload appdata or raw logs: they contain real account credentials and world data.'
    if ($validationExitCode -ne 0) { throw 'Live validation did not pass; see the secret-free result.properties.' }
} finally {
    $env:APPDATA = $previousAppData
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
