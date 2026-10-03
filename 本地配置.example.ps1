# 复制为“本地配置.ps1”后填写。该文件已被 .gitignore 排除。
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "请填写本机数据库密码"
$env:JWT_SECRET = "请填写至少 32 字节的随机字符串"

# 仅用于兼容历史 AES 密码；新密码使用 BCrypt。
$env:LEGACY_AES_IV = "请填写16字节旧IV"
$env:LEGACY_AES_KEY = "请填写16字节旧密钥"

$env:MAIL_HOST = "smtp.example.com"
$env:MAIL_ADDRESS = "sender@example.com"
$env:MAIL_PASSWORD = "请填写邮箱授权码"

$env:AI_RAG_INTERNAL_TOKEN = "请填写随机内部令牌"
$env:RAG_INTERNAL_TOKEN = $env:AI_RAG_INTERNAL_TOKEN
