$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..'))
if (-not (Test-Path .\gradlew.bat)) { throw 'Gradle-Wrapper nicht vorhanden.' }
& .\gradlew.bat ':apps:layerforge:assembleDebug'
if ($LASTEXITCODE -ne 0) { throw 'Android Build fehlgeschlagen.' }
Write-Host 'APK: apps\layerforge\build\outputs\apk\debug\layerforge-debug.apk'
