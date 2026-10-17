param(
    [string]$Version = '',
    [string]$Directory = '.',
    [string]$Repository = 'hetaoawa/AstraProject',
    [string]$PluginId = '',
    [string]$Name = '',
    [string]$Package = '',
    [string]$GroupId = '',
    [string]$PluginVersion = '',
    [string]$AssetsDirectory = ''
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install Java 21 JDK and add java/jar to PATH.' }
if (-not (Get-Command jar -ErrorAction SilentlyContinue)) { throw 'Java 21 JDK (including jar) is required.' }
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid GitHub repository.' }
if (-not $Version) {
    $Version = Read-Host 'Framework version (for example 0.1.0; Enter = latest stable release)'
    if (-not $Version) { $Version = 'latest' }
}
$Version = $Version -replace '^(framework-)?v', ''
if ($Version -ne 'latest' -and $Version -notmatch '^\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$') { throw 'Invalid version.' }
$target = [IO.Path]::GetFullPath($Directory)
$cache = if ($env:ASTRA_CACHE_ROOT) { $env:ASTRA_CACHE_ROOT } else { Join-Path $target '.astra/cache' }
$stage = Join-Path $cache ('bootstrap/' + [Guid]::NewGuid().ToString('N'))
# The target remains empty until the Java initializer has validated it.
if (-not $env:ASTRA_CACHE_ROOT) { $stage = Join-Path ([IO.Path]::GetTempPath()) ('astra-bootstrap-' + [Guid]::NewGuid().ToString('N')) }
[IO.Directory]::CreateDirectory($stage) | Out-Null
function Get-Asset([string]$AssetName, [string]$Destination, [string]$BaseUrl) {
    if ($AssetsDirectory) { Copy-Item -LiteralPath (Join-Path $AssetsDirectory $AssetName) -Destination $Destination }
    else { Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $AssetName) -OutFile $Destination }
}
$baseUrl = if ($Version -eq 'latest') { "https://github.com/$Repository/releases/latest/download/" } else { "https://github.com/$Repository/releases/download/framework-v$Version/" }
$manifestPath = Join-Path $stage 'astra-release.properties'
Get-Asset 'astra-release.properties' $manifestPath $baseUrl
$manifest = ConvertFrom-StringData ([IO.File]::ReadAllText($manifestPath))
if ($manifest['schema.version'] -ne '1') { throw 'Unsupported release manifest schema.' }
$resolved = $manifest['framework.version']
if ($resolved -notmatch '^\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$') { throw 'Invalid manifest version.' }
if ($Version -ne 'latest' -and $resolved -ne $Version) { throw 'Release version mismatch.' }
$baseUrl = "https://github.com/$Repository/releases/download/framework-v$resolved/"
$filename = $manifest['template.file']
if ($filename -notmatch '^[A-Za-z0-9._-]+\.zip$' -or $manifest['template.sha256'] -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid template manifest.' }
$archive = Join-Path $stage $filename
Get-Asset $filename $archive $baseUrl
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $manifest['template.sha256']) { throw 'Template SHA-256 mismatch.' }
$unpacked = Join-Path $stage 'template'
Expand-Archive -LiteralPath $archive -DestinationPath $unpacked
$arguments = @((Join-Path $unpacked 'tooling/AstraDev.java'), 'init', '--template', $unpacked, '--manifest', $manifestPath, '--directory', $target, '--repository', $Repository)
foreach ($pair in @(@('id', $PluginId), @('name', $Name), @('package', $Package), @('group', $GroupId), @('plugin-version', $PluginVersion), @('assets', $AssetsDirectory))) {
    if ($pair[1]) { $arguments += @(('--' + $pair[0]), $pair[1]) }
}
$ErrorActionPreference = 'Continue'
& java @arguments
$nativeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($nativeExit -ne 0) { throw 'Plugin initialization failed.' }
