$ErrorActionPreference = 'Stop'
$environmentNames = @('JAVA_HOME', 'OPENNEST_ENVIRONMENT', 'OPENNEST_DATA_MODE', 'OPENNEST_GOOGLE_ENABLED',
    'DB_PASSWORD', 'SANDBOX_DB_PASSWORD', 'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET')
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('opennest-launcher-tests-' + [Guid]::NewGuid().ToString('N'))
$launcherDirectory = Join-Path $testRoot 'scripts'
$artifactDirectory = Join-Path $testRoot 'backend/target'
$launcherProbe = [pscustomobject]@{ PromptCalls = 0; ReceivedSecret = $null }

function docker { $global:LASTEXITCODE = 0 }
function java {
    $global:LASTEXITCODE = 0
    if ($args -contains '--version') { 'openjdk 25.0.4' }
    elseif ($args -contains '-jar') { $launcherProbe.ReceivedSecret = $env:GOOGLE_CLIENT_SECRET }
    else { throw 'Unexpected Java invocation in launcher regression.' }
}
function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    if (-not $AsSecureString) { throw 'The secret prompt must use hidden input.' }
    $launcherProbe.PromptCalls++
    ConvertTo-SecureString 'fabricated-prompt-secret' -AsPlainText -Force
}

try {
    New-Item -ItemType Directory -Path $launcherDirectory, $artifactDirectory -Force | Out-Null
    $testLauncher = Join-Path $launcherDirectory 'start-local.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-local.ps1') -Destination $testLauncher
    [IO.File]::WriteAllText((Join-Path $artifactDirectory 'opennest-backend-0.0.1-SNAPSHOT.jar'), '')
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $null, 'Process')
    $cases = @(
        @{ Name = 'blank'; Entry = ''; Prompts = 1; Expected = 'fabricated-prompt-secret' },
        @{ Name = 'whitespace'; Entry = '   '; Prompts = 1; Expected = 'fabricated-prompt-secret' },
        @{ Name = 'double-quoted blank'; Entry = '""'; Prompts = 1; Expected = 'fabricated-prompt-secret' },
        @{ Name = 'single-quoted blank'; Entry = "''"; Prompts = 1; Expected = 'fabricated-prompt-secret' },
        @{ Name = 'nonblank'; Entry = 'fabricated-file-secret'; Prompts = 0; Expected = 'fabricated-file-secret' },
        @{ Name = 'quoted nonblank'; Entry = '"fabricated-file-secret"'; Prompts = 0; Expected = 'fabricated-file-secret' },
        @{ Name = 'omitted'; Entry = $null; Prompts = 0; Expected = 'fabricated-inherited-secret' }
    )
    foreach ($case in $cases) {
        $settings = @('OPENNEST_ENVIRONMENT=DEVELOPMENT', 'OPENNEST_DATA_MODE=PRIMARY', 'OPENNEST_GOOGLE_ENABLED=true',
            'DB_PASSWORD=fabricated-primary-password', 'SANDBOX_DB_PASSWORD=fabricated-sandbox-password',
            'GOOGLE_CLIENT_ID=fabricated-client-id')
        if ($null -ne $case.Entry) { $settings += 'GOOGLE_CLIENT_SECRET=' + $case.Entry }
        $settingsPath = Join-Path $testRoot '.env'
        [IO.File]::WriteAllLines($settingsPath, $settings)
        $env:GOOGLE_CLIENT_SECRET = 'fabricated-inherited-secret'
        $launcherProbe.PromptCalls = 0
        $launcherProbe.ReceivedSecret = $null
        & $testLauncher 6>$null
        if ($launcherProbe.PromptCalls -ne $case.Prompts -or $launcherProbe.ReceivedSecret -ne $case.Expected) {
            throw ('Launcher secret handling failed for case: ' + $case.Name)
        }
        if ($env:GOOGLE_CLIENT_SECRET -ne 'fabricated-inherited-secret') {
            throw 'The launcher did not restore the parent process secret.'
        }
        if ([IO.File]::ReadAllText($settingsPath).Contains('fabricated-prompt-secret')) {
            throw 'The launcher saved a prompted secret.'
        }
    }
    Write-Output ('Passed ' + $cases.Count + ' launcher secret regressions without starting Docker or Java.')
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
    }
    $absoluteTestRoot = [IO.Path]::GetFullPath($testRoot)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $absoluteTestRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($absoluteTestRoot) -notlike 'opennest-launcher-tests-*') {
        throw 'Refusing to clean an unexpected regression directory.'
    }
    if (Test-Path -LiteralPath $absoluteTestRoot) { Remove-Item -LiteralPath $absoluteTestRoot -Recurse -Force }
}
