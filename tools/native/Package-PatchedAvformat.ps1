param(
    [Parameter(Mandatory)][string]$BuildRoot,
    [Parameter(Mandatory)][string]$FfmpegKitAar
)
$ErrorActionPreference = 'Stop'
if ((Get-FileHash -LiteralPath $FfmpegKitAar).Hash -ne 'F3CFBD97F85BA25BCD6C594227D10DF612F69AD4499576B65A4AA01190BE21D9') {
    throw 'Unexpected original FFmpegKit artifact; review the ABI before rebuilding.'
}
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$out = Join-Path $repo 'vendor/maven/com/github/jmir1/ffmpeg-kit/1.18-hls1/ffmpeg-kit-1.18-hls1.aar'
$abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
$replacements = @{}
foreach ($abi in $abis) {
    $lib = Join-Path $BuildRoot "build-$abi/ffmpeg/libavformat/libavformat.so"
    if (!(Test-Path -LiteralPath $lib)) { throw "Missing $abi library" }
    $replacements["jni/$abi/libavformat.so"] = $lib
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$source = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $FfmpegKitAar))
# Build output is replaced, never the input artifact. A fixed ZIP timestamp makes packaging deterministic.
$stream = [IO.File]::Open($out, [IO.FileMode]::Create)
$target = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
$count = 0
try {
    foreach ($entry in ($source.Entries | Sort-Object FullName)) {
        $next = $target.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::Optimal)
        $next.LastWriteTime = [DateTimeOffset]::new(2026, 9, 12, 0, 0, 0, [TimeSpan]::Zero)
        $inputStream = if ($replacements.ContainsKey($entry.FullName)) {
            $count++
            [IO.File]::OpenRead($replacements[$entry.FullName])
        } else { $entry.Open() }
        $outputStream = $next.Open()
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
    }
} finally { $target.Dispose(); $stream.Dispose(); $source.Dispose() }
if ($count -ne 4) { throw "Expected exactly four replacements, got $count" }
$hashes = @((Get-FileHash -LiteralPath $out)) + @($replacements.Values | Sort-Object | ForEach-Object { Get-FileHash -LiteralPath $_ })
$hashes | Select-Object Path, Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $BuildRoot 'packaged-hashes.json')
$hashes | Format-Table -AutoSize
