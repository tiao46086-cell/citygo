# Import sql/citygo-demo.sql into the existing Compose MySQL volume.
# Backup first, then import, delete shop cache, and restart the app.
# Does not delete the citygo-mysql volume, seckill keys, or login tokens.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$backupDir = Join-Path $root "backup"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupFile = Join-Path $backupDir "citygo-$stamp.sql"

Write-Host "backup mysql to $backupFile"
docker compose exec -T mysql sh -c "mysqldump -uroot -p`"`$MYSQL_ROOT_PASSWORD`" --single-transaction --default-character-set=utf8mb4 hmdp > /tmp/citygo-backup.sql"
if ($LASTEXITCODE -ne 0) { throw "mysqldump failed" }
docker compose cp "mysql:/tmp/citygo-backup.sql" $backupFile
if ($LASTEXITCODE -ne 0) { throw "copy backup failed" }

Write-Host "import sql/citygo-upgrade.sql"
docker compose cp "sql/citygo-upgrade.sql" "mysql:/tmp/citygo-upgrade.sql"
if ($LASTEXITCODE -ne 0) { throw "copy upgrade sql failed" }
docker compose exec -T mysql sh -c "mysql -uroot -p`"`$MYSQL_ROOT_PASSWORD`" --default-character-set=utf8mb4 hmdp < /tmp/citygo-upgrade.sql"
if ($LASTEXITCODE -ne 0) { throw "import upgrade sql failed" }

Write-Host "import sql/citygo-demo.sql"
docker compose cp "sql/citygo-demo.sql" "mysql:/tmp/citygo-demo.sql"
if ($LASTEXITCODE -ne 0) { throw "copy demo sql failed" }
docker compose exec -T mysql sh -c "mysql -uroot -p`"`$MYSQL_ROOT_PASSWORD`" --default-character-set=utf8mb4 hmdp < /tmp/citygo-demo.sql"
if ($LASTEXITCODE -ne 0) { throw "import demo sql failed" }

Write-Host "delete shop cache"
docker compose exec -T redis sh -c "redis-cli --scan --pattern 'cache:shop:*' | xargs -r redis-cli DEL"
if ($LASTEXITCODE -ne 0) { throw "delete shop cache failed" }
docker compose exec -T redis sh -c "redis-cli --scan --pattern 'lock:shop:*' | xargs -r redis-cli DEL"
if ($LASTEXITCODE -ne 0) { throw "delete shop lock failed" }

Write-Host "restart app"
docker compose restart app
if ($LASTEXITCODE -ne 0) { throw "restart app failed" }
Write-Host "done"
