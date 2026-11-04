[CmdletBinding()]
param([string]$ComposeProject)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '启停工具.ps1')
Assert-LifecycleHost
# Stop never loads credentials/local scripts; ownership survives missing configuration.
$parameters=@{ComposeProject=(Resolve-LifecycleProject $PSScriptRoot $ComposeProject)}
$config=Get-LifecycleConfiguration -ProjectRoot $PSScriptRoot -Parameters $parameters -IgnorePorts
$lifecycleLock=Enter-LifecycleLock $config.statePath
try {
    if (-not (Test-Path -LiteralPath $config.statePath)) { Write-Host '无本项目启停状态，未停止任何进程或容器。'; return }
    $state=Read-LifecycleState $config.statePath $config.projectRoot $config.composeProject
    foreach ($identity in @($state.processes | Sort-Object { if ($_.name -like '*-logger') { 1 } else { 0 } })) { Stop-OwnedProcess $identity }
    if (@($state.containers).Count) {
        $docker=(Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        & $docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Docker 不可用；进程已停止，容器状态记录保留，请恢复 Docker 后重试停止。' }
        foreach ($identity in $state.containers) { Stop-OwnedContainer $identity $docker }
    }
    Remove-Item -LiteralPath $config.statePath -Force
    Write-Host '停止完成；仅处理身份匹配的本项目进程和容器，数据卷保留。' -ForegroundColor Green
} finally { $lifecycleLock.ReleaseMutex(); $lifecycleLock.Dispose() }
