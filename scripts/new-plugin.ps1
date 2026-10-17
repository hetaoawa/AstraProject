param([string]$PluginId = '', [string]$Name = '', [string]$Package = '', [string]$GroupId = '', [string]$Directory = '', [string]$PluginVersion = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$arguments = @((Join-Path $PSScriptRoot 'lib/AstraDev.java'), 'new', '--source', $root)
foreach ($pair in @(@('id', $PluginId), @('name', $Name), @('package', $Package), @('group', $GroupId), @('directory', $Directory), @('plugin-version', $PluginVersion))) {
    if ($pair[1]) { $arguments += @(('--' + $pair[0]), $pair[1]) }
}
$ErrorActionPreference = 'Continue'
& java @arguments
$nativeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($nativeExit -ne 0) { throw 'Plugin creation failed.' }
