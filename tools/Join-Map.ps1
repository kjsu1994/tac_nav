param([string]$Manifest = (Join-Path $PSScriptRoot 'map-parts.json'))
$ErrorActionPreference = 'Stop'
$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
$directory = Split-Path -Parent $manifestPath
$info = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([IO.Path]::GetFileName($info.name) -cne $info.name -or $info.name -notmatch '^[A-Za-z0-9_-]+\.tacmap$') { throw 'Invalid output name' }
if ($info.parts.Count -lt 1 -or $info.parts.Count -gt 100) { throw 'Invalid parts list' }
$output = Join-Path $directory $info.name
if (Test-Path -LiteralPath $output) { throw "Output already exists: $output" }
$temporary = $output + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
try {
    $destination = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        foreach ($part in $info.parts) {
            if ([IO.Path]::GetFileName($part.name) -cne $part.name) { throw 'Invalid part name' }
            $path = Join-Path $directory $part.name
            if ((Get-Item -LiteralPath $path).Length -ne $part.bytes) { throw "Wrong part size: $path" }
            if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine $part.sha256) { throw "Corrupt part: $path" }
            Write-Host "Joining $($part.name)"
            $source = [IO.File]::OpenRead($path)
            try { $source.CopyTo($destination) } finally { $source.Dispose() }
        }
    } finally { $destination.Dispose() }
    if ((Get-Item -LiteralPath $temporary).Length -ne $info.bytes) { throw 'Wrong combined size' }
    if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ine $info.sha256) { throw 'Combined SHA256 mismatch' }
    [IO.File]::Move($temporary, $output)
    Write-Host "Complete: $output"
    Write-Host 'Copy this .tacmap file to the phone, then import it in Tac_Nav settings.'
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
