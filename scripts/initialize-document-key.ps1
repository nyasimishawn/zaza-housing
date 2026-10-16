param([string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))
$ErrorActionPreference = 'Stop'
$resolvedEnvFile = [IO.Path]::GetFullPath($EnvFile)
$existing = if (Test-Path -LiteralPath $resolvedEnvFile) { [IO.File]::ReadAllText($resolvedEnvFile) } else { '' }
$hasKeys = [regex]::IsMatch($existing, '(?m)^[ \t]*ZAZA_DOCUMENT_KEYS[ \t]*=[ \t]*\S+')
$hasActive = [regex]::IsMatch($existing, '(?m)^[ \t]*ZAZA_DOCUMENT_ACTIVE_KEY_ID[ \t]*=[ \t]*\S+')
$hasAnySetting = [regex]::IsMatch($existing, '(?m)^[ \t]*ZAZA_DOCUMENT_(KEYS|ACTIVE_KEY_ID)[ \t]*=')
if ($hasKeys -and $hasActive) {
    Write-Output 'Document encryption is already configured; existing key material was preserved.'
    return
}
if ($hasKeys -or $hasActive) {
    throw 'Document key configuration is incomplete. Complete both settings locally; this script will not overwrite key settings.'
}
$newKey = New-Object byte[] 32
$randomSource = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $randomSource.GetBytes($newKey) } finally { $randomSource.Dispose() }
try {
    $encodedKey = [Convert]::ToBase64String($newKey)
    $addition = "`r`nZAZA_DOCUMENT_KEYS=local-v1:$encodedKey`r`nZAZA_DOCUMENT_ACTIVE_KEY_ID=local-v1`r`n"
    if ($hasAnySetting) {
        $keyPattern = '(?m)^[ \t]*ZAZA_DOCUMENT_KEYS[ \t]*=[ \t]*\r?$'
        $activePattern = '(?m)^[ \t]*ZAZA_DOCUMENT_ACTIVE_KEY_ID[ \t]*=[ \t]*\r?$'
        $updated = [regex]::Replace($existing, $keyPattern, "ZAZA_DOCUMENT_KEYS=local-v1:$encodedKey")
        $updated = [regex]::Replace($updated, $activePattern, 'ZAZA_DOCUMENT_ACTIVE_KEY_ID=local-v1')
        if (-not [regex]::IsMatch($existing, '(?m)^[ \t]*ZAZA_DOCUMENT_KEYS[ \t]*=')) { $updated += "`r`nZAZA_DOCUMENT_KEYS=local-v1:$encodedKey`r`n" }
        if (-not [regex]::IsMatch($existing, '(?m)^[ \t]*ZAZA_DOCUMENT_ACTIVE_KEY_ID[ \t]*=')) { $updated += "`r`nZAZA_DOCUMENT_ACTIVE_KEY_ID=local-v1`r`n" }
        [IO.File]::WriteAllText($resolvedEnvFile, $updated, [Text.UTF8Encoding]::new($false))
        $updated = $null
    } else { [IO.File]::AppendAllText($resolvedEnvFile, $addition, [Text.UTF8Encoding]::new($false)) }
} finally {
    [Array]::Clear($newKey, 0, $newKey.Length)
    $encodedKey = $null
    $addition = $null
}
Write-Output 'Initialized one document encryption key in the local environment file. Preserve this key with encrypted-file backups.'
