$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$validator = Join-Path $PSScriptRoot 'verify-windows-app.ps1'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ('ecl-image-validator-' + [guid]::NewGuid().ToString())
$image = Join-Path $root 'ECL'
$evidence = Join-Path $root 'evidence'
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../build.gradle.kts') -Raw
if ($source -notmatch 'version\s*=\s*"([^"]+)"') { throw 'Cannot determine source version.' }
$version = $Matches[1]

function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "Validator accepted $Message" }
}

try {
    # Deliberately synthetic files: test the structure checker, never execute this fixture.
    foreach ($relative in @('ECL.exe', 'runtime/release', 'runtime/bin/server/jvm.dll')) {
        $file = Join-Path $image $relative
        New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($file)) -Force | Out-Null
        Set-Content -LiteralPath $file -Value 'synthetic structure fixture'
    }
    $classes = @{
        'ecl-boot' = 'com/ecl/ECL.class'
        'ecl-core' = 'com/ecl/ECLConfig.class'
        'ecl-gui' = 'com/ecl/ui/MainController.class'
    }
    $configuration = @('[Application]', 'app.mainclass=com.ecl.ECL')
    foreach ($module in $classes.Keys) {
        $staging = Join-Path $root $module
        $entry = Join-Path $staging $classes[$module]
        New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($entry)) -Force | Out-Null
        Set-Content -LiteralPath $entry -Value 'synthetic class entry'
        New-Item -ItemType Directory -Path (Join-Path $image 'app') -Force | Out-Null
        [System.IO.Compression.ZipFile]::CreateFromDirectory($staging, (Join-Path $image "app/$module-$version.jar"))
        $configuration += 'app.classpath=$APPDIR/' + "$module-$version.jar"
    }
    $configuration | Set-Content -LiteralPath (Join-Path $image 'app/ECL.cfg')
    $zip = Join-Path $root 'ECL.zip'
    & $validator -AppImagePath $image -EvidenceDirectory $evidence -ZipPath $zip | Out-Null
    $result = Get-Content -LiteralPath (Join-Path $evidence 'image-result.json') -Raw | ConvertFrom-Json
    if ($result.result -ne 'PASS' -or -not $result.zipSha256) { throw 'Missing structure/hash evidence.' }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($required in @('ECL/ECL.exe', "ECL/app/ecl-core-$version.jar", 'ECL/runtime/bin/server/jvm.dll')) {
            if (-not $archive.GetEntry($required)) { throw "Release ZIP lost $required" }
        }
    } finally { $archive.Dispose() }
    Assert-Rejected { & $validator -AppImagePath $image -EvidenceDirectory $image } 'evidence inside image'
    Assert-Rejected { & $validator -AppImagePath $image -EvidenceDirectory $evidence -ZipPath $zip } 'ZIP overwrite'
    $jvm = Join-Path $image 'runtime/bin/server/jvm.dll'
    Remove-Item -LiteralPath $jvm
    Assert-Rejected { & $validator -AppImagePath $image -EvidenceDirectory $evidence } 'missing JVM'
    Set-Content -LiteralPath $jvm -Value 'synthetic structure fixture'
    $core = Join-Path $image "app/ecl-core-$version.jar"
    Copy-Item -LiteralPath $core -Destination (Join-Path $image 'app/ecl-core-duplicate.jar')
    Assert-Rejected { & $validator -AppImagePath $image -EvidenceDirectory $evidence } 'duplicate module'
    Remove-Item -LiteralPath (Join-Path $image 'app/ecl-core-duplicate.jar')
    $archive = [System.IO.Compression.ZipFile]::Open($core, [System.IO.Compression.ZipArchiveMode]::Update)
    try { $archive.CreateEntry('com/ecl/AccidentalTest$Nested.class') | Out-Null } finally { $archive.Dispose() }
    Assert-Rejected { & $validator -AppImagePath $image -EvidenceDirectory $evidence } 'nested test class'
    Write-Output 'WINDOWS_IMAGE_VALIDATOR_TESTS_PASS (synthetic fixtures; no Windows execution)'
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
