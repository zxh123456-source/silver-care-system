$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$targetDir = Join-Path $projectRoot ".runtime\ocr\tessdata"
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

$chiSim = Join-Path $targetDir "chi_sim.traineddata"
if (-not (Test-Path -LiteralPath $chiSim)) {
    Invoke-WebRequest `
        -Uri "https://github.com/tesseract-ocr/tessdata_fast/raw/main/chi_sim.traineddata" `
        -OutFile $chiSim -UseBasicParsing -TimeoutSec 60
}

$eng = Join-Path $targetDir "eng.traineddata"
$systemEngCandidates = @(
    "C:\Program Files\Tesseract-OCR\tessdata\eng.traineddata",
    "C:\Program Files (x86)\Tesseract-OCR\tessdata\eng.traineddata"
)
if (-not (Test-Path -LiteralPath $eng)) {
    $systemEng = $systemEngCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($systemEng) {
        Copy-Item -LiteralPath $systemEng -Destination $eng
    }
}

if (-not (Test-Path -LiteralPath $eng)) {
    Write-Warning "未找到 eng.traineddata；中文 OCR 仍可使用，但中英文混排识别效果会受影响。"
}

$chiSize = (Get-Item -LiteralPath $chiSim).Length
if ($chiSize -lt 2000000) {
    throw "chi_sim.traineddata 下载不完整（当前 $chiSize 字节）。"
}
Write-Host "中文 OCR 语言数据已准备：$targetDir" -ForegroundColor Green
