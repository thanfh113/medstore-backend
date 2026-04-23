$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

Write-Host "Starting nhathuoc-backend on http://127.0.0.1:8080"
& ".\gradlew.bat" run
