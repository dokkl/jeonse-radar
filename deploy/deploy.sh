#!/usr/bin/env bash
# jar를 빌드해 Lightsail 인스턴스로 올리고 docker compose로 (재)기동한다.
#
#   deploy/deploy.sh
#
# 전제: infra/terraform 에서 terraform apply 완료, deploy/.env.prod 작성 완료.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="${SSH_KEY:-$HOME/.ssh/jeonse-radar}"
HOST="${DEPLOY_HOST:-$(terraform -chdir="$ROOT/infra/terraform" output -raw static_ip)}"
REMOTE=/opt/jeonse-radar
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "ubuntu@$HOST")

[ -f "$ROOT/deploy/.env.prod" ] || { echo "deploy/.env.prod 가 없다. .env.prod.example 참고" >&2; exit 1; }

echo "==> 인스턴스 초기화(cloud-init) 완료 대기"
"${SSH[@]}" 'until [ -f /var/lib/cloud/instance/jeonse-radar-ready ]; do sleep 5; done'

echo "==> bootJar"
(cd "$ROOT" && ./gradlew --quiet bootJar)
JAR="$(ls "$ROOT"/build/libs/*.jar | grep -v -- '-plain.jar' | head -1)"

echo "==> 업로드 → $HOST"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$JAR" "$STAGE/app.jar"
cp "$ROOT/deploy/Dockerfile" "$ROOT/deploy/Caddyfile" "$STAGE/"
cp "$ROOT/deploy/docker-compose.prod.yml" "$STAGE/docker-compose.yml"
cp "$ROOT/deploy/.env.prod" "$STAGE/.env"
chmod 600 "$STAGE/.env"
rsync -az -e "ssh -i $KEY" "$STAGE/" "ubuntu@$HOST:$REMOTE/"

echo "==> docker compose up"
"${SSH[@]}" "cd $REMOTE && docker compose up -d --build --remove-orphans && docker image prune -f >/dev/null && docker compose ps"
