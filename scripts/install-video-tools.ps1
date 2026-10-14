$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $backendRoot '.tools/ffmpeg'
$packageRoot = Join-Path $toolRoot 'ffmpeg-9.0.2-essentials_build'
$archive = Join-Path $toolRoot 'ffmpeg-9.0.2-essentials_build.zip'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
# FFmpeg's official download page links this Windows build provider.
$uri = 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip'
$sha256 = '60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba'
if (-not (Test-Path -LiteralPath $archive)) { Invoke-WebRequest -Uri $uri -OutFile $archive }
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
    throw 'FFmpeg checksum mismatch. No executables were installed.'
}
if (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'bin/ffmpeg.exe'))) {
    Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot
}
$binRoot = Join-Path $toolRoot 'bin'
New-Item -ItemType Directory -Force -Path $binRoot | Out-Null
foreach ($tool in @('ffmpeg.exe', 'ffprobe.exe')) {
    Copy-Item -LiteralPath (Join-Path $packageRoot "bin/$tool") -Destination (Join-Path $binRoot $tool)
}
# Keep the distribution's license, notices, and docs in the extracted package.
$ffmpegVersion = & (Join-Path $binRoot 'ffmpeg.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg could not run.' }
$ffmpegVersion | Select-Object -First 1
$ffprobeVersion = & (Join-Path $binRoot 'ffprobe.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'FFprobe could not run.' }
$ffprobeVersion | Select-Object -First 1
Write-Output "Portable video tools are ready in $binRoot. No global PATH changes were made."
