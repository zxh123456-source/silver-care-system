# Shared Docker selection and transport. The manifest lives in 数据库/migrations.tsv.
function Resolve-DatabaseContainer {
    param([string]$Container)
    $docker = (Get-Command docker -ErrorAction Stop).Source
    if (-not $Container) {
        $compose = Join-Path $PSScriptRoot 'docker-compose.yml'
        $ids = @(& $docker compose -f $compose ps -q mysql)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or -not $ids[0]) {
            throw '无法定位正在运行的 MySQL 容器；请检查 docker compose ps，或显式提供 -Container。'
        }
        $Container = $ids[0].Trim()
    }
    $id = & $docker inspect --format '{{.Id}}' -- $Container
    if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[a-f0-9]{64}$') {
        throw 'MySQL 容器不存在或无法唯一识别。'
    }
    return $id.Trim()
}

function Invoke-ProjectDatabase {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('inspect', 'upgrade')][string]$Mode,
        [string]$Container,
        [switch]$RetryFailed
    )
    $docker = (Get-Command docker -ErrorAction Stop).Source
    $containerId = Resolve-DatabaseContainer -Container $Container
    $remoteDirectory = '/tmp/silvercare-db-' + [Guid]::NewGuid().ToString('N')
    $source = Join-Path $PSScriptRoot '数据库/.'
    & $docker exec $containerId mkdir -p -- $remoteDirectory
    if ($LASTEXITCODE -ne 0) { throw '无法在 MySQL 容器内准备迁移工具目录。' }
    try {
        & $docker cp $source "${containerId}:$remoteDirectory"
        if ($LASTEXITCODE -ne 0) { throw '数据库工具复制失败。' }
        $arguments = @('exec', $containerId, 'bash', "$remoteDirectory/migrate.sh", $Mode)
        if ($RetryFailed) {
            if ($Mode -ne 'upgrade') { throw '-RetryFailed 仅适用于升级操作。' }
            $arguments += '--retry'
        }
        & $docker @arguments
        if ($LASTEXITCODE -ne 0) {
            throw '数据库版本检查或升级失败。请保留备份，依据版本状态与诊断处理；不要删除数据卷重试。'
        }
    } finally {
        # This invocation owns the fixed-prefix GUID directory, not any database data directory.
        if ($remoteDirectory -match '^/tmp/silvercare-db-[a-f0-9]{32}$') {
            & $docker exec $containerId rm -rf -- $remoteDirectory
        }
    }
}

function Invoke-ProjectPython {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$PythonPath
    )
    $candidates = if ($PythonPath) { @($PythonPath) } else {
        @($env:SILVERCARE_PYTHON,
          (Join-Path $PSScriptRoot '源码/beadhouse-ai/.venv/Scripts/python.exe'),
          (Join-Path $PSScriptRoot '源码/beadhouse-ai/.venv/bin/python'),
          'py', 'python3', 'python')
    }
    $python = $null
    $prefix = @()
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $command = Get-Command $candidate -CommandType Application -ErrorAction SilentlyContinue
        if (-not $command -or $command.Source -match '[\\/]Microsoft[\\/]WindowsApps[\\/]') { continue }
        $candidatePrefix = @()
        if ($command.Name -eq 'py.exe' -or $command.Name -eq 'py') { $candidatePrefix = @('-3') }
        & $command.Source @candidatePrefix -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' 2>$null
        if ($LASTEXITCODE -eq 0) {
            $python = $command.Source
            $prefix = $candidatePrefix
            break
        }
    }
    if (-not $python) { throw '找不到可用的 Python 3.10+；请通过 -PythonPath 指定解释器完整路径。' }
    & $python @prefix -X utf8 @Arguments
    if ($LASTEXITCODE -ne 0) { throw "数据库工具执行失败（exit $LASTEXITCODE）；请按输出的日志定位。原备份不会被覆盖。" }
}
