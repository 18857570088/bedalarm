$ProjectRoot = 'D:\2026\202605\bed alarm'
$SourceRoot = Join-Path $ProjectRoot 'bed-alarm-android'
$CodexWorkRoot = 'D:\2026\codexwork'

$env:BED_ALARM_PROJECT_ROOT = $ProjectRoot
$env:BED_ALARM_SOURCE_ROOT = $SourceRoot
$env:CODEX_WORK_ROOT = $CodexWorkRoot
$env:GRADLE_USER_HOME = Join-Path $CodexWorkRoot '.gradle'
$env:ANDROID_USER_HOME = Join-Path $CodexWorkRoot '.android'
$env:ANDROID_AVD_HOME = Join-Path $CodexWorkRoot '.android\avd'
$env:ANDROID_HOME = Join-Path $CodexWorkRoot 'AndroidSDK'
$env:ANDROID_SDK_ROOT = Join-Path $CodexWorkRoot 'AndroidSDK'

Remove-Item Env:\ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME | Out-Null
Set-Location -LiteralPath $SourceRoot

Write-Host "Project root: $env:BED_ALARM_PROJECT_ROOT"
Write-Host "Source root : $env:BED_ALARM_SOURCE_ROOT"
Write-Host "Codex work  : $env:CODEX_WORK_ROOT"
Write-Host "Gradle home : $env:GRADLE_USER_HOME"
Write-Host "Android SDK : $env:ANDROID_SDK_ROOT"
