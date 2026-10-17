param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ApplicationArguments)
$ErrorActionPreference = 'Stop'
$configuration = Join-Path $PSScriptRoot 'config/application.yml'
if (-not (Test-Path -LiteralPath $configuration)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config/application.example.yml') -Destination $configuration
    Write-Host 'Created config/application.yml. Edit it and set QQ_APP_ID / QQ_BOT_SECRET, then start again.'
    exit 0
}
Push-Location $PSScriptRoot
try {
    $ErrorActionPreference = 'Continue'
    & java -jar astra.jar @ApplicationArguments
    $nativeExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($nativeExit -ne 0) { throw 'Astra exited with an error.' }
}
finally { Pop-Location }
