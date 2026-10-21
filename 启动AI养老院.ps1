[CmdletBinding()]
param(
    [switch]$Prepare, [switch]$InitializeDemo, [switch]$AcknowledgeDemoOnly,
    [string]$ComposeProject,
    [int]$MysqlPort, [int]$RedisPort, [int]$MilvusPort, [int]$MilvusHealthPort,
    [int]$RagPort, [int]$BackendPort, [int]$FrontendPort,
    [ValidateRange(1,3600)][int]$TimeoutSeconds=300
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '启停工具.ps1')
Assert-LifecycleHost
if ($InitializeDemo -and -not $AcknowledgeDemoOnly) { throw '-InitializeDemo 必须同时明确提供 -AcknowledgeDemoOnly，仅允许初始化未修改过的原始演示账户。' }
if ($Prepare -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.env'))) { & (Join-Path $PSScriptRoot '初始化本地配置.ps1') }
Import-LifecycleEnvironment $PSScriptRoot
$config = Get-LifecycleConfiguration -ProjectRoot $PSScriptRoot -Parameters $PSBoundParameters
$projectRoot=$config.projectRoot; $ports=$config.ports
$logDir = Join-Path $projectRoot ".runtime/logs/$($config.composeProject)"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$lifecycleLock=Enter-LifecycleLock $config.statePath
try {
    . (Join-Path $projectRoot '校验环境.ps1')
    Assert-ProjectEnvironment
    $docker = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $node = (Get-Command $(if ($env:SILVERCARE_NODE) { $env:SILVERCARE_NODE } else { 'node' }) -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $java = if ($env:SILVERCARE_JAVA) { $env:SILVERCARE_JAVA } elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source }
    if (-not (Test-Path -LiteralPath $java)) { throw 'JAVA_HOME/SILVERCARE_JAVA 未指向有效 JDK。' }
    & $docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 未就绪。' }
    & $docker compose version --short 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '需要 Docker Compose v2。' }
    $env:COMPOSE_PROJECT_NAME=$config.composeProject
    $env:MYSQL_PORT=[string]$ports.MysqlPort; $env:REDIS_PORT=[string]$ports.RedisPort
    $env:MILVUS_PORT=[string]$ports.MilvusPort; $env:MILVUS_HEALTH_PORT=[string]$ports.MilvusHealthPort
    $env:RAG_PORT=[string]$ports.RagPort; $env:BACKEND_PORT=[string]$ports.BackendPort; $env:FRONTEND_PORT=[string]$ports.FrontendPort
    $env:DB_URL="jdbc:mysql://127.0.0.1:$($ports.MysqlPort)/db_beadhouse?useSSL=false&useUnicode=true&characterEncoding=utf-8&allowMultiQueries=true&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
    $env:SPRING_REDIS_HOST='127.0.0.1'; $env:SPRING_REDIS_PORT=[string]$ports.RedisPort
    $env:AI_RAG_URL="http://127.0.0.1:$($ports.RagPort)"; $env:RAG_MILVUS_URI="http://127.0.0.1:$($ports.MilvusPort)"
    $env:RAG_BACKEND='milvus'; $env:RAG_EMBEDDING_BACKEND='fastembed'; $env:RAG_EMBEDDING_MODEL='BAAI/bge-small-zh-v1.5'; $env:RAG_DENSE_DIMENSION='512'
    $env:SILVERCARE_BACKEND_URL="http://127.0.0.1:$($ports.BackendPort)"
    $env:NO_PROXY='127.0.0.1,localhost'; $env:no_proxy=$env:NO_PROXY
    if (-not $env:DB_USERNAME) { $env:DB_USERNAME='root' }
    # Preserve the existing local OCR discovery; an explicit configuration wins.
    if (-not $env:AI_OCR_ENABLED) {
        $ocrData=Join-Path $projectRoot '.runtime/ocr/tessdata'
        $ocrCandidates=@((Get-Command tesseract -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source)
        if ($env:ProgramFiles) { $ocrCandidates+=Join-Path $env:ProgramFiles 'Tesseract-OCR/tesseract.exe' }
        if (${env:ProgramFiles(x86)}) { $ocrCandidates+=Join-Path ${env:ProgramFiles(x86)} 'Tesseract-OCR/tesseract.exe' }
        $ocr=$ocrCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
        if ($ocr -and (Test-Path -LiteralPath (Join-Path $ocrData 'chi_sim.traineddata'))) {
            $env:AI_OCR_ENABLED='true'; $env:AI_OCR_COMMAND=$ocr; $env:AI_OCR_LANGUAGE='chi_sim+eng'; $env:AI_OCR_DATA_DIR='..\..\.runtime\ocr\tessdata'
        } else { $env:AI_OCR_ENABLED='false' }
    }
    if ($config.composeProject -ne 'silver-care-system') {
        $dataDirectory=Join-Path $projectRoot ".runtime/data/$($config.composeProject)"
        New-Item -ItemType Directory -Force -Path $dataDirectory | Out-Null
        $env:RAG_DATA_FILE=Join-Path $dataDirectory 'policy_documents.json'
    }
    $fingerprint=Get-LifecycleFingerprint $projectRoot
    $composeArguments=@('compose','--project-name',$config.composeProject,'--project-directory',$projectRoot,'-f',(Join-Path $projectRoot 'docker-compose.yml'))
    & $docker @composeArguments config --quiet 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置校验失败；请检查 .env 和 compose 文件（未打印密钥）。' }
    $state=Read-LifecycleState -Path $config.statePath -ProjectRoot $projectRoot -ComposeProject $config.composeProject
    if (-not $state) { $state=@{schemaVersion=1;projectRoot=$projectRoot;composeProject=$config.composeProject;ports=$ports;instanceId=[Guid]::NewGuid().ToString('N');processes=@();containers=@()} }
    $liveProcesses=@($state.processes | Where-Object { Test-ProcessIdentity $_ })
    $existingContainers=@(Get-LifecycleContainers $docker $config.composeProject $projectRoot)
    $runningBefore=@($existingContainers | Where-Object running | ForEach-Object id)
    if ($liveProcesses.Count -or $runningBefore.Count) {
        foreach ($key in $ports.Keys) { if ($state.ports[$key] -ne $ports[$key]) { throw '已有本项目服务运行；修改端口前请用原 ComposeProject 停止。' } }
        if ($state.configurationFingerprint -ne $fingerprint) { throw '正在运行的实例配置与当前配置不一致；请先停止再启动，未修改原有服务。' }
    }
    foreach ($container in $existingContainers) {
        if ($container.running -and @($state.containers | Where-Object id -EQ $container.id).Count -ne 1) { throw '发现未由启停状态登记的运行容器；请使用另一个 ComposeProject，不能接管现有服务。' }
    }
    foreach ($name in @('rag','backend','frontend')) {
        $key=@{rag='RagPort';backend='BackendPort';frontend='FrontendPort'}[$name]
        $identity=$liveProcesses | Where-Object name -EQ $name | Select-Object -First 1
        Assert-LifecyclePort $ports[$key] $identity
    }
    foreach ($entry in @(@('mysql','MysqlPort',3306),@('redis','RedisPort',6379),@('milvus','MilvusPort',19530),@('milvus','MilvusHealthPort',9091))) {
        $container=$existingContainers | Where-Object { $_.service -eq $entry[0] -and $_.running } | Select-Object -First 1
        $owned=$false
        if ($container) {
            $owned=Test-ContainerPublishedPort $container $docker $ports[$entry[1]] $entry[2]
            if (-not $owned) { throw '现有容器端口映射与本实例配置不一致。' }
        }
        Assert-LifecyclePort -Port $ports[$entry[1]] -OwnedContainer $owned
    }
    $aiDir=Join-Path $projectRoot '源码/beadhouse-ai'; $backendDir=Join-Path $projectRoot '源码/beadhouse-backend'; $frontendDir=Join-Path $projectRoot '源码/beadhouse-frontend'
    $python=Get-WindowsToolPath (Join-Path $aiDir '.venv/Scripts/python.exe'); $jar=Join-Path $backendDir 'target/beadhouse-backend-0.0.1-SNAPSHOT.jar'
    $vite=Join-Path $frontendDir 'node_modules/vite/bin/vite.js'
    Invoke-LifecycleCommand $java @('-version') $backendDir (Join-Path $logDir 'java-version.log')
    $javaVersion=(Get-Content -LiteralPath (Join-Path $logDir 'java-version.log') -Raw) + (Get-Content -LiteralPath (Join-Path $logDir 'java-version.log.stderr') -Raw)
    if ($javaVersion -notmatch 'version "1\.8\.') { throw '本项目要求 JDK 8；请设置 JAVA_HOME 或 SILVERCARE_JAVA。' }
    $env:JAVA_HOME=Split-Path (Split-Path $java)
    if ($Prepare) {
        if ($liveProcesses.Count) { throw 'Prepare 需要应用已停止，避免覆盖运行中的依赖或 JAR。' }
        if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { throw 'Prepare 要求完整 JDK 8（含 javac），不能使用 JRE。' }
        Write-Host "准备后端 JAR；日志：$(Join-Path $logDir 'prepare-maven.log')"
        $maven=(Get-Command $(if ($env:SILVERCARE_MAVEN) { $env:SILVERCARE_MAVEN } else { 'mvn.cmd' }) -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        $mavenCmd=Join-Path $logDir 'prepare-maven.cmd'
        [IO.File]::WriteAllText($mavenCmd, ('@call "' + $maven + '" package -DskipTests' + [Environment]::NewLine), [Text.Encoding]::Default)
        Invoke-LifecycleCommand $env:ComSpec @('/d','/c',$mavenCmd) $backendDir (Join-Path $logDir 'prepare-maven.log')
        Write-Host "安装前端依赖；日志：$(Join-Path $logDir 'prepare-npm.log')"
        $npm=(Get-Command npm.cmd -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        $npmCli=Join-Path (Split-Path $npm) 'node_modules/npm/bin/npm-cli.js'
        if (-not (Test-Path -LiteralPath $npmCli)) { throw '无法定位 npm-cli.js，请安装包含 npm 的 Node.js。' }
        Invoke-LifecycleCommand $node @($npmCli,'ci') $frontendDir (Join-Path $logDir 'prepare-npm.log')
        Write-Host "准备 Python 3.12 和 AI 依赖；日志：$(Join-Path $logDir 'prepare-pip.log')"
        if (-not (Test-Path -LiteralPath $python)) {
            $basePython=(Get-Command $(if ($env:SILVERCARE_PYTHON) { $env:SILVERCARE_PYTHON } else { 'py' }) -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
            $prefix=if ([IO.Path]::GetFileNameWithoutExtension($basePython) -eq 'py') { @('-3.12') } else { @() }
            Invoke-LifecycleCommand $basePython ($prefix + @('-c','import sys; assert sys.version_info[:2] == (3,12), "Python 3.12 required"')) $aiDir (Join-Path $logDir 'prepare-python-base.log')
            Invoke-LifecycleCommand $basePython ($prefix + @('-m','venv',(Join-Path $aiDir '.venv'))) $aiDir (Join-Path $logDir 'prepare-venv.log')
            $python=Get-WindowsToolPath (Join-Path $aiDir '.venv/Scripts/python.exe')
        }
        Invoke-LifecycleCommand $python @('-c','import sys; assert sys.version_info[:2] == (3,12), "Python 3.12 required"') $aiDir (Join-Path $logDir 'prepare-python.log')
        try { Invoke-LifecycleCommand $python @('-m','pip','install','-r',(Join-Path $aiDir 'requirements.txt')) $aiDir (Join-Path $logDir 'prepare-pip.log') } catch {
            throw "AI 依赖安装失败：$($_.Exception.Message) 如日志出现 Windows 路径过长，请将项目放在更短路径（保留本项目现有数据）；脚本会优先使用可用的工具短路径，不修改系统设置。"
        }
    }
    foreach ($artifact in @($python,$jar,$vite)) { if (-not (Test-Path -LiteralPath $artifact)) { throw '缺少构建产物或依赖；请先使用 -Prepare。' } }
    Invoke-LifecycleCommand $python @('-c','import sys, uvicorn, fastembed, pymilvus; assert sys.version_info[:2] == (3,12), "Python 3.12 required"') $aiDir (Join-Path $logDir 'python-check.log')
    $state.processes=$liveProcesses; $state.ports=$ports; $state.configurationFingerprint=$fingerprint
    Save-LifecycleState $state $config.statePath
    $newProcesses=[Collections.Generic.List[object]]::new(); $newContainers=@()
    $saveStateCheckpoint=${function:Save-LifecycleState}
    $checkpointIdentity={
        param($identity)
        if (@($state.processes | Where-Object { $_.pid -eq $identity.pid -and $_.name -eq $identity.name }).Count -eq 0) {
            $state.processes=@($state.processes) + @($identity)
            $newProcesses.Add($identity)
            & $saveStateCheckpoint $state $config.statePath
        }
    }.GetNewClosure()
    try {
        Write-Host "启动并检查基础设施；日志：$(Join-Path $logDir 'compose.log')"
        Start-LifecycleInfrastructure $state $config.statePath $docker $composeArguments (Join-Path $logDir 'compose.log')
        $newContainers=@($state.containers | Where-Object { $_.running -and $_.id -notin $runningBefore })
        foreach ($service in @('mysql','redis','etcd','minio')) {
            $id=($state.containers | Where-Object service -EQ $service).id
            if (-not $id) { throw "缺少 $service 容器。" }
            Wait-LifecycleCondition -TimeoutSeconds $TimeoutSeconds -Description "$service 容器健康" -Condition {
                $health=& $docker inspect --format '{{.State.Health.Status}}' $id 2>$null
                $LASTEXITCODE -eq 0 -and $health -eq 'healthy'
            }
        }
        Wait-LifecycleCondition -TimeoutSeconds $TimeoutSeconds -Description 'Milvus HTTP' -Condition { (Invoke-WebRequest -NoProxy "http://127.0.0.1:$($ports.MilvusHealthPort)/healthz" -TimeoutSec 5).StatusCode -eq 200 }
        Wait-LifecycleCondition -TimeoutSeconds $TimeoutSeconds -Description 'Milvus 认证连接' -Condition {
            & $python -c 'import os; from pymilvus import MilvusClient; c=MilvusClient(uri=os.environ["RAG_MILVUS_URI"],token=os.environ["RAG_MILVUS_TOKEN"],timeout=5); c.list_collections(timeout=5); c.close()' 2>$null | Out-Null
            $LASTEXITCODE -eq 0
        }
        $mysqlId=($state.containers | Where-Object service -EQ 'mysql').id
        & (Join-Path $projectRoot '检查数据库.ps1') -Container $mysqlId
        if ($InitializeDemo) { & (Join-Path $projectRoot '初始化演示账号.ps1') -UseCurrentEnvironment }
        $uploadDir=Join-Path $projectRoot ".runtime/uploads/$($config.composeProject)"
        New-Item -ItemType Directory -Force -Path $uploadDir | Out-Null
        foreach ($name in @('rag','backend','frontend')) {
            if (@($state.processes | Where-Object name -EQ $name).Count) { continue }
            Write-Host "启动 $name；日志目录：$logDir"
            $env:RAG_INSTANCE_ID=$state.instanceId
            $launch=switch ($name) {
                rag { @{file=$python;arguments=@('-m','uvicorn','app.main:app','--app-dir',$aiDir,'--host','127.0.0.1','--port',[string]$ports.RagPort);directory=$aiDir} }
                backend { @{file=$java;arguments=@('-jar',$jar,"--server.port=$($ports.BackendPort)",'--server.address=127.0.0.1',"--filesave.windows=$uploadDir","--filesave.linux=$uploadDir");directory=$backendDir} }
                frontend { @{file=$node;arguments=@($vite,'--host','127.0.0.1','--port',[string]$ports.FrontendPort);directory=$frontendDir} }
            }
            Start-LifecycleProcess $name $launch.file $launch.arguments $launch.directory $projectRoot $logDir -OnIdentity $checkpointIdentity | Out-Null
        }
        Write-Host '等待真实 HTTP、BGE 模型、Milvus 和前端代理健康检查。'
        Wait-LifecycleCondition -TimeoutSeconds $TimeoutSeconds -Description 'RAG BGE + Milvus 认证诊断' -Condition {
            $health=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.RagPort)/health" -TimeoutSec 5
            $diagnostic=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.RagPort)/v2/diagnostics" -Headers @{'X-Internal-Token'=$env:RAG_INTERNAL_TOKEN} -TimeoutSec 5
            $health.status -eq 'ok' -and $health.instance_id -eq $state.instanceId -and -not $health.degraded -and $diagnostic.backend -eq 'milvus-hybrid' -and $diagnostic.embedding_loaded -and $diagnostic.embedding_backend -eq 'BAAI/bge-small-zh-v1.5' -and $diagnostic.dimension -eq 512 -and $diagnostic.model.name -eq 'BAAI/bge-small-zh-v1.5' -and @($diagnostic.model.onnx_files).Count -gt 0 -and -not $diagnostic.degraded -and (Test-LifecycleAuthorization "http://127.0.0.1:$($ports.RagPort)/v2/diagnostics")
        }
        Wait-LifecycleCondition -TimeoutSeconds $TimeoutSeconds -Description '后端 HTTP API' -Condition {
            $api=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.BackendPort)/v2/api-docs" -TimeoutSec 5
            $api.swagger -eq '2.0' -and $null -ne $api.paths.'/ai/daily/overview' -and (Test-LifecycleAuthorization "http://127.0.0.1:$($ports.BackendPort)/ai/daily/overview")
        }
        Wait-LifecycleCondition -TimeoutSeconds $TimeoutSeconds -Description '前端页面及后端代理' -Condition {
            $page=Invoke-WebRequest -NoProxy "http://127.0.0.1:$($ports.FrontendPort)/" -TimeoutSec 5
            $api=Invoke-RestMethod -NoProxy "http://127.0.0.1:$($ports.FrontendPort)/api/v2/api-docs" -TimeoutSec 5
            $page.StatusCode -eq 200 -and $page.Content -match '<div id="app"' -and $api.swagger -eq '2.0' -and $null -ne $api.paths.'/ai/daily/overview' -and (Test-LifecycleAuthorization "http://127.0.0.1:$($ports.FrontendPort)/api/ai/daily/overview")
        }
        foreach ($name in @('rag','backend','frontend')) {
            $identity=$state.processes | Where-Object name -EQ $name | Select-Object -First 1
            if (-not (Test-ProcessIdentity $identity)) { throw "$name 进程身份已变化。" }
            $key=@{rag='RagPort';backend='BackendPort';frontend='FrontendPort'}[$name]
            Assert-LifecyclePort $ports[$key] $identity
        }
        Write-Host "系统已通过健康检查：http://127.0.0.1:$($ports.FrontendPort)；日志：$logDir" -ForegroundColor Green
    } catch {
        $newContainers=@($state.containers | Where-Object { $_.running -and $_.id -notin $runningBefore })
        foreach ($identity in @($newProcesses | Sort-Object { if ($_.name -like '*-logger') { 1 } else { 0 } })) { try { Stop-OwnedProcess $identity } catch { Write-Warning '本次新建进程回滚失败，请使用停止脚本重试。' } }
        foreach ($identity in $newContainers) { try { Stop-OwnedContainer $identity $docker } catch { Write-Warning '本次新启动容器回滚失败，请使用停止脚本重试。' } }
        $state.processes=@($state.processes | Where-Object { Test-ProcessIdentity $_ })
        Save-LifecycleState $state $config.statePath
        throw "启动失败：$($_.Exception.Message) 日志：$logDir；原有运行项及数据卷已保留。"
    }
} finally { if ($lifecycleLock) { $lifecycleLock.ReleaseMutex(); $lifecycleLock.Dispose() } }
