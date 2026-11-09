param([switch]$EnableBackend)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $backendRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $backendRoot '.env.example') -Destination $envFile
}
$contents = [IO.File]::ReadAllText($envFile)
function Set-LocalSetting([string]$Key, [string]$Value) {
    $pattern = '(?m)^[ \t]*' + [regex]::Escape($Key) + '[ \t]*=.*$'
    if ([regex]::IsMatch($script:contents, $pattern)) {
        $script:contents = [regex]::Replace($script:contents, $pattern, "$Key=$Value")
    } else { $script:contents += "`r`n$Key=$Value`r`n" }
}
foreach ($key in @('ZAZA_KEYCLOAK_CLIENT_SECRET', 'ZAZA_KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD')) {
    if (-not [regex]::IsMatch($contents, ('(?m)^[ \t]*' + $key + '[ \t]*=[ \t]*\S+'))) {
        $random = New-Object byte[] 32
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($random) } finally { $generator.Dispose() }
        try { Set-LocalSetting $key ([BitConverter]::ToString($random).Replace('-', '')) }
        finally { [Array]::Clear($random, 0, $random.Length) }
    }
}
if ($EnableBackend) {
    Set-LocalSetting 'ZAZA_KEYCLOAK_ENABLED' 'true'
    Set-LocalSetting 'ZAZA_KEYCLOAK_URL' 'http://localhost:9080'
    Set-LocalSetting 'ZAZA_KEYCLOAK_REALM' 'zaza'
    Set-LocalSetting 'ZAZA_KEYCLOAK_CLIENT_ID' 'zaza-backend'
}
[IO.File]::WriteAllText($envFile, $contents, [Text.UTF8Encoding]::new($false))
$contents = $null
& (Join-Path $PSScriptRoot 'initialize-document-key.ps1') -EnvFile $envFile
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else {
    Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
}
if (-not (Test-Path -LiteralPath $docker)) { throw 'Docker Desktop is required. Start Docker Desktop and try again.' }
Push-Location -LiteralPath $backendRoot
try {
    & $docker compose --progress plain --env-file $envFile -f compose.keycloak.yaml up -d --quiet-pull
    if ($LASTEXITCODE -ne 0) { throw 'Keycloak container startup failed. Check Docker Desktop and port 9080.' }
    Write-Output 'Keycloak container created. Realm zaza / client zaza-backend will import on first startup.'
    Write-Output 'Admin console: http://localhost:9080/admin/ (username zaza-admin; password is in the local .env).'
    if ($EnableBackend) { Write-Output 'Keycloak mode enabled in .env. Wait for realm discovery, then restart the backend.' }
} finally { Pop-Location }
