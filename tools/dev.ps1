param([ValidateSet('build','test','runClient','runNpcGameTest','runNpcNaturalGameTest','runNpcTargetedGameTest','runNpcWaterGameTest','runNpcComplexGameTest','runAnchorGameTest','runAnchorClient','runAreaGameTest','runPlayerGameTest','runLoaderGameTest','runLoaderClient','runPlayerClient','runCompanionClient','runSeriesClient','runAiSettingsGameTest','runAiSettingsClient','runJournalGameTest','runJournalClient')][string]$Task = 'build')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($Task -eq 'runNpcTargetedGameTest' -and $env:AVATARCRAFT_QA_DIAMOND_TOOLS -eq 'true' -and $env:AVATARCRAFT_TARGET_MISSION -in @('coal','diamond') -and [string]::IsNullOrWhiteSpace($env:AVATARCRAFT_QA_COMMAND_OVERRIDE)) {
  Write-Output 'QA_SUPPLIED_TOOL_COMMAND_REQUIRED: use the updated campaign runner so supplied tools are used instead of requesting new tool crafting.'
  exit 77
}
$jdk25 = Join-Path $env:USERPROFILE '.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
if (Test-Path (Join-Path $jdk25 'bin\java.exe')) { $env:JAVA_HOME = $jdk25 }
if (-not (Test-Path '.tmp')) { New-Item -ItemType Directory '.tmp' | Out-Null }
$socketDirectory = Join-Path (Get-Location) '.tmp'
$env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' "-Djdk.net.unixdomain.tmpdir=' + $socketDirectory + '"').Trim())
& .\gradlew.bat $Task
exit $LASTEXITCODE
