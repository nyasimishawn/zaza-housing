param([string]$DatabaseUrl, [string]$DatabaseUser)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'C:\Program Files\Java\jdk-24')) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
}
if ($PSBoundParameters.ContainsKey('DatabaseUrl')) { $env:ZAZA_DB_URL = $DatabaseUrl }
if ($PSBoundParameters.ContainsKey('DatabaseUser')) { $env:ZAZA_DB_USER = $DatabaseUser }
$passwordWasProvided = [bool]$env:ZAZA_DB_PASSWORD
$dotenvPath = Join-Path $backendRoot '.env'
$passwordInDotenv = (Test-Path -LiteralPath $dotenvPath) -and
    (Select-String -LiteralPath $dotenvPath -Pattern '^\s*ZAZA_DB_PASSWORD\s*=\s*\S' -Quiet)
if (-not $passwordWasProvided -and -not $passwordInDotenv) {
    $databaseSecret = Read-Host 'PostgreSQL database password' -AsSecureString
    $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($databaseSecret)
    try {
        $env:ZAZA_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
        $databaseSecret.Dispose()
    }
}
try {
    Push-Location -LiteralPath $backendRoot
    & .\gradlew.bat bootRun --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Backend startup failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
    if (-not $passwordWasProvided -and -not $passwordInDotenv) { Remove-Item Env:ZAZA_DB_PASSWORD -ErrorAction SilentlyContinue }
}
