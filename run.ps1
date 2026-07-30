$ErrorActionPreference = "Stop"

$jarPath = (Resolve-Path -LiteralPath "$PSScriptRoot\target\payment-analysis.jar").Path
$javaHomePath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { $null }
$adoptiumJavaPaths = Get-ChildItem `
    -Path "$env:ProgramFiles\Eclipse Adoptium\jdk-*\bin\java.exe" `
    -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -ExpandProperty FullName
$pathJava = (Get-Command java -ErrorAction SilentlyContinue).Source
$javaCandidates = @($javaHomePath) + @($adoptiumJavaPaths) + @($pathJava) |
    Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
    Select-Object -Unique

function Get-JavaMajorVersion([string]$candidate) {
    $versionCheck = New-Object System.Diagnostics.ProcessStartInfo
    $versionCheck.FileName = $candidate
    $versionCheck.Arguments = "-version"
    $versionCheck.UseShellExecute = $false
    $versionCheck.RedirectStandardError = $true
    $versionCheck.CreateNoWindow = $true
    $versionProcess = [System.Diagnostics.Process]::Start($versionCheck)
    $versionText = $versionProcess.StandardError.ReadToEnd()
    $versionProcess.WaitForExit()
    if ($versionText -match 'version "(?<major>\d+)(?:\.(?<minor>\d+))?') {
        $major = [int]$Matches.major
        return $(if ($major -eq 1) { [int]$Matches.minor } else { $major })
    }
    return 0
}

$javaPath = $javaCandidates |
    Where-Object { (Get-JavaMajorVersion $_) -ge 17 } |
    Select-Object -First 1
if (-not $javaPath) {
    throw "Java 17 or newer was not found. Set JAVA_HOME to a compatible JDK."
}

$env:LLM_MOCK_ENABLED = "false"
if ([string]::IsNullOrWhiteSpace($env:LLM_API_KEY)) {
    $savedApiKey = [Environment]::GetEnvironmentVariable("LLM_API_KEY", "User")
    if (-not [string]::IsNullOrWhiteSpace($savedApiKey)) {
        $env:LLM_API_KEY = $savedApiKey
    }
}
if ([string]::IsNullOrWhiteSpace($env:LLM_API_KEY)) {
    throw "LLM_API_KEY is not configured in the Windows user environment."
}

function Clear-AppPort {
    $listeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    $ownerIds = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($ownerId in $ownerIds) {
        if ($ownerId -le 4) {
            throw "Port 8080 is occupied by protected system process PID $ownerId."
        }
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId" -ErrorAction SilentlyContinue
        Write-Host "Port 8080 is occupied by $($owner.Name) PID $ownerId. Stopping it..."
        Stop-Process -Id $ownerId -Force -ErrorAction Stop
    }
    foreach ($attempt in 1..20) {
        if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
            if ($ownerIds.Count -gt 0) { Start-Sleep -Milliseconds 500 }
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw "Port 8080 could not be released."
}

function Test-RedisPort {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connection = $client.ConnectAsync("127.0.0.1", 6379)
        return $connection.Wait(500) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

Clear-AppPort

$redisCommand = Get-Command redis-server -ErrorAction SilentlyContinue
$redisPath = if ($redisCommand) { $redisCommand.Source } else {
    Get-ChildItem `
        -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\taizod1024.redis-windows-fork*\*\redis-server.exe" `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
$redisProcess = $null
if (-not (Test-RedisPort)) {
    if (-not $redisPath) {
        throw "Redis is not available. Install it with: winget install taizod1024.redis-windows-fork"
    }
    $redisDataPath = Join-Path $env:LOCALAPPDATA "UnionPayDataAgent\redis"
    New-Item -ItemType Directory -Path $redisDataPath -Force | Out-Null
    $redisArguments = @(
        "--bind", "127.0.0.1", "--protected-mode", "yes", "--port", "6379",
        "--appendonly", "yes", "--dir", "`"$redisDataPath`""
    )
    $redisProcess = Start-Process -FilePath $redisPath `
        -ArgumentList $redisArguments `
        -WorkingDirectory (Split-Path -Parent $redisPath) `
        -WindowStyle Hidden `
        -PassThru
    foreach ($attempt in 1..20) {
        if (Test-RedisPort) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-RedisPort)) {
        Stop-Process -Id $redisProcess.Id -Force -ErrorAction SilentlyContinue
        throw "Redis failed to start on port 6379."
    }
    Write-Host "Local Redis started, PID: $($redisProcess.Id)"
} else {
    Write-Host "Redis is already available on port 6379."
}

$appProcess = $null

try {
    $appProcess = Start-Process -FilePath $javaPath `
        -ArgumentList @("-jar", "`"$jarPath`"") `
        -WorkingDirectory $PSScriptRoot `
        -NoNewWindow `
        -PassThru

    Write-Host "payment-analysis started, PID: $($appProcess.Id)"
    Write-Host "Press Ctrl+C to stop the application and release the JAR file."
    $appProcess.WaitForExit()
} finally {
    if ($appProcess -and -not $appProcess.HasExited) {
        Write-Host "`nStopping payment-analysis..."
        Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
        $appProcess.WaitForExit()
    }
    if ($redisProcess -and -not $redisProcess.HasExited) {
        Write-Host "Stopping local Redis..."
        Stop-Process -Id $redisProcess.Id -Force -ErrorAction SilentlyContinue
        $redisProcess.WaitForExit()
    }
    Write-Host "Application stopped. The JAR file lock has been released."
}
