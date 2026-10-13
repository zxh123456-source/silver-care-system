# Back up the existing database before running. Never import the destructive demo dump here.
$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'docker-compose.yml'
$docker = (Get-Command docker -ErrorAction Stop).Source
foreach ($name in @('ai_care_upgrade.sql', 'daily_task_upgrade.sql', 'care_alert_upgrade.sql')) {
    $source = Join-Path $PSScriptRoot "数据库/$name"
    $containerPath = '/tmp/silvercare-upgrade-' + [Guid]::NewGuid().ToString('N') + '.sql'
    & $docker compose -f $compose cp $source "mysql:$containerPath"
    if ($LASTEXITCODE -ne 0) { throw "复制迁移失败：$name" }
    try {
        # Container environment supplies the existing DB password. No password in host arguments.
        & $docker compose -f $compose exec -T mysql sh -c ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot db_beadhouse < ' + $containerPath)
        if ($LASTEXITCODE -ne 0) { throw "迁移失败：$name。DDL 不支持整体回滚；保留备份并修复报错后重试。" }
    } finally {
        & $docker compose -f $compose exec -T mysql rm -f $containerPath
    }
    Write-Host "已执行 $name"
}
