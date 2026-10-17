# SPDX-License-Identifier: EPL-2.0
param([ValidateSet('setup', 'package', 'dev')][string]$Command = 'dev')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$ErrorActionPreference = 'Continue'
& java (Join-Path $root '.astra-tools/AstraDev.java') $Command --project $root
$nativeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($nativeExit -ne 0) { throw "Plugin $Command failed." }
