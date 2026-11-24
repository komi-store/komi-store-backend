#!/usr/bin/env bash
set -euo pipefail

# GitHub Store Backend — deploy to production VPS
# Usage: ./deploy.sh <server-ip>

SERVER_IP="${1:?Usage: ./deploy.sh <server-ip>}"
SSH_USER="root"
REMOTE_DIR="/opt/github-store-backend"

echo "==> Deploying to $SERVER_IP..."

# Sync project files to VPS (excludes dev/build artifacts)
rsync -avz --delete \
    --exclude '.git/' \
    --exclude '.gradle/' \
    --exclude 'build/' \
    --exclude '.idea/' \
    --exclude '.env' \
    --exclude 'docker-compose.override.yml' \
    --exclude '.DS_Store' \
    --exclude '.claude/' \
    ./ "$SSH_USER@$SERVER_IP:$REMOTE_DIR/"

echo "==> Building and starting services..."
ssh "$SSH_USER@$SERVER_IP" "cd $REMOTE_DIR && docker compose -f docker-compose.prod.yml up -d --build"

# Images are built on the server, so every deploy adds build cache; unpruned it
# reached 42 GB. Keep the 10 GB used most recently (least recently used goes
# first) so the next build still reuses the base-image and Gradle dependency
# layers. --max-used-space checked against the server's Docker 29.4 / buildx
# 0.33. A failed prune must not fail the deploy.
echo "==> Pruning build cache..."
ssh "$SSH_USER@$SERVER_IP" "docker builder prune -f --max-used-space 10gb" \
    || echo "WARN: build cache prune failed" >&2

# Caddyfile is mounted as a volume, so changes on disk don't auto-reload the
# running Caddy process. Gracefully reload if the file changed since last deploy.
# (Reload, not restart — avoids a TLS reconnect blip for live traffic.)
echo "==> Reloading Caddy config..."
ssh "$SSH_USER@$SERVER_IP" "docker compose -f $REMOTE_DIR/docker-compose.prod.yml exec -T caddy caddy reload --config /etc/caddy/Caddyfile || docker compose -f $REMOTE_DIR/docker-compose.prod.yml restart caddy"

# Poll /v1/health until it returns 200, with a hard 60s ceiling.
# A failing curl must fail the deploy script — the previous `|| echo`
# version swallowed the non-zero exit and reported "Deploy complete" on
# unhealthy boots.
echo "==> Waiting for health check..."
for i in $(seq 1 30); do
    if ssh "$SSH_USER@$SERVER_IP" "docker exec github-store-backend-app-1 curl -sf http://localhost:8080/v1/health" >/dev/null 2>&1; then
        echo "==> Healthy after $((i*2))s"
        echo "==> Deploy complete!"
        exit 0
    fi
    sleep 2
done
echo "Health check failed after 60s" >&2
exit 1
