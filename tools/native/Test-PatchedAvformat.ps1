param(
    [Parameter(Mandatory)][string]$BuildRoot,
    [Parameter(Mandatory)][string]$Ndk,
    [Parameter(Mandatory)][string]$FfmpegKitAar
)
$ErrorActionPreference = 'Stop'
$bin = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$aar = Join-Path $repo 'vendor/maven/com/github/jmir1/ffmpeg-kit/1.18-hls1/ffmpeg-kit-1.18-hls1.aar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-EntryHash($Entry) {
    $inputStream = $Entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($inputStream)) } finally { $sha.Dispose(); $inputStream.Dispose() }
}
function Get-Symbols([string]$Path, [string]$Kind) {
    $lines = & "$bin/llvm-nm.exe" -D $Kind --format=posix $Path
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect symbols in $Path" }
    @($lines | ForEach-Object { ($_ -split ' ')[0] -replace '@@', '@' })
}
$old = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $FfmpegKitAar))
$new = [IO.Compression.ZipFile]::OpenRead($aar)
$changed = @()
try {
    if ($old.Entries.Count -ne $new.Entries.Count) { throw 'Unexpected archive entry count' }
    foreach ($entry in $old.Entries) {
        $replacement = $new.GetEntry($entry.FullName)
        if (!$replacement) { throw "Missing entry $($entry.FullName)" }
        if ((Get-EntryHash $entry) -ne (Get-EntryHash $replacement)) { $changed += $entry.FullName }
    }
    if ($changed.Count -ne 4 -or @($changed | Where-Object { $_ -notmatch '^jni/(arm64-v8a|armeabi-v7a|x86|x86_64)/libavformat\.so$' }).Count -gt 0) {
        throw "Unexpected archive changes: $changed"
    }
    foreach ($entryName in $changed) {
        $abi = ($entryName -split '/')[1]
        $library = Join-Path $BuildRoot "build-$abi/ffmpeg/libavformat/libavformat.so"
        $original = Join-Path $BuildRoot "build-$abi/avformat-original.so"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($old.GetEntry($entryName), $original, $true)
        $exports = @(Get-Symbols $library '--defined-only')
        $missing = @(Get-Symbols $original '--defined-only' | Where-Object { $_ -notin $exports })
        if ($missing.Count -gt 0) { throw "$abi lost exported ABI symbols: $missing" }
        $dependencies = @('libavcodec.so', 'libavutil.so', 'libxml2.so') | ForEach-Object {
            Get-Symbols (Join-Path $BuildRoot "prefix-$abi/lib/$_") '--defined-only'
        }
        $unresolved = @(Get-Symbols $library '--undefined-only' | Where-Object {
            $_ -match '^(av_|avcodec_|avformat_|avpriv_|ff_|sws_|swr_|xml)' -and $_ -notin $dependencies
        })
        if ($unresolved.Count -gt 0) { throw "$abi unresolved dependency symbols: $unresolved" }
        $loads = @(& "$bin/llvm-readelf.exe" -lW $library | Where-Object { $_ -match '^\s*LOAD\s' })
        if ($loads.Count -eq 0 -or @($loads | Where-Object { $_ -notmatch '0x4000\s*$' }).Count -gt 0) { throw "$abi is not 16 KiB aligned" }
        Write-Output "$abi PASS: original exports retained; codec/XML imports resolved; ELF alignment 16 KiB"
    }
} finally { $old.Dispose(); $new.Dispose() }
Write-Output 'PASS: exactly four demuxer entries changed; all other archive bytes preserved'
