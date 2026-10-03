$projectRoot = $PSScriptRoot
$runtimeDir = Join-Path $projectRoot ".runtime"

function Get-ChildProcessIds([int]$parentId) {
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $parentId" -ErrorAction SilentlyContinue)
    $ids = @()
    foreach ($child in $children) {
        $ids += [int]$child.ProcessId
        $ids += Get-ChildProcessIds([int]$child.ProcessId)
    }
    return $ids
}

function Stop-TrackedProcess([string]$name, [string]$commandHint) {
    $pidFile = Join-Path $runtimeDir "$name.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { return }
    $rawPid = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    $processId = 0
    if (-not [int]::TryParse([string]$rawPid, [ref]$processId) -or $processId -le 0) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        return
    }
    $root = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if ($root -and $root.CommandLine -and $root.CommandLine -notlike "*$commandHint*") {
        Write-Warning "$name PID $processId 不是本项目进程，已跳过。"
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        return
    }
    $descendants = @(Get-ChildProcessIds $processId | Select-Object -Unique)
    [array]::Reverse($descendants)
    foreach ($childId in $descendants) {
        if (Get-Process -Id $childId -ErrorAction SilentlyContinue) {
            Stop-Process -Id $childId -Force -ErrorAction SilentlyContinue
        }
    }
    if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Write-Host "已停止 $name（进程树 $processId）"
    } elseif ($descendants.Count -gt 0) {
        Write-Host "已停止 $name 子进程（根 PID $processId 已退出）"
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

foreach ($name in @("frontend", "backend", "rag")) {
    $hint = switch ($name) {
        "frontend" { "beadhouse-frontend"; break }
        "backend" { "beadhouse-backend"; break }
        "rag" { "beadhouse-ai"; break }
    }
    Stop-TrackedProcess $name $hint
}

$dockerPath = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$infraCompose = Join-Path $projectRoot "docker-compose.yml"
if ((Test-Path -LiteralPath $dockerPath) -and (Test-Path -LiteralPath $infraCompose)) {
    $env:PATH = "C:\Program Files\Docker\Docker\resources\bin;" + $env:PATH
    try {
        & $dockerPath compose -f $infraCompose stop | Out-Host
        Write-Host "已停止 MySQL、Redis 和 Milvus 容器，数据卷仍保留。"
    } catch {
        Write-Warning "基础设施容器停止失败，请在 Docker Desktop 中检查。"
    }
}

Write-Host "停止完成。脚本只处理由启动脚本记录的进程和本项目 Docker 容器。" -ForegroundColor Green
