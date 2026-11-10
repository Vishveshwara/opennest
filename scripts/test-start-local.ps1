$ErrorActionPreference = 'Stop'
$environmentNames = @('JAVA_HOME', 'OPENNEST_ENVIRONMENT', 'OPENNEST_DATA_MODE', 'OPENNEST_TELEGRAM_AUTH_ENABLED',
    'OPENNEST_TELEGRAM_POLLING_ENABLED', 'TELEGRAM_BOT_TOKEN', 'DB_PASSWORD', 'SANDBOX_DB_PASSWORD',
    'OPENNEST_LAUNCHER_FIXTURE')
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('opennest-launcher-tests-' + [Guid]::NewGuid().ToString('N'))
$launcherDirectory = Join-Path $testRoot 'scripts'
$artifactDirectory = Join-Path $testRoot 'backend/target'
$token = '123456789:fabricated_fixture_token_canary'
$launcherProbe = [pscustomobject]@{
    PromptCalls = 0
    VersionCalls = 0
    DockerCalls = 0
    JarCalls = 0
    ReceivedToken = $null
    JavaVersion = 'openjdk 25.0.4'
    VersionExit = 0
    JarExit = 0
    DockerFailureAt = 0
}

function docker {
    $launcherProbe.DockerCalls++
    if (($args -join ' ') -notin @('compose config --quiet', 'compose up -d --wait --wait-timeout 180 postgres redis')) {
        throw 'Unexpected Docker invocation in launcher regression.'
    }
    $global:LASTEXITCODE = if ($launcherProbe.DockerCalls -eq $launcherProbe.DockerFailureAt) { 1 } else { 0 }
}
function java {
    if ($args -contains '--version') {
        $launcherProbe.VersionCalls++
        $global:LASTEXITCODE = $launcherProbe.VersionExit
        $launcherProbe.JavaVersion
    } elseif ($args -contains '-jar') {
        $launcherProbe.JarCalls++
        $launcherProbe.ReceivedToken = $env:TELEGRAM_BOT_TOKEN
        $global:LASTEXITCODE = $launcherProbe.JarExit
    } else { throw 'Unexpected Java invocation in launcher regression.' }
}
function Read-Host {
    $launcherProbe.PromptCalls++
    throw 'The Telegram launcher must not prompt for credentials.'
}

try {
    New-Item -ItemType Directory -Path $launcherDirectory, $artifactDirectory -Force | Out-Null
    $testLauncher = Join-Path $launcherDirectory 'start-local.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-local.ps1') -Destination $testLauncher
    [IO.File]::WriteAllText((Join-Path $artifactDirectory 'opennest-backend-0.0.1-SNAPSHOT.jar'), '')
    $cases = @(
        @{ Name = 'plain token'; Token = $token },
        @{ Name = 'double-quoted token'; Token = '"' + $token + '"' },
        @{ Name = 'single-quoted token'; Token = "'" + $token + "'" },
        @{ Name = 'inherited token'; Inherited = $token },
        @{ Name = 'blank token'; Token = ''; Rejection = 'Telegram authentication'; Versions = 0 },
        @{ Name = 'whitespace token'; Token = '   '; Rejection = 'Telegram authentication'; Versions = 0 },
        @{ Name = 'double-quoted blank token'; Token = '""'; Rejection = 'Telegram authentication'; Versions = 0 },
        @{ Name = 'single-quoted blank token'; Token = "''"; Rejection = 'Telegram authentication'; Versions = 0 },
        @{ Name = 'missing token'; Rejection = 'Telegram authentication'; Versions = 0 },
        @{ Name = 'authentication disabled'; Token = $token; Auth = 'false'; Rejection = 'Telegram authentication'; Versions = 0 },
        @{ Name = 'authentication missing'; Token = $token; Auth = $null; Rejection = 'Telegram authentication'; Versions = 0 },
        @{ Name = 'production environment'; Token = $token; Runtime = 'PRODUCTION'; Rejection = 'DEVELOPMENT/PRIMARY'; Versions = 0 },
        @{ Name = 'sandbox mode'; Token = $token; Mode = 'SANDBOX'; Rejection = 'DEVELOPMENT/PRIMARY'; Versions = 0 },
        @{ Name = 'primary database password absent'; Token = $token; Database = ''; Rejection = 'DB_PASSWORD'; Versions = 0 },
        @{ Name = 'sandbox database password absent'; Token = $token; SandboxDatabase = ''; Rejection = 'DB_PASSWORD'; Versions = 0 },
        @{ Name = 'Java 24'; Token = $token; JavaVersion = 'openjdk 24.0.2'; Rejection = 'Java 25'; Versions = 1 },
        @{ Name = 'Java version command failure'; Token = $token; VersionExit = 1; Rejection = 'Java 25'; Versions = 1 },
        @{ Name = 'Docker configuration failure'; Token = $token; DockerFailureAt = 1; Rejection = 'Compose configuration'; Versions = 1; Docker = 1 },
        @{ Name = 'Docker startup failure'; Token = $token; DockerFailureAt = 2; Rejection = 'Local stores'; Versions = 1; Docker = 2 },
        @{ Name = 'backend process failure'; Token = $token; JarExit = 1; Rejection = 'OpenNest stopped'; Versions = 1; Docker = 2; Jar = 1 }
    )
    foreach ($case in $cases) {
        foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
        if ($case.ContainsKey('Inherited')) { $env:TELEGRAM_BOT_TOKEN = $case.Inherited }
        $runtime = if ($case.ContainsKey('Runtime')) { $case.Runtime } else { 'DEVELOPMENT' }
        $mode = if ($case.ContainsKey('Mode')) { $case.Mode } else { 'PRIMARY' }
        $auth = if ($case.ContainsKey('Auth')) { $case.Auth } else { 'true' }
        $database = if ($case.ContainsKey('Database')) { $case.Database } else { 'fabricated-primary-password' }
        $sandboxDatabase = if ($case.ContainsKey('SandboxDatabase')) { $case.SandboxDatabase } else { 'fabricated-sandbox-password' }
        $settings = @("OPENNEST_ENVIRONMENT=$runtime", "OPENNEST_DATA_MODE=$mode",
            "DB_PASSWORD=$database", "SANDBOX_DB_PASSWORD=$sandboxDatabase",
            'OPENNEST_TELEGRAM_POLLING_ENABLED=true', 'OPENNEST_LAUNCHER_FIXTURE=fabricated-preserved-value')
        if ($null -ne $auth) { $settings += "OPENNEST_TELEGRAM_AUTH_ENABLED=$auth" }
        if ($case.ContainsKey('Token')) { $settings += 'TELEGRAM_BOT_TOKEN=' + $case.Token }
        $settingsPath = Join-Path $testRoot '.env'
        [IO.File]::WriteAllLines($settingsPath, $settings)
        $originalSettings = [Convert]::ToBase64String([IO.File]::ReadAllBytes($settingsPath))
        $launcherProbe.PromptCalls = 0
        $launcherProbe.VersionCalls = 0
        $launcherProbe.DockerCalls = 0
        $launcherProbe.JarCalls = 0
        $launcherProbe.ReceivedToken = $null
        $launcherProbe.JavaVersion = if ($case.ContainsKey('JavaVersion')) { $case.JavaVersion } else { 'openjdk 25.0.4' }
        $launcherProbe.VersionExit = if ($case.ContainsKey('VersionExit')) { $case.VersionExit } else { 0 }
        $launcherProbe.JarExit = if ($case.ContainsKey('JarExit')) { $case.JarExit } else { 0 }
        $launcherProbe.DockerFailureAt = if ($case.ContainsKey('DockerFailureAt')) { $case.DockerFailureAt } else { 0 }
        $failure = $null
        try { & $testLauncher 6>$null } catch { $failure = $_.Exception.Message }
        $rejects = $case.ContainsKey('Rejection')
        if ($rejects) {
            if ($null -eq $failure -or -not $failure.Contains($case.Rejection)) {
                throw ('Launcher did not reject the expected condition: ' + $case.Name)
            }
        } elseif ($null -ne $failure) { throw ('Launcher unexpectedly rejected case: ' + $case.Name) }
        $expectedVersions = if ($rejects) { $case.Versions } else { 1 }
        $expectedDocker = if ($case.ContainsKey('Docker')) { $case.Docker } elseif ($rejects) { 0 } else { 2 }
        $expectedJar = if ($case.ContainsKey('Jar')) { $case.Jar } elseif ($rejects) { 0 } else { 1 }
        if ($launcherProbe.PromptCalls -ne 0 -or $launcherProbe.VersionCalls -ne $expectedVersions -or
            $launcherProbe.DockerCalls -ne $expectedDocker -or $launcherProbe.JarCalls -ne $expectedJar) {
            throw ('Launcher invoked a dependency or prompt unexpectedly: ' + $case.Name)
        }
        if ($expectedJar -eq 1 -and $launcherProbe.ReceivedToken -ne $token) {
            throw ('Launcher did not pass the configured Telegram token: ' + $case.Name)
        }
        if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($settingsPath)) -ne $originalSettings -or
            $env:OPENNEST_LAUNCHER_FIXTURE -ne 'fabricated-preserved-value') {
            throw ('Launcher changed private settings or lost unrelated configuration: ' + $case.Name)
        }
    }
    Write-Output ('Passed ' + $cases.Count + ' Telegram launcher regressions using isolated settings and mocked Docker/Java; no prompts or real service calls.')
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
