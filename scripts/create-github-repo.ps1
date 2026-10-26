# Requires Git + GitHub CLI (`gh`). Creates the private monorepo under theubyte.
$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..'))
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git fehlt. Installiere Git for Windows.' }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI fehlt. Installiere gh und führe gh auth login aus.' }
& gh auth status
if ($LASTEXITCODE -ne 0) { throw 'Zuerst: gh auth login' }
if (-not (Test-Path .git)) { & git init -b main; if ($LASTEXITCODE -ne 0) { throw 'git init fehlgeschlagen' } }
& git add .
& git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    & git commit -m 'Initialize Android Native Lab and offline LayerForge alpha'
    if ($LASTEXITCODE -ne 0) { throw 'Git commit fehlgeschlagen. Git Name/Email konfigurieren.' }
}
& git remote get-url origin 2>$null
if ($LASTEXITCODE -eq 0) { throw 'Origin existiert bereits. Nicht automatisch überschreiben.' }
& gh repo create theubyte/Android-Native-Lab --private --source . --remote origin --push
if ($LASTEXITCODE -ne 0) { throw 'GitHub Repository wurde nicht angelegt. Prüfe Berechtigungen/Namen.' }
Write-Host 'Fertig: https://github.com/theubyte/Android-Native-Lab'
