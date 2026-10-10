# Explicitly initialize ONLY the original imported demo administrator. Not a production password reset tool.
$ErrorActionPreference = 'Stop'
foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env') -Encoding UTF8) {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim('"').Trim("'"), 'Process')
    }
}
$localConfig = Join-Path $PSScriptRoot '本地配置.ps1'
if (Test-Path -LiteralPath $localConfig) { . $localConfig }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$jar = Join-Path $PSScriptRoot '源码/beadhouse-backend/target/beadhouse-backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw '请先在后端目录执行 mvn package' }
$runtime = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$bytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:DEMO_ACCOUNT_PASSWORD = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
# Persist before writing the database so a filesystem failure cannot lose the generated password.
$pending = Join-Path $runtime ('demo-accounts-' + [Guid]::NewGuid().ToString('N') + '.pending.txt')
try {
    [IO.File]::WriteAllLines($pending, @('仅本地虚构演示账号', '账号：13547584400', "密码：$env:DEMO_ACCOUNT_PASSWORD"), (New-Object Text.UTF8Encoding $false))
    $result = & $java '-Dloader.main=com.shanzhu.beadhouse.tools.DemoAccountInitializer' '-cp' $jar 'org.springframework.boot.loader.PropertiesLauncher' '--confirm-demo'
    if ($LASTEXITCODE -ne 0) { throw "初始化失败。候选凭据保留于 $pending；确认数据库状态后再处理。" }
    if ($result -contains 'DEMO_INITIALIZED') {
        $target = Join-Path $runtime 'demo-accounts.txt'
        Move-Item -LiteralPath $pending -Destination $target -Force
        Write-Host "原始演示管理员已转换为 BCrypt。登录凭据保存在 $target，请勿提交。"
    } elseif ($result -contains 'DEMO_UNCHANGED') {
        Remove-Item -LiteralPath $pending
        Write-Host '管理员密码已修改过，未覆盖；继续使用现有凭据。'
    } else { throw "未收到明确结果，候选凭据保留于 $pending" }
} finally {
    Remove-Item Env:DEMO_ACCOUNT_PASSWORD -ErrorAction SilentlyContinue
}
