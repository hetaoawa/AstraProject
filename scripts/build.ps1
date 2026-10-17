param(
    [string]$CacheRoot = $env:ASTRA_CACHE_ROOT,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Settings = $env:ASTRA_MAVEN_SETTINGS,
    [string[]]$MavenArguments = @()
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($CacheRoot)) { $CacheRoot = Join-Path $projectRoot '.astra/cache' }
$CacheRoot = [IO.Path]::GetFullPath($CacheRoot)
$taskRoot = Join-Path $CacheRoot 'work/astra'
$buildRoot = Join-Path $taskRoot 'build'
$tempRoot = Join-Path $CacheRoot 'temp/astra'
$repository = if ($env:ASTRA_MAVEN_REPOSITORY) { $env:ASTRA_MAVEN_REPOSITORY } else { Join-Path $CacheRoot 'm2' }
foreach ($path in @($taskRoot, $buildRoot, $tempRoot, $repository)) {
    [IO.Directory]::CreateDirectory($path) | Out-Null
}
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set -JavaHome to an existing Java 21 JDK.'
}
$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
$env:MAVEN_OPTS = '-Djava.io.tmpdir="' + $tempRoot + '"'
if (-not $env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME = Join-Path $CacheRoot 'maven-user' }
$env:ASTRA_CACHE_ROOT = $CacheRoot
$env:MAVEN_ARGS = ''
$ErrorActionPreference = 'Continue'
$versionText = & (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String
$ErrorActionPreference = 'Stop'
if ($versionText -notmatch 'version "21\.') { throw 'Java 21 is required.' }
if ([string]::IsNullOrWhiteSpace($Settings)) {
    $Settings = Join-Path $taskRoot 'build-settings.xml'
    $proxy = [Net.WebRequest]::GetSystemWebProxy().GetProxy([Uri]'https://repo.maven.apache.org')
    $proxyXml = ''
    if ($proxy.Host -ne 'repo.maven.apache.org') {
        $hostText = [Security.SecurityElement]::Escape($proxy.Host)
        $proxyXml = '<proxies><proxy><id>astra-build</id><active>true</active><protocol>http</protocol><host>' + $hostText + '</host><port>' + $proxy.Port + '</port><nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy></proxies>'
    }
    [IO.File]::WriteAllText($Settings, '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">' + $proxyXml + '</settings>', [Text.UTF8Encoding]::new($false))
}
Push-Location $projectRoot
try {
    $arguments = @('-s', $Settings, '-gs', $Settings, ('-Dmaven.repo.local=' + $repository), ('-Dastra.buildRoot=' + $buildRoot), 'verify') + $MavenArguments
    $ErrorActionPreference = 'Continue'
    & (Join-Path $projectRoot 'mvnw.cmd') @arguments
    $mavenExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($mavenExitCode -ne 0) { throw 'Maven verification failed.' }
    & (Join-Path $PSScriptRoot 'package-release.ps1') -BuildRoot $buildRoot -CacheRoot $env:ASTRA_CACHE_ROOT
} finally {
    Pop-Location
}
