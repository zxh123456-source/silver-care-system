$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $target) { throw '.env 已存在，保留现有密钥；请手动补齐缺失项，切勿直接覆盖数据卷使用的密码' }
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
$databaseSecret = New-LocalSecret
$ragSecret = New-LocalSecret
$values = @{
    MYSQL_ROOT_PASSWORD = $databaseSecret
    DB_PASSWORD = $databaseSecret
    REDIS_PASSWORD = New-LocalSecret
    JWT_SECRET = New-LocalSecret
    AI_RAG_INTERNAL_TOKEN = $ragSecret
    RAG_INTERNAL_TOKEN = $ragSecret
    MINIO_ROOT_PASSWORD = New-LocalSecret
    MILVUS_ROOT_PASSWORD = New-LocalSecret
}
$lines = foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.example') -Encoding UTF8) {
    if ($line -match '^([A-Z_]+)=') {
        $name = $matches[1]
        if ($values.ContainsKey($name)) { "$name=$($values[$name])" } else { $line }
    } else { $line }
}
[IO.File]::WriteAllLines($target, [string[]]$lines, (New-Object Text.UTF8Encoding $false))
Write-Host '已生成本地随机密钥到 .env（Git 已忽略）。已有演示 AES 账号需另外配置历史 IV/密钥。'
