[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $projectRoot '.env'
$temporaryPath = Join-Path $projectRoot '.env.telegram.tmp'
$botToken = $null

if (-not (Test-Path -LiteralPath $settingsPath)) { throw 'Create the private .env from .env.example and configure local database credentials first.' }
Push-Location -LiteralPath $projectRoot
try {
    foreach ($privateFile in @('.env', '.env.telegram.tmp')) {
        & git check-ignore --quiet -- $privateFile
        if ($LASTEXITCODE -ne 0) { throw 'Private environment files must be ignored by Git before Telegram setup.' }
    }
    $original = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($settingsPath))
    $lines = [System.IO.File]::ReadAllLines($settingsPath)
    $values = @{}
    foreach ($line in $lines) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { $values[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'") }
    }
    if ($values['OPENNEST_ENVIRONMENT'] -ne 'DEVELOPMENT' -or $values['OPENNEST_DATA_MODE'] -ne 'PRIMARY') {
        throw 'Local Telegram authentication requires DEVELOPMENT/PRIMARY.'
    }
    if ($values.ContainsKey('OTP_PROVIDER')) { throw 'Remove OTP_PROVIDER from the private .env. OpenNest has no console OTP provider.' }
    $botToken = $values['TELEGRAM_BOT_TOKEN']
    if ([string]::IsNullOrWhiteSpace($botToken)) {
        $secret = Read-Host 'Telegram bot token (hidden input)' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        try { $botToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
    }
    if ($botToken -notmatch '^[1-9][0-9]{4,19}:[A-Za-z0-9_-]{20,100}$') { throw 'The Telegram bot token format is invalid.' }
    try {
        $identity = Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$botToken/getMe" -ContentType 'application/json' -Body '{}' -TimeoutSec 10
        $webhook = Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$botToken/getWebhookInfo" -ContentType 'application/json' -Body '{}' -TimeoutSec 10
    } catch { throw 'Telegram bot identity check failed. Confirm the private token and network access.' }
    if (-not $identity.ok -or -not $identity.result.is_bot -or $identity.result.username -notmatch '^[A-Za-z][A-Za-z0-9_]{4,31}$') {
        throw 'Telegram did not return a valid bot identity.'
    }
    if (-not $webhook.ok -or $null -eq $webhook.result.url -or $webhook.result.url -ne '') {
        throw 'This bot already has a webhook or its status is unavailable. Use a separate local bot or remove the webhook deliberately before polling.'
    }
    $updates = [ordered]@{
        OPENNEST_TELEGRAM_AUTH_ENABLED = 'true'
        OPENNEST_TELEGRAM_POLLING_ENABLED = 'true'
        TELEGRAM_BOT_USERNAME = [string]$identity.result.username
        TELEGRAM_BOT_TOKEN = $botToken
    }
    foreach ($name in @('TELEGRAM_WEBHOOK_SECRET', 'TELEGRAM_OTP_HMAC_KEY')) {
        if ([string]::IsNullOrWhiteSpace($values[$name])) {
            $bytes = New-Object byte[] 32
            $random = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $random.GetBytes($bytes) } finally { $random.Dispose() }
            $updates[$name] = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        }
    }
    $result = New-Object 'System.Collections.Generic.List[string]'
    $written = @{}
    $removed = @('OPENNEST_GOOGLE_ENABLED', 'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'SANDBOX_GOOGLE_CLIENT_ID', 'SANDBOX_GOOGLE_CLIENT_SECRET', 'OPENNEST_TELEGRAM_CONTACT_ENABLED')
    foreach ($line in $lines) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=' -and $removed -contains $matches[1]) { continue }
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=' -and $updates.Contains($matches[1])) {
            $name = $matches[1]; $result.Add("$name=$($updates[$name])"); $written[$name] = $true
        } else { $result.Add($line) }
    }
    foreach ($name in $updates.Keys) { if (-not $written.ContainsKey($name)) { $result.Add("$name=$($updates[$name])") } }
    if ([Convert]::ToBase64String([System.IO.File]::ReadAllBytes($settingsPath)) -ne $original) { throw 'The private .env changed during setup; retry to preserve those changes.' }
    [System.IO.File]::WriteAllLines($temporaryPath, $result, (New-Object System.Text.UTF8Encoding($false)))
    [System.IO.File]::Replace($temporaryPath, $settingsPath, [System.Management.Automation.Language.NullString]::Value)
    Write-Host 'OpenNest Telegram-only authentication settings saved privately. No messages were sent and no webhook was changed.'
    Write-Host 'Start the app with: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Build'
} finally {
    $botToken = $null
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
    Pop-Location
}
