param([Parameter(Mandatory)][string]$BuildRoot)
$ErrorActionPreference = 'Stop'
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
if ($BuildRoot -match '\s' -or $BuildRoot.TrimEnd('\', '/') -eq [IO.Path]::GetPathRoot($BuildRoot).TrimEnd('\', '/')) {
    throw 'Use a dedicated build directory without spaces, not a drive root.'
}
New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed: $LASTEXITCODE" }
}
$sources = @(
    @('ffmpeg', 'https://github.com/FFmpeg/FFmpeg.git', 'n7.1', 'b08d7969c550a804a59511c7b83f2dd8cc0499b8'),
    @('mbedtls', 'https://github.com/Mbed-TLS/mbedtls.git', 'mbedtls-3.6.2', '107ea89daaefb9867ea9121002fbbdf926780e98'),
    @('libxml2', 'https://gitlab.gnome.org/GNOME/libxml2.git', 'v2.13.5', 'de918d45e1b2276a28a4cd32bcf556bef65284e4')
)
foreach ($source in $sources) {
    $path = Join-Path $BuildRoot $source[0]
    if (!(Test-Path -LiteralPath $path)) {
        Invoke-Checked git @('clone', '--depth', '1', '--branch', $source[2], $source[1], $path)
    }
    $commit = & git -C $path rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $commit -ne $source[3]) { throw "Unexpected source revision: $path" }
}
Invoke-Checked git @('-C', "$BuildRoot/mbedtls", 'submodule', 'update', '--init', '--recursive', '--depth', '1')
foreach ($patch in @('ffmpeg-7359.patch', 'ffmpeg-force-mpegts.patch')) {
    $patchPath = Join-Path $PSScriptRoot $patch
    & git -C "$BuildRoot/ffmpeg" apply --reverse --check $patchPath 2>$null
    if ($LASTEXITCODE -ne 0) { Invoke-Checked git @('-C', "$BuildRoot/ffmpeg", 'apply', '--check', $patchPath); Invoke-Checked git @('-C', "$BuildRoot/ffmpeg", 'apply', $patchPath) }
}
# MSYS make uses the same path conventions/runtime as Git Bash. The NDK's Windows make cannot consume /tmp paths.
$makeArchive = Join-Path $BuildRoot 'make.pkg.tar.zst'
if (!(Test-Path -LiteralPath $makeArchive)) {
    Invoke-WebRequest 'https://repo.msys2.org/msys/x86_64/make-4.4.1-3-x86_64.pkg.tar.zst' -OutFile $makeArchive
}
if ((Get-FileHash -LiteralPath $makeArchive).Hash -ne 'AF0BDBA17F06FE037F0194069ADAA31A8FE45F1A11381501896AEA1FAE37BD5D') { throw 'Unexpected make archive checksum' }
New-Item -ItemType Directory -Force -Path "$BuildRoot/make" | Out-Null
Invoke-Checked tar @('-xf', $makeArchive, '-C', "$BuildRoot/make")
