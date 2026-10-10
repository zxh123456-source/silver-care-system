function Assert-ProjectSecret([string]$name, [int]$minimumLength = 1) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    $defaults = @('123456', 'minioadmin', 'redis-dev-password', 'local-dev-token', 'Milvus', 'local-dev-jwt-secret-change-before-use-2026')
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -lt $minimumLength -or $defaults -contains $value -or $value -match '^(replace-|change-|请填写)') {
        throw "请在 .env 或环境变量中配置有效的 $name（不输出密钥值）"
    }
}

function Assert-ProjectEnvironment {
    foreach ($name in @('MYSQL_ROOT_PASSWORD', 'DB_PASSWORD', 'REDIS_PASSWORD', 'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD')) { Assert-ProjectSecret $name }
    Assert-ProjectSecret 'JWT_SECRET' 32
    Assert-ProjectSecret 'AI_RAG_INTERNAL_TOKEN' 32
    Assert-ProjectSecret 'MILVUS_ROOT_PASSWORD' 8
    if ($env:MILVUS_ROOT_PASSWORD -notmatch '^[A-Za-z0-9_-]+$') { throw 'MILVUS_ROOT_PASSWORD 仅支持字母、数字、下划线和短横线' }
    if (-not $env:RAG_INTERNAL_TOKEN) { $env:RAG_INTERNAL_TOKEN = $env:AI_RAG_INTERNAL_TOKEN }
    if ($env:RAG_INTERNAL_TOKEN -cne $env:AI_RAG_INTERNAL_TOKEN) { throw 'RAG_INTERNAL_TOKEN 必须与 AI_RAG_INTERNAL_TOKEN 一致' }
    if (-not $env:RAG_MILVUS_TOKEN) { $env:RAG_MILVUS_TOKEN = 'root:' + $env:MILVUS_ROOT_PASSWORD }
    if ($env:LEGACY_AES_IV -or $env:LEGACY_AES_KEY) {
        $ivBytes = [Text.Encoding]::UTF8.GetByteCount([string]$env:LEGACY_AES_IV)
        $keyBytes = [Text.Encoding]::UTF8.GetByteCount([string]$env:LEGACY_AES_KEY)
        if ($ivBytes -ne 16 -or $keyBytes -notin @(16,24,32)) { throw '历史 AES 配置要求 IV 为16字节，密钥为16、24或32字节' }
    }
    if ($env:PASSWORD_RESET_EMAIL_ENABLED -eq 'true') {
        foreach ($name in @('MAIL_HOST', 'MAIL_ADDRESS', 'MAIL_PASSWORD')) { Assert-ProjectSecret $name }
    }
    if ($env:AI_PROVIDER_ENABLED -eq 'true') { Assert-ProjectSecret 'AI_PROVIDER_API_KEY' }
}
