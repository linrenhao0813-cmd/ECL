param(
    [string]$AppImagePath = (Join-Path $PSScriptRoot '../dist/windows/ECL'),
    [string]$ExpectedVersion,
    [string]$EvidenceDirectory = (Join-Path $PSScriptRoot '../build/release-validation'),
    [string]$ZipPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not $ExpectedVersion) {
    $build = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../build.gradle.kts') -Raw
    if ($build -notmatch 'version\s*=\s*"([^"]+)"') { throw 'Cannot determine the source version.' }
    $ExpectedVersion = $Matches[1]
}
$configSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../ecl-core/src/main/java/com/ecl/ECLConfig.java') -Raw
if ($configSource -notmatch 'LAUNCHER_VERSION\s*=\s*"([^"]+)"' -or $Matches[1] -ne $ExpectedVersion) {
    throw 'Launcher display version and candidate version disagree.'
}
$image = (Resolve-Path -LiteralPath $AppImagePath).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
if ($evidence.StartsWith($image + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase) `
    -or $evidence -eq $image) { throw 'Evidence must be outside the application image.' }
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
foreach ($relative in @('ECL.exe', 'app/ECL.cfg', 'runtime/release', 'runtime/bin/server/jvm.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $image $relative) -PathType Leaf)) {
        throw "Incomplete Windows application image: missing $relative"
    }
}
$configuration = Get-Content -LiteralPath (Join-Path $image 'app/ECL.cfg')
if ($configuration -notcontains 'app.mainclass=com.ecl.ECL') { throw 'Unexpected application main class.' }
$classpath = @($configuration | Where-Object { $_.StartsWith('app.classpath=') })
if ($classpath.Count -eq 0) { throw 'Application classpath is missing.' }
foreach ($line in $classpath) {
    foreach ($entry in $line.Substring('app.classpath='.Length).Split(';')) {
        $resolved = $entry.Replace('$APPDIR', (Join-Path $image 'app'))
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "Missing classpath file: $entry" }
    }
}
$requiredClasses = @{
    'ecl-boot' = 'com/ecl/ECL.class'
    'ecl-core' = 'com/ecl/ECLConfig.class'
    'ecl-gui' = 'com/ecl/ui/MainController.class'
}
foreach ($module in @('ecl-boot', 'ecl-core', 'ecl-gui')) {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $image 'app') -Filter "$module-*.jar")
    if ($jars.Count -ne 1 -or $jars[0].Name -ne "$module-$ExpectedVersion.jar") {
        throw "Wrong or duplicate $module version; expected $ExpectedVersion"
    }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
    try {
        if (-not $archive.GetEntry($requiredClasses[$module])) { throw "Missing entry class in $module" }
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '^com/ecl/.*(?:Test|SmokeTest|RuntimeValidation|UiSnapshot)(?:\$[^/]*)?\.class$') {
                throw "Test class included in application: $($entry.FullName)"
            }
        }
    } finally { $archive.Dispose() }
}
if (@(Get-ChildItem -LiteralPath (Join-Path $image 'app') -Filter '*.jar' | Where-Object {
    $_.Name -match '^(junit|testfx|openjfx-monocle)'
}).Count -gt 0) { throw 'Test runtime dependencies are included in the application.' }
$files = @(Get-ChildItem -LiteralPath $image -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($image.Length + 1).Replace('\', '/')
    [ordered]@{ path = $relative; bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$result = [ordered]@{
    result = 'PASS'
    scope = 'application-image-structure-and-hashes-only'
    version = $ExpectedVersion
    completedAt = [DateTime]::UtcNow.ToString('o')
    files = $files
}
if ($ZipPath) {
    $zip = [System.IO.Path]::GetFullPath($ZipPath)
    if ($zip.StartsWith($image + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'ZIP must be outside the application image.'
    }
    if (Test-Path -LiteralPath $zip) { throw 'Refusing to overwrite an existing release ZIP.' }
    New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($zip)) -Force | Out-Null
    Compress-Archive -LiteralPath $image -DestinationPath $zip
    $result['zipSha256'] = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
}
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'image-result.json') -Encoding utf8
Write-Output ('WINDOWS_IMAGE_STRUCTURE_PASS version=' + $ExpectedVersion)
Write-Output ('IMAGE_EVIDENCE ' + (Join-Path $evidence 'image-result.json'))
Write-Output 'This check does not validate EXE startup, Microsoft login, Minecraft gameplay, or upgrade compatibility.'
