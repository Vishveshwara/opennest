[CmdletBinding()]
param([switch]$Build)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $projectRoot '.env'
$jarPath = Join-Path $projectRoot 'backend/target/opennest-backend-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $settingsPath)) {
    throw 'Create a private .env from .env.example and configure database passwords and Telegram authentication first.'
}

Push-Location -LiteralPath $projectRoot
try {
    foreach ($line in Get-Content -LiteralPath $settingsPath) {
        if ($line -match '^\s*(?:#|$)') { continue }
        $parts = $line -split '=', 2
        if ($parts.Length -ne 2 -or $parts[0].Trim() -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw 'Invalid local .env entry. Use NAME=value entries without shell expressions.'
        }
        $entryName = $parts[0].Trim()
        $entryValue = $parts[1].Trim()
        if ($entryValue.Length -ge 2 -and (
            ($entryValue.StartsWith('"') -and $entryValue.EndsWith('"')) -or
            ($entryValue.StartsWith("'") -and $entryValue.EndsWith("'")))) {
            $entryValue = $entryValue.Substring(1, $entryValue.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($entryName, $entryValue, 'Process')
    }

    if ($env:OPENNEST_ENVIRONMENT -ne 'DEVELOPMENT' -or $env:OPENNEST_DATA_MODE -ne 'PRIMARY') {
        throw 'This launcher supports DEVELOPMENT/PRIMARY only. Use the deployment-specific configuration for other modes.'
    }
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -or [string]::IsNullOrWhiteSpace($env:SANDBOX_DB_PASSWORD)) {
        throw 'Set distinct DB_PASSWORD and SANDBOX_DB_PASSWORD values in the private .env file. Preserve passwords for existing volumes.'
    }
    if ($env:OPENNEST_TELEGRAM_AUTH_ENABLED -ne 'true' -or [string]::IsNullOrWhiteSpace($env:TELEGRAM_BOT_TOKEN)) {
        throw 'Run scripts/setup-telegram-local.ps1 to configure Telegram authentication before starting OpenNest.'
    }

    $javaExecutable = 'java'
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
    }
    $javaVersion = & $javaExecutable --version
    if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch '(?:openjdk|java) 25(?:[.\s])') {
        throw 'Select a Java 25 JDK through JAVA_HOME or PATH.'
    }

    & docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration failed.' }
    & docker compose up -d --wait --wait-timeout 180 postgres redis
    if ($LASTEXITCODE -ne 0) { throw 'Local stores did not become healthy. Check Docker Desktop and existing volume credentials.' }

    if ($Build -or -not (Test-Path -LiteralPath $jarPath)) {
        & .\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'The Java build failed.' }
    }

    Write-Host 'Starting OpenNest. Begin Telegram login with GET http://localhost:8080/api/v1/auth/csrf.'
    Write-Host 'Follow the Postman login flow in docs/TELEGRAM_HANDOFF.md. Stop Java with Ctrl+C.'
    & $javaExecutable -jar $jarPath
    if ($LASTEXITCODE -ne 0) { throw 'OpenNest stopped with an error. Confirm the selected database credentials and local settings.' }
} finally {
    Pop-Location
}
