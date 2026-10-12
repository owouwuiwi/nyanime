param(
    [Parameter(Mandatory)][string]$BuildRoot,
    [Parameter(Mandatory)][string]$Ndk,
    [Parameter(Mandatory)][string]$CmakeBin,
    [Parameter(Mandatory)][string]$FfmpegKitAar,
    [Parameter(Mandatory)][string]$MpvAar,
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')][string]$Abi = 'arm64-v8a'
)
$ErrorActionPreference = 'Stop'
if ((Get-FileHash -LiteralPath $FfmpegKitAar).Hash -ne 'F3CFBD97F85BA25BCD6C594227D10DF612F69AD4499576B65A4AA01190BE21D9' -or
    (Get-FileHash -LiteralPath $MpvAar).Hash -ne 'A08C2D3345FB1F46F7FFE2F68999F244666DE4A1ED1F90A0CEF6C1C761A6D793') {
    throw 'Unexpected dependency artifact. Review native ABI before rebuilding.'
}
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed: $LASTEXITCODE" }
}
$build = Join-Path $BuildRoot "build-$Abi"
$prefix = Join-Path $BuildRoot "prefix-$Abi"
New-Item -ItemType Directory -Force -Path $build, "$prefix/lib", "$prefix/include" | Out-Null
$toolchain = "$Ndk/build/cmake/android.toolchain.cmake"
$common = @('-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$CmakeBin/ninja.exe", "-DCMAKE_TOOLCHAIN_FILE=$toolchain", "-DANDROID_ABI=$Abi", '-DANDROID_PLATFORM=android-21', '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', "-DCMAKE_INSTALL_PREFIX=$prefix")
Invoke-Checked "$CmakeBin/cmake.exe" (@('-S', "$BuildRoot/mbedtls", '-B', "$build/mbedtls", '-DENABLE_TESTING=OFF', '-DENABLE_PROGRAMS=OFF', '-DUSE_SHARED_MBEDTLS_LIBRARY=OFF') + $common)
Invoke-Checked "$CmakeBin/cmake.exe" @('--build', "$build/mbedtls", '--target', 'install', '--parallel', '8')
# Configure headers only; preserve the libxml2 binary already shipped by FFmpegKit.
Invoke-Checked "$CmakeBin/cmake.exe" (@('-S', "$BuildRoot/libxml2", '-B', "$build/xml", '-DLIBXML2_WITH_PYTHON=OFF', '-DLIBXML2_WITH_PROGRAMS=OFF', '-DLIBXML2_WITH_TESTS=OFF', '-DLIBXML2_WITH_ICONV=OFF', '-DLIBXML2_WITH_LZMA=OFF', '-DLIBXML2_WITH_ZLIB=OFF') + $common)
Invoke-Checked "$CmakeBin/cmake.exe" @('--build', "$build/xml", '--target', 'install', '--parallel', '8')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $FfmpegKitAar))
$mpvArchive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $MpvAar))
try {
    foreach ($name in @('libavcodec.so', 'libavutil.so', 'libxml2.so')) {
        $entry = $archive.GetEntry("jni/$Abi/$name")
        if (!$entry) { $entry = $mpvArchive.GetEntry("jni/$Abi/$name") }
        if (!$entry) { throw "Missing $Abi/$name" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, "$prefix/lib/$name", $true)
    }
} finally { $archive.Dispose(); $mpvArchive.Dispose() }
$env:ANIYOMI_NATIVE_ROOT = $BuildRoot.Replace('\', '/')
$env:ANIYOMI_NATIVE_NDK = $Ndk.Replace('\', '/')
$env:ANIYOMI_NATIVE_ABI = $Abi
Invoke-Checked 'C:/Program Files/Git/bin/bash.exe' @((Join-Path $PSScriptRoot 'build-avformat.sh').Replace('\', '/'))
