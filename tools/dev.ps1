param([ValidateSet('build','test','runClient','runNpcGameTest','runNpcComplexGameTest','runAnchorGameTest','runAnchorClient','runAreaGameTest','runPlayerGameTest','runLoaderGameTest','runLoaderClient','runPlayerClient','runCompanionClient','runSeriesClient','runAiSettingsGameTest','runAiSettingsClient')][string]$Task = 'build')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$jdk25 = Join-Path $env:USERPROFILE '.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
if (Test-Path (Join-Path $jdk25 'bin\java.exe')) { $env:JAVA_HOME = $jdk25 }
if (-not (Test-Path '.tmp')) { New-Item -ItemType Directory '.tmp' | Out-Null }
$socketDirectory = Join-Path (Get-Location) '.tmp'
$env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' "-Djdk.net.unixdomain.tmpdir=' + $socketDirectory + '"').Trim())
& .\gradlew.bat $Task
exit $LASTEXITCODE
