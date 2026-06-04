. 'D:\2026\202605\bed alarm\env.ps1'
.\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$apkDir = Join-Path $env:BED_ALARM_SOURCE_ROOT 'app\build\outputs\apk\debug'
$targetApk = Join-Path $apkDir 'bedalarm.apk'
Write-Host "APK output : $targetApk"
