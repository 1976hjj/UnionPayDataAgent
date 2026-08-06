$ErrorActionPreference = "Stop"

$projectJarPath = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "target\payment-analysis.jar")
)
$targetPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "target"))
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
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaPath)

if ([string]::IsNullOrWhiteSpace($env:LLM_MOCK_ENABLED)) {
    $env:LLM_MOCK_ENABLED = "false"
}
if ([string]::IsNullOrWhiteSpace($env:SMARTBI_MOCK_ENABLED)) {
    $env:SMARTBI_MOCK_ENABLED = "true"
}
$env:DEBUG = "false"
if ([string]::IsNullOrWhiteSpace($env:LLM_API_KEY)) {
    $savedApiKey = [Environment]::GetEnvironmentVariable("LLM_API_KEY", "User")
    if (-not [string]::IsNullOrWhiteSpace($savedApiKey)) {
        $env:LLM_API_KEY = $savedApiKey
    }
}
$llmMockEnabled = @("true", "1", "yes", "on") -contains ([string]$env:LLM_MOCK_ENABLED).ToLowerInvariant()
if (-not $llmMockEnabled -and [string]::IsNullOrWhiteSpace($env:LLM_API_KEY)) {
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

function Stop-ExistingProjectProcess {
    $processes = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object {
            $_.CommandLine -and
            $_.CommandLine.IndexOf(
                $projectJarPath,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -ge 0
        }
    foreach ($process in $processes) {
        Write-Host "Existing payment-analysis process found, PID $($process.ProcessId). Stopping it..."
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    }
    foreach ($attempt in 1..20) {
        $remaining = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object {
                $_.CommandLine -and
                $_.CommandLine.IndexOf(
                    $projectJarPath,
                    [System.StringComparison]::OrdinalIgnoreCase
                ) -ge 0
            }
        if (-not $remaining) {
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw "The existing payment-analysis process could not be stopped."
}

function Clear-TargetReadOnlyAttributes {
    if (-not (Test-Path -LiteralPath $targetPath)) {
        return
    }
    $projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\'
    if (-not $targetPath.StartsWith($projectRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify attributes outside the project directory: $targetPath"
    }
    $items = @((Get-Item -LiteralPath $targetPath -Force)) + @(
        Get-ChildItem -LiteralPath $targetPath -Recurse -Force -ErrorAction Stop
    )
    $readOnlyItems = @($items | Where-Object {
        ($_.Attributes -band [System.IO.FileAttributes]::ReadOnly) -ne 0
    })
    foreach ($item in $readOnlyItems) {
        $item.Attributes = $item.Attributes -bxor [System.IO.FileAttributes]::ReadOnly
    }
    if ($readOnlyItems.Count -gt 0) {
        Write-Host "Cleared read-only attributes from $($readOnlyItems.Count) target item(s)."
    }
}

function Build-Project {
    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mavenCommand) {
        throw "Maven was not found. Add mvn to PATH before running this script."
    }
    Write-Host "Building payment-analysis with Maven..."
    & $mavenCommand.Source clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $projectJarPath)) {
        throw "Maven completed but the application JAR was not generated: $projectJarPath"
    }
    Write-Host "Maven build completed."
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

Stop-ExistingProjectProcess
Clear-AppPort
Clear-TargetReadOnlyAttributes
Build-Project
$jarPath = (Resolve-Path -LiteralPath $projectJarPath).Path

$redisCommand = Get-Command redis-server -ErrorAction SilentlyContinue
$redisPath = if ($redisCommand) { $redisCommand.Source } else {
    Get-ChildItem `
        -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\taizod1024.redis-windows-fork*\*\redis-server.exe" `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
$redisProcess = $null
$redisMemoryEnabled = -not (@("false", "0", "no", "off") -contains ([string]$env:CHAT_MEMORY_REDIS_ENABLED).ToLowerInvariant())
if ($redisMemoryEnabled -and -not (Test-RedisPort)) {
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
} elseif ($redisMemoryEnabled) {
    Write-Host "Redis is already available on port 6379."
} else {
    Write-Warning "Redis memory is disabled. The application will use temporary in-process conversations."
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
    $ready = $false
    foreach ($attempt in 1..120) {
        $appProcess.Refresh()
        if ($appProcess.HasExited) {
            throw "payment-analysis exited before it became ready. Exit code: $($appProcess.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/health" -TimeoutSec 2
            if ($health.status -eq "UP") {
                $ready = $true
                break
            }
        } catch {
            # The server is still starting.
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        throw "payment-analysis did not become healthy within 60 seconds."
    }
    Write-Host "payment-analysis is ready: http://localhost:8080"
    while (-not $appProcess.HasExited) {
        Start-Sleep -Milliseconds 500
        $appProcess.Refresh()
    }
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
