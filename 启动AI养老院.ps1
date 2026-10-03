$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$runtimeDir = Join-Path $projectRoot ".runtime"
$logDir = Join-Path $runtimeDir "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$localConfig = Join-Path $projectRoot "本地配置.ps1"
if (Test-Path -LiteralPath $localConfig) {
    . $localConfig
}

function Test-Port([int]$port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Test-TcpEndpoint([int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect("127.0.0.1", $port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($result)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

if (-not (Test-Port 3306)) {
    throw "MySQL 3306 端口未启动，请先启动 MySQL。"
}

if (-not (Test-Port 6379)) {
    $redisPath = Join-Path $projectRoot "redis\Redis-x64-3.2.100\redis-server.exe"
    $redisProcess = Start-Process -FilePath $redisPath -WorkingDirectory (Split-Path $redisPath) -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $runtimeDir "redis.pid") -Value $redisProcess.Id
}

$dockerPath = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$milvusCompose = Join-Path $projectRoot "源码\beadhouse-ai\docker-compose.milvus.yml"
if ((Test-Path -LiteralPath $dockerPath) -and (Test-Path -LiteralPath $milvusCompose)) {
    $env:PATH = "C:\Program Files\Docker\Docker\resources\bin;" + $env:PATH
    try {
        & $dockerPath info --format "{{.ServerVersion}}" | Out-Null
        if (-not (Test-TcpEndpoint 19530)) {
            & $dockerPath compose -f $milvusCompose up -d | Out-Host
            for ($attempt = 0; $attempt -lt 30 -and -not (Test-TcpEndpoint 19530); $attempt++) {
                Start-Sleep -Seconds 1
            }
        }
    } catch {
        Write-Warning "Docker/Milvus 未就绪，RAG 将使用本地混合检索。"
    }
}

$env:RAG_BACKEND = if (Test-TcpEndpoint 19530) { "milvus" } else { "memory" }

# OCR 只在有 Tesseract 和语言数据时自动启用。相对路径从后端工作目录解析，可避免 Windows 中文路径导致 Tesseract 无法读取数据。
$ocrDataDir = Join-Path $runtimeDir "ocr\tessdata"
$ocrCommandCandidates = @(
    "C:\Program Files\Tesseract-OCR\tesseract.exe",
    "C:\Program Files (x86)\Tesseract-OCR\tesseract.exe"
)
$ocrCommand = $ocrCommandCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if ($ocrCommand -and (Test-Path -LiteralPath (Join-Path $ocrDataDir "chi_sim.traineddata"))) {
    $env:AI_OCR_ENABLED = "true"
    $env:AI_OCR_COMMAND = $ocrCommand
    $env:AI_OCR_LANGUAGE = "chi_sim+eng"
    $env:AI_OCR_DATA_DIR = "..\..\.runtime\ocr\tessdata"
} else {
    $env:AI_OCR_ENABLED = "false"
    Remove-Item Env:AI_OCR_COMMAND -ErrorAction SilentlyContinue
    Remove-Item Env:AI_OCR_LANGUAGE -ErrorAction SilentlyContinue
    Remove-Item Env:AI_OCR_DATA_DIR -ErrorAction SilentlyContinue
    Write-Warning "未检测到完整中文 OCR 配置；扫描 PDF/图片上传会提示安装 Tesseract 和 chi_sim 语言数据。"
}

$ragPython = Join-Path $projectRoot "源码\beadhouse-ai\.venv\Scripts\python.exe"
if ((Test-Path -LiteralPath $ragPython) -and -not (Test-Port 8001)) {
    $ragDir = Join-Path $projectRoot "源码\beadhouse-ai"
    $env:NO_PROXY = "127.0.0.1,localhost"
    $env:no_proxy = $env:NO_PROXY
    $ragProcess = Start-Process -FilePath $ragPython `
        -ArgumentList "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8001" `
        -WorkingDirectory $ragDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir "rag.log") `
        -RedirectStandardError (Join-Path $logDir "rag.err.log")
    Set-Content -LiteralPath (Join-Path $runtimeDir "rag.pid") -Value $ragProcess.Id
}

$javaCandidates = @(
    "C:\Program Files\Java\jdk1.8.0_201\bin\java.exe",
    "C:\Program Files\Java\jre1.8.0_201\bin\java.exe"
)
$javaPath = $javaCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $javaPath) {
    throw "未找到兼容的 JDK 8，请安装 JDK 8 或修改脚本中的 javaCandidates。"
}

if (-not (Test-Port 9001)) {
    $backendDir = Join-Path $projectRoot "源码\beadhouse-backend"
    $backendProcess = Start-Process -FilePath $javaPath `
        -ArgumentList "-jar", "target\beadhouse-backend-0.0.1-SNAPSHOT.jar", "--server.port=9001" `
        -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir "backend.log") `
        -RedirectStandardError (Join-Path $logDir "backend.err.log")
    Set-Content -LiteralPath (Join-Path $runtimeDir "backend.pid") -Value $backendProcess.Id
}

if (-not (Test-Port 8080)) {
    $frontendDir = Join-Path $projectRoot "源码\beadhouse-frontend"
    $frontendProcess = Start-Process -FilePath "C:\Program Files\nodejs\npm.cmd" `
        -ArgumentList "run", "dev", "--", "--port", "8080" `
        -WorkingDirectory $frontendDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir "frontend.log") `
        -RedirectStandardError (Join-Path $logDir "frontend.err.log")
    Set-Content -LiteralPath (Join-Path $runtimeDir "frontend.pid") -Value $frontendProcess.Id
}

Start-Sleep -Seconds 10
$status = @(6379, 8001, 9001, 8080) | ForEach-Object {
    [PSCustomObject]@{ Port = $_; Listening = Test-Port $_ }
}
$status | Format-Table -AutoSize

if (($status | Where-Object { $_.Port -in @(6379, 9001, 8080) }).Listening -contains $false) {
    throw "部分服务启动失败，请检查 $logDir 下的日志。"
}

if (-not (Test-Port 8001)) {
    Write-Warning "FastAPI RAG 服务未启动，制度问答会自动使用本地检索。"
}

Write-Host "系统已启动：http://127.0.0.1:8080" -ForegroundColor Green
