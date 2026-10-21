[CmdletBinding()]
param([string]$ComposeProject, [switch]$AsJson)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '启停工具.ps1')
Assert-LifecycleHost
Import-LifecycleEnvironment $PSScriptRoot
$parameters=@{ComposeProject=(Resolve-LifecycleProject $PSScriptRoot $ComposeProject)}
$config=Get-LifecycleConfiguration $PSScriptRoot $parameters -IgnorePorts
$state=Read-LifecycleState $config.statePath $config.projectRoot $config.composeProject
if (-not $state) {
    if ($AsJson) { @{healthy=$false;status='not-tracked';composeProject=$config.composeProject} | ConvertTo-Json -Compress } else { Write-Host '本实例无启停状态；未查询或接管其他服务。' }
    exit 1
}
$ports=$state.ports
$checks=[Collections.Generic.List[object]]::new()
foreach ($name in @('rag','backend','frontend')) {
    $identity=$state.processes | Where-Object name -EQ $name | Select-Object -First 1
    $healthy=[bool](Test-ProcessIdentity $identity)
    if ($healthy) {
        $portKey=@{rag='RagPort';backend='BackendPort';frontend='FrontendPort'}[$name]
        try { Assert-LifecyclePort $ports[$portKey] $identity } catch { $healthy=$false }
    }
    $checks.Add(@{name="$name-process";healthy=$healthy})
}
$docker=(Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
foreach ($service in @('mysql','redis','etcd','minio','milvus')) {
    $identity=$state.containers | Where-Object service -EQ $service | Select-Object -First 1
    $healthy=$false
    if ($docker -and (Test-ContainerIdentity $identity $docker)) {
        $raw=& $docker inspect $identity.id 2>$null
        if ($LASTEXITCODE -eq 0) {
            $container=@($raw | ConvertFrom-Json)[0]
            $healthy=$container.State.Running -and ($service -eq 'milvus' -or $container.State.Health.Status -eq 'healthy')
        }
    }
    $checks.Add(@{name="$service-container";healthy=[bool]$healthy})
}
$probes=@{
    'milvus-http'={ (Invoke-WebRequest -NoProxy "http://127.0.0.1:$($ports.MilvusHealthPort)/healthz" -TimeoutSec 5).StatusCode -eq 200 }
    'rag-diagnostics'={
        $health=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.RagPort)/health" -TimeoutSec 5
        $diagnostic=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.RagPort)/v2/diagnostics" -Headers @{'X-Internal-Token'=$env:AI_RAG_INTERNAL_TOKEN} -TimeoutSec 5
        $health.status -eq 'ok' -and $health.instance_id -eq $state.instanceId -and -not $health.degraded -and $diagnostic.backend -eq 'milvus-hybrid' -and $diagnostic.embedding_loaded -and $diagnostic.model.name -eq 'BAAI/bge-small-zh-v1.5' -and $diagnostic.dimension -eq 512 -and @($diagnostic.model.onnx_files).Count -gt 0 -and -not $diagnostic.degraded -and (Test-LifecycleAuthorization "http://127.0.0.1:$($ports.RagPort)/v2/diagnostics")
    }
    'backend-http'={
        $api=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.BackendPort)/v2/api-docs" -TimeoutSec 5
        $api.swagger -eq '2.0' -and $null -ne $api.paths.'/ai/daily/overview' -and (Test-LifecycleAuthorization "http://127.0.0.1:$($ports.BackendPort)/ai/daily/overview")
    }
    'frontend-proxy'={
        $page=Invoke-WebRequest -NoProxy "http://127.0.0.1:$($ports.FrontendPort)/" -TimeoutSec 5
        $api=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.FrontendPort)/api/v2/api-docs" -TimeoutSec 5
        $page.StatusCode -eq 200 -and $page.Content -match '<div id="app"' -and $api.swagger -eq '2.0' -and $null -ne $api.paths.'/ai/daily/overview' -and (Test-LifecycleAuthorization "http://127.0.0.1:$($ports.FrontendPort)/api/ai/daily/overview")
    }
}
foreach ($name in $probes.Keys) {
    try { $healthy=[bool](& $probes[$name]) } catch { $healthy=$false }
    $checks.Add(@{name=$name;healthy=$healthy})
}
$result=@{healthy=(@($checks | Where-Object {-not $_.healthy}).Count -eq 0);composeProject=$config.composeProject;url="http://127.0.0.1:$($ports.FrontendPort)";checks=$checks.ToArray();logDirectory=(Join-Path $PSScriptRoot ".runtime/logs/$($config.composeProject)")}
if ($AsJson) { $result | ConvertTo-Json -Depth 8 -Compress } else { $checks | ForEach-Object { [pscustomobject]$_ } | Format-Table -AutoSize; Write-Host "日志：$($result.logDirectory)" }
if (-not $result.healthy) { exit 1 }
