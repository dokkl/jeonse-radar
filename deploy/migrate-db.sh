#!/usr/bin/env bash
# 로컬 MySQL(jeonse-mysql 컨테이너)의 데이터를 Lightsail 서버 DB로 통째로 옮긴다.
# 전국 백필을 서버에서 다시 돌리면 쿼터 때문에 며칠이 걸리므로 덤프로 옮긴다.
#
#   deploy/migrate-db.sh
#
# 서버 DB의 기존 테이블은 덮어써진다. 최초 1회 용도.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="${SSH_KEY:-$HOME/.ssh/jeonse-radar}"
HOST="${DEPLOY_HOST:-$(terraform -chdir="$ROOT/infra/terraform" output -raw static_ip)}"
REMOTE=/opt/jeonse-radar
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "ubuntu@$HOST")

read -r -p "서버($HOST) DB를 로컬 데이터로 덮어쓴다. 계속할까? [y/N] " ok
[ "$ok" = "y" ] || exit 1

echo "==> 서버 앱 중지 (적재 중 ddl-auto/스케줄러 간섭 방지)"
"${SSH[@]}" "cd $REMOTE && docker compose stop app && docker compose up -d --wait db"

echo "==> 덤프 → 전송 → 적재 (스트리밍)"
docker exec jeonse-mysql mysqldump -uroot -proot \
    --default-character-set=utf8mb4 --single-transaction --quick \
    --set-gtid-purged=OFF --no-tablespaces jeonse \
  | gzip -c \
  | "${SSH[@]}" "cd $REMOTE && gunzip -c | docker compose exec -T db sh -c 'mysql --default-character-set=utf8mb4 -ujeonse -p\"\$MYSQL_PASSWORD\" jeonse'"

echo "==> 앱 재기동"
"${SSH[@]}" "cd $REMOTE && docker compose up -d app && docker compose exec -T db sh -c 'mysql -ujeonse -p\"\$MYSQL_PASSWORD\" -N -e \"select count(*) from rent_deal\" jeonse'"
