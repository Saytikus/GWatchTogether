[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ServerExe,
    [Parameter(Mandatory = $true)]
    [string] $EvidenceDirectory,
    [ValidateRange(5, 60)]
    [int] $ReadinessTimeoutSeconds = 20
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$serverPath = [IO.Path]::GetFullPath($ServerExe)
$evidencePath = [IO.Path]::GetFullPath($EvidenceDirectory)
if (!(Test-Path -LiteralPath $serverPath -PathType Leaf)) { throw 'ServerExe does not exist.' }
if ($serverPath.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ServerExe must be outside the repository checkout.'
}
if ($evidencePath.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'EvidenceDirectory must be outside the repository checkout.'
}
if (Test-Path -LiteralPath $evidencePath) {
    if (@(Get-ChildItem -LiteralPath $evidencePath -Force).Count -ne 0) {
        throw 'EvidenceDirectory must be new or empty.'
    }
} else {
    New-Item -ItemType Directory -Path $evidencePath | Out-Null
}
$workPath = Join-Path $evidencePath '.work'
New-Item -ItemType Directory -Path $workPath | Out-Null
$activeProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$rawLogs = [System.Collections.Generic.List[string]]::new()
$testResults = [System.Collections.Generic.List[object]]::new()
$testKey = $null
$testSecret = $null
$binaryHash = 'unavailable'
$capacityWarningObserved = $false
$script:falseSuccessObserved = $false

function Get-RandomHex([int] $ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint] $listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Get-FreeUdpPort {
    $udp = [Net.Sockets.UdpClient]::new([Net.IPEndPoint]::new([Net.IPAddress]::Loopback, 0))
    try { return ([Net.IPEndPoint] $udp.Client.LocalEndPoint).Port }
    finally { $udp.Dispose() }
}

function New-TestConfig([int] $ApiPort, [int] $UdpPort, [string] $Name) {
    $configPath = Join-Path $workPath "$Name.yaml"
    $yaml = @(
        "port: $ApiPort",
        'rtc:',
        '  tcp_port: 0',
        "  udp_port: $UdpPort",
        '  use_external_ip: false',
        '  node_ip: 127.0.0.1',
        '  enable_loopback_candidate: true',
        '  ips:',
        '    includes:',
        '      - 127.0.0.1/32',
        'keys:',
        "  $testKey`: $testSecret",
        'logging:',
        '  level: warn',
        '  json: true'
    ) -join "`n"
    [IO.File]::WriteAllText($configPath, $yaml + "`n", [Text.UTF8Encoding]::new($false))
    return $configPath
}

function Start-LiveKit([string] $ConfigPath, [string] $Name) {
    $stdoutPath = Join-Path $workPath "$Name.stdout.log"
    $stderrPath = Join-Path $workPath "$Name.stderr.log"
    $rawLogs.Add($stdoutPath)
    $rawLogs.Add($stderrPath)
    $args = @('--config', ('"{0}"' -f $ConfigPath), '--bind', '127.0.0.1')
    $process = Start-Process -FilePath $serverPath -ArgumentList $args -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $activeProcesses.Add($process)
    return $process
}

function Invoke-ShortProcess([string[]] $Arguments, [int] $TimeoutSeconds) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $serverPath
    $startInfo.Arguments = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
        $process.Kill()
        $process.WaitForExit()
        throw 'Short-lived LiveKit invocation exceeded its timeout.'
    }
    $process.WaitForExit()
    return [pscustomobject]@{
        ProcessId = $process.Id
        ExitCode = $process.ExitCode
        Stdout = $stdoutTask.Result
        Stderr = $stderrTask.Result
    }
}

function Test-Health([int] $ApiPort) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "http://127.0.0.1:$ApiPort/"
        return $response.StatusCode -eq 200
    } catch { return $false }
}

function Get-ProcessTcp([int] $ProcessId) {
    return @(Get-NetTCPConnection -OwningProcess $ProcessId -ErrorAction SilentlyContinue)
}

function Get-ProcessUdp([int] $ProcessId) {
    return @(Get-NetUDPEndpoint -OwningProcess $ProcessId -ErrorAction SilentlyContinue)
}

function Assert-LoopbackOnly([int] $ProcessId, [int] $ApiPort, [int] $UdpPort) {
    $tcp = Get-ProcessTcp $ProcessId
    $udp = Get-ProcessUdp $ProcessId
    if (@($tcp | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }).Count -ne 0) {
        throw 'The LiveKit process owns a non-loopback TCP endpoint.'
    }
    if (@($udp | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }).Count -ne 0) {
        throw 'The LiveKit process owns a non-loopback UDP endpoint.'
    }
    if (@($tcp | Where-Object { $_.LocalPort -eq $ApiPort -and $_.State -eq 'Listen' -and $_.LocalAddress -eq '127.0.0.1' }).Count -ne 1) {
        throw 'Expected loopback API listener is not owned by the LiveKit process.'
    }
    if (@($udp | Where-Object { $_.LocalPort -eq $UdpPort -and $_.LocalAddress -eq '127.0.0.1' }).Count -ne 1) {
        throw 'Expected loopback ICE UDP listener is not owned by the LiveKit process.'
    }
}

function Wait-Ready([System.Diagnostics.Process] $Process, [int] $ApiPort, [int] $UdpPort) {
    $deadline = [DateTime]::UtcNow.AddSeconds($ReadinessTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) { throw 'LiveKit exited before bounded health readiness.' }
        if (Test-Health $ApiPort) {
            Assert-LoopbackOnly $Process.Id $ApiPort $UdpPort
            return
        }
        Start-Sleep -Milliseconds 200
    }
    throw 'LiveKit did not pass bounded health readiness.'
}

function Stop-LiveKit([System.Diagnostics.Process] $Process, [int] $ApiPort, [int] $UdpPort) {
    $Process.Refresh()
    if (!$Process.HasExited) { Stop-Process -Id $Process.Id -Force }
    if (!$Process.WaitForExit(10000)) { throw 'LiveKit survived bounded process termination.' }
    Start-Sleep -Milliseconds 200
    if (@(Get-NetTCPConnection -LocalPort $ApiPort -ErrorAction SilentlyContinue |
        Where-Object OwningProcess -eq $Process.Id).Count -ne 0) {
        throw 'API port remained owned after process termination.'
    }
    if (@(Get-NetUDPEndpoint -LocalPort $UdpPort -ErrorAction SilentlyContinue |
        Where-Object OwningProcess -eq $Process.Id).Count -ne 0) {
        throw 'ICE port remained owned after process termination.'
    }
}

function Invoke-RecordedTest([string] $Name, [scriptblock] $Action) {
    try {
        $details = & $Action
        $testResults.Add([pscustomobject]@{ name = $Name; status = 'passed'; details = $details })
    } catch {
        $testResults.Add([pscustomobject]@{ name = $Name; status = 'failed'; details = $_.Exception.Message })
        throw
    }
}

$failureMessage = $null
try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'This harness requires native Windows.' }
    if ([Environment]::Is64BitOperatingSystem -ne $true) { throw 'This harness requires Windows x64.' }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this harness as an ordinary non-elevated user.'
    }
    $testKey = Get-RandomHex 16
    $testSecret = Get-RandomHex 32
    $version = Invoke-ShortProcess @('--version') 10
    if ($version.ExitCode -ne 0 -or $version.Stdout -notmatch 'version 1\.13\.7') {
        throw 'Server binary must report LiveKit Server v1.13.7.'
    }
    $binaryHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $serverPath).Hash.ToLowerInvariant()

    Invoke-RecordedTest 'repeated-loopback-start-health-stop' {
        $runs = [System.Collections.Generic.List[string]]::new()
        for ($i = 1; $i -le 3; $i++) {
            $apiPort = Get-FreeTcpPort
            $udpPort = Get-FreeUdpPort
            $configPath = New-TestConfig $apiPort $udpPort "cycle-$i"
            $process = Start-LiveKit $configPath "cycle-$i"
            try {
                Wait-Ready $process $apiPort $udpPort
                $runs.Add("cycle-$i health=200 tcp=127.0.0.1:$apiPort udp=127.0.0.1:$udpPort")
            } finally { Stop-LiveKit $process $apiPort $udpPort }
            $runs.Add("cycle-$i process=exited api-and-udp-ports=released")
        }
        return ,$runs.ToArray()
    }

    Invoke-RecordedTest 'occupied-api-port-is-terminal-even-with-zero-exit' {
        $apiPort = Get-FreeTcpPort
        $ownerUdpPort = Get-FreeUdpPort
        $contenderUdpPort = Get-FreeUdpPort
        while ($contenderUdpPort -eq $ownerUdpPort) { $contenderUdpPort = Get-FreeUdpPort }
        $ownerConfig = New-TestConfig $apiPort $ownerUdpPort 'occupied-owner'
        $contenderConfig = New-TestConfig $apiPort $contenderUdpPort 'occupied-contender'
        $owner = Start-LiveKit $ownerConfig 'occupied-owner'
        try {
            Wait-Ready $owner $apiPort $ownerUdpPort
            $attempt = Invoke-ShortProcess @('--config', $contenderConfig, '--bind', '127.0.0.1') 10
            if ($attempt.ExitCode -eq 0) { $script:falseSuccessObserved = $true }
            if ($attempt.Stdout + $attempt.Stderr -notmatch '(?i)bind|address already in use|only one usage') {
                throw 'Occupied API port did not produce a bind failure.'
            }
            if (!(Test-Health $apiPort)) { throw 'Original API owner lost health during port-conflict test.' }
            if (@(Get-NetTCPConnection -State Listen -LocalPort $apiPort -ErrorAction SilentlyContinue |
                Where-Object OwningProcess -eq $owner.Id).Count -ne 1) {
                throw 'Original process no longer owns the occupied API port.'
            }
            if (@(Get-NetUDPEndpoint -LocalPort $contenderUdpPort -ErrorAction SilentlyContinue).Count -ne 0) {
                throw 'Failed contender left an ICE UDP port behind.'
            }
            $contenderChildren = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($attempt.ProcessId)" -ErrorAction SilentlyContinue)
            if ($contenderChildren.Count -ne 0) { throw 'Failed contender left child processes.' }
            return [pscustomobject]@{
                contenderExitCode = $attempt.ExitCode
                startupBindErrorObserved = $true
                terminalFailureDetectedWithoutTrustingExitCode = $true
                ownerHealth = 200
                failedContenderUdpPortReleased = $true
                contenderChildrenAfter = $contenderChildren.Count
            }
        } finally { Stop-LiveKit $owner $apiPort $ownerUdpPort }
    }

    Invoke-RecordedTest 'malformed-config-early-failure-is-terminal-even-with-zero-exit' {
        $badConfig = Join-Path $workPath 'malformed.yaml'
        $apiPort = Get-FreeTcpPort
        $yaml = "port: $apiPort`ninvalid: [`n"
        [IO.File]::WriteAllText($badConfig, $yaml, [Text.UTF8Encoding]::new($false))
        $attempt = Invoke-ShortProcess @('--config', $badConfig, '--bind', '127.0.0.1') 10
        if ($attempt.ExitCode -eq 0) { $script:falseSuccessObserved = $true }
        if ($attempt.Stdout + $attempt.Stderr -notmatch 'could not parse config') {
            throw 'Malformed config did not report its parse error.'
        }
        $leftoverTcp = @(Get-NetTCPConnection -LocalPort $apiPort -ErrorAction SilentlyContinue |
            Where-Object OwningProcess -eq $attempt.ProcessId)
        $leftoverUdp = @(Get-NetUDPEndpoint -OwningProcess $attempt.ProcessId -ErrorAction SilentlyContinue)
        if ($leftoverTcp.Count -gt 0 -or $leftoverUdp.Count -gt 0) {
            throw 'Malformed config unexpectedly left owned network endpoints.'
        }
        $earlyFailureChildren = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($attempt.ProcessId)" -ErrorAction SilentlyContinue)
        if ($earlyFailureChildren.Count -ne 0) { throw 'Malformed config failure left child processes.' }
        return [pscustomobject]@{
            exitCode = $attempt.ExitCode
            parseErrorObserved = $true
            noOwnedEndpointsObserved = $true
            childProcessesAfter = $earlyFailureChildren.Count
            terminalFailureDetectedFromErrorAndNoReadiness = $true
        }
    }

    Invoke-RecordedTest 'forced-stop-cleans-process-and-loopback-ports' {
        $apiPort = Get-FreeTcpPort
        $udpPort = Get-FreeUdpPort
        $configPath = New-TestConfig $apiPort $udpPort 'forced-stop'
        $process = Start-LiveKit $configPath 'forced-stop'
        try {
            Wait-Ready $process $apiPort $udpPort
            $childrenBefore = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($process.Id)" -ErrorAction SilentlyContinue).Count
            Stop-LiveKit $process $apiPort $udpPort
            $childrenAfter = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($process.Id)" -ErrorAction SilentlyContinue).Count
            if ($childrenAfter -ne 0) { throw 'Unexpected exit left child processes.' }
            return [pscustomobject]@{
                apiAndUdpPortsReleased = $true
                childProcessesBefore = $childrenBefore
                childProcessesAfter = $childrenAfter
                stopMethod = 'harness-initiated force termination; unexpected process death is not tested here'
            }
        } finally { Stop-LiveKit $process $apiPort $udpPort }
    }

    $allLogs = foreach ($log in $rawLogs) {
        if (Test-Path -LiteralPath $log) { [IO.File]::ReadAllText($log) }
    }
    $capacityWarningObserved = ($allLogs -join "`n").Contains('CPU monitoring unsupported on current platform')
} catch {
    $failureMessage = $_.Exception.Message
} finally {
    foreach ($process in $activeProcesses) {
        try {
            $process.Refresh()
            if (!$process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
            $process.WaitForExit(10000) | Out-Null
        } catch { }
    }
}

$privacyFailure = $false
$index = 0
foreach ($log in $rawLogs) {
    if (Test-Path -LiteralPath $log) {
        $text = [IO.File]::ReadAllText($log)
        if ($text.Contains($testKey) -or $text.Contains($testSecret)) {
            $privacyFailure = $true
            $text = $text.Replace($testKey, '[REDACTED_TEST_KEY]').Replace($testSecret, '[REDACTED_TEST_SECRET]')
        }
        $name = [IO.Path]::GetFileNameWithoutExtension($log)
        [IO.File]::WriteAllText((Join-Path $evidencePath "$name.redacted.log"), $text, [Text.UTF8Encoding]::new($false))
    }
    $index++
}
Remove-Item -LiteralPath $workPath -Recurse -Force -ErrorAction SilentlyContinue

$testFailures = @($testResults | Where-Object status -ne 'passed').Count
$falseSuccessObserved = $script:falseSuccessObserved
$proofStatus = if ($failureMessage -or $testFailures -gt 0 -or $privacyFailure) {
    'FAILED'
} elseif ($falseSuccessObserved) {
    'BLOCKED_UPSTREAM_FALSE_SUCCESS_EXIT'
} else {
    'LIFECYCLE_ONLY'
}
$summary = [ordered]@{
    schema = 'gwatchtogether.p0-06-livekit-lifecycle-proof.v1'
    status = $proofStatus
    serverVersion = '1.13.7'
    binaryPath = $serverPath
    binarySha256 = $binaryHash
    apiAndRtcBind = '127.0.0.1 only; config restricts ICE UDP mux to 127.0.0.1/32'
    credentials = 'random ephemeral API key/secret; values not included in evidence'
    falseSuccessExitObserved = $falseSuccessObserved
    windowsCpuMonitoringUnsupportedObserved = $capacityWarningObserved
    tests = @($testResults)
    privacyFailure = $privacyFailure
    failure = $failureMessage
    notes = @(
        'A reported exit code is not accepted as readiness; process liveness, HTTP health, and owning loopback listeners are required.',
        'P0-06 is ACCEPTED NARROW for bounded loopback; upstream zero-exit startup failure remains unresolved. ' +
        'Any future hosted service must consume the safe lifecycle seam or fail closed.',
        'No RTC client/media, CPU capacity, LAN/WAN, firewall, or public-port behavior is proven.'
    )
}
$summaryPath = Join-Path $evidencePath 'lifecycle-summary.json'
[IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 8) + "`n", [Text.UTF8Encoding]::new($false))
Get-Content -LiteralPath $summaryPath
if ($proofStatus -eq 'FAILED') { exit 1 }
