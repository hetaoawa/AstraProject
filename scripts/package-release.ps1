param([string]$BuildRoot = '', [string]$Output = '', [string]$CacheRoot = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $BuildRoot) { $BuildRoot = Join-Path $root 'target' }
if (-not $Output) { $Output = Join-Path $root 'release' }
if ($CacheRoot) { $env:ASTRA_CACHE_ROOT = $CacheRoot }
$ErrorActionPreference = 'Continue'
& java (Join-Path $PSScriptRoot 'lib/AstraDev.java') release --source $root --build-root $BuildRoot --output $Output
$nativeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($nativeExit -ne 0) { throw 'Release packaging failed.' }
