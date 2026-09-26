# Offline synthetic ZIP parser/writer exercise only. Not innoextract or installer containment.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not [Environment]::OSVersion.Platform.Equals([PlatformID]::Win32NT)) { throw 'Windows is required.' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class P008ScratchDirectory {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CreateDirectoryW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool Create(string path, IntPtr securityAttributes);
}
'@

function Assert-ExistingPlainDirectory([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $parts = [System.Collections.Generic.List[string]]::new()
    $cursor = $full
    while ($true) {
        $parts.Insert(0, $cursor)
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if (-not $parent -or $parent -eq $cursor) { break }
        $cursor = $parent
    }
    foreach ($part in $parts) {
        $item = Get-Item -LiteralPath $part -Force -ErrorAction Stop
        if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Missing, non-directory or reparse ancestor.'
        }
    }
}
function Assert-BeneathScratch([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith(($script:root + [IO.Path]::DirectorySeparatorChar),
            [StringComparison]::OrdinalIgnoreCase)) { throw 'Path outside owned scratch.' }
    $parent = [IO.Path]::GetDirectoryName($full)
    Assert-ExistingPlainDirectory $parent
    # Get-Item detects existing reparse nodes, including a junction at the final component.
    $item = Get-Item -LiteralPath $full -Force -ErrorAction SilentlyContinue
    if ($item) { throw 'Destination already exists (including reparse destination).' }
}
function New-ExclusiveDirectory([string] $Path) {
    Assert-BeneathScratch $Path
    if (-not [P008ScratchDirectory]::Create($Path, [IntPtr]::Zero)) {
        throw ('Exclusive directory creation failed: ' + [Runtime.InteropServices.Marshal]::GetLastWin32Error())
    }
    Assert-ExistingPlainDirectory $Path
    [void]$script:ownedDirectories.Add($Path)
}
function Get-Hash([byte[]] $Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($Bytes)).Replace('-', '') }
    finally { $sha.Dispose() }
}
function New-Fixture([object[]] $Entries) {
    $memory = [IO.MemoryStream]::new()
    $zip = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($spec in $Entries) {
            $entry = $zip.CreateEntry($spec.Name, [IO.Compression.CompressionLevel]::Optimal)
            if ($spec.Link) { $entry.ExternalAttributes = [int]0xA1FF0000 }
            $stream = $entry.Open()
            try { if ($spec.Bytes.Length) { $stream.Write($spec.Bytes, 0, $spec.Bytes.Length) } }
            finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    $bytes = $memory.ToArray()
    $memory.Dispose()
    if ($bytes.Length -ge 32768) { throw 'Synthetic compressed fixture exceeds 32 KiB.' }
    return ,$bytes
}
function New-Spec([string] $Name, [byte[]] $Bytes, [bool] $Link = $false) {
    return [pscustomobject]@{ Name = $Name; Bytes = $Bytes; Link = $Link }
}
function Get-BoundedInventory([IO.Compression.ZipArchive] $Archive) {
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $files = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $directories = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $inventory = [System.Collections.Generic.List[object]]::new()
    $total = 0L
    if ($Archive.Entries.Count -gt 8) { throw 'Entry count limit.' }
    foreach ($entry in $Archive.Entries) {
        $name = $entry.FullName
        if (-not $name -or $name.Contains('\') -or $name.StartsWith('/') -or
            $name -match '[\x00-\x1f:<>"|?*]' -or $name -match '^[A-Za-z]:') {
            throw 'Unsafe ZIP path.'
        }
        $directory = $name.EndsWith('/')
        $trimmed = $name.TrimEnd('/')
        $segments = $trimmed.Split('/')
        foreach ($segment in $segments) {
            if (-not $segment -or $segment -eq '.' -or $segment -eq '..' -or
                $segment.EndsWith('.') -or $segment.EndsWith(' ') -or
                $segment -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$') {
                throw 'Unsafe Windows path segment.'
            }
        }
        $unixType = ($entry.ExternalAttributes -shr 16) -band 0xF000
        if ($unixType -notin @(0, 0x4000, 0x8000) -or ($entry.ExternalAttributes -band 0x400)) {
            throw 'Symlink or reparse metadata.'
        }
        if ($directory -and ($entry.Length -ne 0 -or $unixType -eq 0x8000)) { throw 'Invalid directory.' }
        if (-not $directory -and $unixType -eq 0x4000) { throw 'Directory metadata on file.' }
        if (-not $seen.Add($trimmed)) { throw 'Case-insensitive duplicate.' }
        if ($directory) { [void]$directories.Add($trimmed) } else { [void]$files.Add($trimmed) }
        if ($entry.Length -gt 4096 -or $entry.CompressedLength -gt 8192) { throw 'Entry size limit.' }
        $total += $entry.Length
        if ($total -gt 8192) { throw 'Expanded total limit.' }
        if ($entry.Length -gt 32 * [Math]::Max(1L, $entry.CompressedLength)) { throw 'Compression ratio limit.' }
        [void]$inventory.Add([pscustomobject]@{ Entry = $entry; Name = $trimmed; Directory = $directory })
    }
    foreach ($item in $inventory) {
        $parts = $item.Name.Split('/')
        for ($i = 1; $i -lt $parts.Length; $i++) {
            $ancestor = [string]::Join('/', $parts[0..($i - 1)])
            if ($files.Contains($ancestor)) { throw 'File-directory collision.' }
        }
        if (-not $item.Directory) {
            foreach ($other in $inventory) {
                if ($other.Name.StartsWith(($item.Name + '/'), [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'File-directory collision.'
                }
            }
        }
    }
    return $inventory
}
function Invoke-Fixture([byte[]] $Bytes, [string] $Destination, [bool] $Write) {
    $stream = [IO.MemoryStream]::new($Bytes, $false)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
    try {
        # Phase 1: complete bounded inventory and destination preflight before creating any output.
        $inventory = Get-BoundedInventory $zip
        foreach ($item in $inventory) {
            $target = Join-Path $Destination ($item.Name.Replace('/', [IO.Path]::DirectorySeparatorChar))
            $cursor = $Destination
            foreach ($part in $item.Name.Split('/')) {
                $cursor = Join-Path $cursor $part
                $parent = [IO.Path]::GetDirectoryName($cursor)
                if (Get-Item -LiteralPath $parent -Force -ErrorAction SilentlyContinue) {
                    Assert-ExistingPlainDirectory $parent
                }
                $existing = Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue
                if ($existing -and ($existing.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                    throw 'Preplanted destination reparse point.'
                }
                if ($existing -and -not $existing.PSIsContainer) { throw 'Preplanted destination file.' }
            }
            if ($item.Directory -and $target -eq $Destination) { throw 'Invalid output.' }
        }
        if (-not $Write) { return }
        # Phase 2: bounded streaming extraction. Path-string checks are NOT race-free (TOCTOU).
        foreach ($item in $inventory) {
            $cursor = $Destination
            $parts = $item.Name.Split('/')
            for ($i = 0; $i -lt $parts.Length - 1; $i++) {
                $cursor = Join-Path $cursor $parts[$i]
                if (-not (Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue)) {
                    New-ExclusiveDirectory $cursor
                } else { Assert-ExistingPlainDirectory $cursor }
            }
            $target = Join-Path $cursor $parts[-1]
            if ($item.Directory) {
                if (-not (Get-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue)) {
                    New-ExclusiveDirectory $target
                } else { Assert-ExistingPlainDirectory $target }
                continue
            }
            Assert-BeneathScratch $target
            $source = $item.Entry.Open()
            try {
                $output = [IO.FileStream]::new($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
                    [IO.FileShare]::None)
                [void]$script:ownedFiles.Add($target)
                try {
                    $buffer = [byte[]]::new(512)
                    $count = 0L
                    while (($read = $source.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        $count += $read
                        if ($count -gt 4096 -or $count -gt $item.Entry.Length) { throw 'Streaming inflation limit.' }
                        $output.Write($buffer, 0, $read)
                    }
                    if ($count -ne $item.Entry.Length) { throw 'Truncated ZIP member.' }
                    $output.Flush($true)
                } finally { $output.Dispose() }
            } finally { $source.Dispose() }
            $actual = [IO.File]::ReadAllBytes($target)
            if ($actual.Length -gt 4096 -or (Get-Hash $actual) -ne $script:expectedHash) {
                throw 'Extracted bytes/hash differ from independent fixture expectation.'
            }
        }
    } finally { $zip.Dispose(); $stream.Dispose() }
}

$script:ownedDirectories = [System.Collections.Generic.List[string]]::new()
$script:ownedFiles = [System.Collections.Generic.List[string]]::new()
$script:root = $null
$passed = [System.Collections.Generic.List[string]]::new()
try {
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    Assert-ExistingPlainDirectory $temp
    $script:root = Join-Path $temp ('gwt-p008-fixture-' + [Guid]::NewGuid().ToString('N'))
    if (-not [P008ScratchDirectory]::Create($root, [IntPtr]::Zero)) { throw 'Exclusive scratch creation failed.' }
    [void]$ownedDirectories.Add($root)
    Assert-ExistingPlainDirectory $root
    $dest = Join-Path $root 'output'
    New-ExclusiveDirectory $dest
    $outside = Join-Path $root 'junction-target'
    New-ExclusiveDirectory $outside
    $text = [Text.UTF8Encoding]::new($false).GetBytes('fixture é: exact bytes')
    $script:expectedHash = Get-Hash $text
    $good = New-Fixture @((New-Spec 'clips/' ([byte[]]@())), (New-Spec 'clips/é space.txt' $text))
    Invoke-Fixture $good $dest $true
    $written = [IO.File]::ReadAllBytes((Join-Path $dest 'clips\é space.txt'))
    if ($written.Length -ne $text.Length -or (Get-Hash $written) -ne $expectedHash) {
        throw 'Positive fixture exact byte/hash failure.'
    }
    for ($i = 0; $i -lt $text.Length; $i++) {
        if ($written[$i] -ne $text[$i]) { throw 'Positive fixture byte mismatch.' }
    }
    [void]$passed.Add('positive UTF-8/space exact bytes and SHA-256')
    $badNames = @('../escape', '/rooted', 'C:/drive', 'x:ads', '//host/share', 'a\b',
        'a//b', 'a/./b', 'a/../b', 'a./x', 'a /x', 'CON.txt', 'LPT1', 'a?/x')
    foreach ($name in $badNames) {
        $fixture = New-Fixture @((New-Spec $name $text))
        $rejected = $false
        try { Invoke-Fixture $fixture $dest $false } catch {
            if ($_.Exception.Message -notin @('Unsafe ZIP path.', 'Unsafe Windows path segment.')) { throw }
            $rejected = $true
        }
        if (-not $rejected) { throw ('Unsafe name accepted: ' + $name) }
        [void]$passed.Add(('reject path: ' + $name))
    }
    $cases = @(
        @{ Label = 'case duplicate'; Error = 'Case-insensitive duplicate.'; Entries = @((New-Spec 'One' $text), (New-Spec 'one' $text)) },
        @{ Label = 'file-directory collision'; Error = 'File-directory collision.'; Entries = @((New-Spec 'file' $text), (New-Spec 'file/child' $text)) },
        @{ Label = 'symlink metadata'; Error = 'Symlink or reparse metadata.'; Entries = @((New-Spec 'link' $text $true)) },
        @{ Label = 'oversize entry'; Error = 'Entry size limit.'; Entries = @((New-Spec 'large' ([byte[]]::new(8192)))) },
        @{ Label = 'ratio'; Error = 'Compression ratio limit.'; Entries = @((New-Spec 'compressed' ([byte[]]::new(4096)))) }
    )
    foreach ($case in $cases) {
        $fixture = New-Fixture $case.Entries
        $rejected = $false
        try { Invoke-Fixture $fixture $dest $false } catch {
            if ($_.Exception.Message -ne $case.Error) { throw }
            $rejected = $true
        }
        if (-not $rejected) { throw ('Unsafe case accepted: ' + $case.Label) }
        [void]$passed.Add(('reject ' + $case.Label))
    }
    $many = @(1..9 | ForEach-Object { New-Spec ('entry' + $_) $text })
    $rejected = $false
    try { Invoke-Fixture (New-Fixture $many) $dest $false } catch {
        if ($_.Exception.Message -ne 'Entry count limit.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Entry count accepted.' }
    [void]$passed.Add('reject entry count')
    # Junction is wholly inside owned scratch. Failure to create it is a failed proof, not a skip/pass.
    $junction = Join-Path $dest 'link'
    Assert-BeneathScratch $junction
    $created = New-Item -ItemType Junction -Path $junction -Target $outside -ErrorAction Stop
    if (-not ($created.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        @($created.Target).Count -ne 1 -or
        [IO.Path]::GetFullPath([string]@($created.Target)[0]) -ne $outside) {
        throw 'Junction setup unsupported or target ambiguous.'
    }
    $rejected = $false
    try { Invoke-Fixture (New-Fixture @((New-Spec 'link/child' $text))) $dest $false } catch {
        if ($_.Exception.Message -ne 'Preplanted destination reparse point.') { throw }
        $rejected = $true
    }
    if (-not $rejected -or @(Get-ChildItem -LiteralPath $outside -Force).Count -ne 0) {
        throw 'Preplanted junction not rejected without outside write.'
    }
    [void]$passed.Add('reject preplanted junction (target unchanged)')
    # Remove only the exact verified junction node, never recurse through it.
    $linkItem = Get-Item -LiteralPath $junction -Force
    if (-not ($linkItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        @($linkItem.Target).Count -ne 1 -or
        [IO.Path]::GetFullPath([string]@($linkItem.Target)[0]) -ne $outside) {
        throw 'Junction identity changed.'
    }
    [IO.Directory]::Delete($junction)
    foreach ($path in $ownedFiles) {
        Assert-ExistingPlainDirectory ([IO.Path]::GetDirectoryName($path))
        $item = Get-Item -LiteralPath $path -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Owned file became reparse point.' }
        [IO.File]::Delete($path)
    }
    for ($i = $ownedDirectories.Count - 1; $i -ge 0; $i--) {
        Assert-ExistingPlainDirectory $ownedDirectories[$i]
        [IO.Directory]::Delete($ownedDirectories[$i], $false)
    }
    foreach ($label in $passed) { Write-Output ('PASS ' + $label) }
    Write-Output ('PASS scratch removed; tests=' + $passed.Count)
} catch {
    Write-Error ('STOP: ' + $_.Exception.Message + '; partial scratch (if created): ' + $root +
        '; no automatic cleanup on failure.') -ErrorAction Continue
    exit 1
}
