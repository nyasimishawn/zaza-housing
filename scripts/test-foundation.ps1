param([string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin', [switch]$UseLocalDatabase)
$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'C:\Program Files\Java\jdk-24')) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
}
if ($UseLocalDatabase) {
    Push-Location -LiteralPath $backendRoot
    try {
        # The test profile fixes both JDBC and Flyway to zaza_foundation_test.
        & .\gradlew.bat test bootJar --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Foundation verification failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
    return
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('zaza-foundation-tests\' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$clusterPath = Join-Path $testRoot 'data'
$logPath = Join-Path $testRoot 'postgres.log'
$socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$socket.Start()
$testPort = $socket.LocalEndpoint.Port
$socket.Stop()
$previousUrl = $env:ZAZA_TEST_DB_URL
$previousUser = $env:ZAZA_TEST_DB_USER
$previousPassword = $env:ZAZA_TEST_DB_PASSWORD
$started = $false
try {
    # Trust applies only to this disposable cluster containing generated test accounts.
    $initArguments = @('-D', ('"' + $clusterPath + '"'), '-U', 'admin', '-A', 'trust', '-E', 'UTF8', '--no-locale')
    $initProcess = Start-Process -FilePath (Join-Path $PostgresBin 'initdb.exe') -ArgumentList $initArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'initdb.log') -RedirectStandardError (Join-Path $testRoot 'initdb-error.log')
    if (-not $initProcess.WaitForExit(45000)) {
        $initProcess.Kill($true)
        throw "Test database initialization timed out. See $testRoot. You can use -UseLocalDatabase to test in a separate schema using .env credentials."
    }
    $initProcess.Refresh()
    if ($initProcess.ExitCode -ne 0) { throw "Test database initialization failed. See $testRoot." }
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $clusterPath -l $logPath -o "-h 127.0.0.1 -p $testPort" -w -t 20 start | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Test database startup failed. See $logPath." }
    $started = $true
    & (Join-Path $PostgresBin 'createdb.exe') -w -h 127.0.0.1 -p $testPort -U admin zaza_foundation_test
    if ($LASTEXITCODE -ne 0) { throw 'Test database creation failed.' }
    $env:ZAZA_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$testPort/zaza_foundation_test"
    $env:ZAZA_TEST_DB_USER = 'admin'
    $env:ZAZA_TEST_DB_PASSWORD = ''
    # Override a .env production password with a test-only value. Trust ignores it.
    $env:ZAZA_TEST_DB_PASSWORD = 'disposable-test-only'
    Push-Location -LiteralPath $backendRoot
    try {
        & .\gradlew.bat test bootJar --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Foundation verification failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    if ($started) { & (Join-Path $PostgresBin 'pg_ctl.exe') -D $clusterPath -w -t 15 -m fast stop | Out-Null }
    $env:ZAZA_TEST_DB_URL = $previousUrl
    $env:ZAZA_TEST_DB_USER = $previousUser
    $env:ZAZA_TEST_DB_PASSWORD = $previousPassword
}
