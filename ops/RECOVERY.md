# Recovery runbook

Single-page disaster-recovery for the GitHub Store backend. The client app falls back to cached JSON on GitHub when this backend is down, so users keep working — but ranking, search-warming, and the auth/details proxies stop. Target restore time: under 60 minutes from a clean Hetzner box.

## Inventory

- VPS: Hetzner CPX31 or similar (4 vCPU / 8 GB), Ubuntu LTS, public IP `<VPS_IP>`.
- Off-host backups: Hetzner Storage Box, accessed via `rclone` remote `$RCLONE_REMOTE`. GPG-symmetric encrypted with `$BACKUP_GPG_PASSPHRASE`.
- Local backups: `/var/backups/postgres/backup-YYYY-MM-DD.dump` (7-day rolling).
- Code: `git@github.com:rainxchzed/github-store-backend.git` (or wherever `origin` points).
- Live `.env`: only on the VPS at `/opt/github-store-backend/.env`. **Also copied into the operator's password manager** — that copy is the source of truth during recovery.
- DNS: `api.github-store.org` (CDN-fronted) and `api-direct.github-store.org` (direct origin). Both A-records point at the VPS IP. The CDN has its own A-record pointing at Gcore.

## 1. The VPS is gone (full restore)

Roughly 30–45 minutes hands-on if backups are intact and the password-manager copy of `.env` is reachable.

1. **Provision a new Hetzner box** in the same datacentre. Note the new public IP.
2. **Update DNS**:
   - `api-direct.github-store.org` → new IP (immediate).
   - `api.github-store.org` → in the Gcore origin pull config, replace the origin host with the new IP. Keep the public DNS pointing at Gcore.
   - TTLs are 5 minutes; expect ~10 min before traffic shifts.
3. **Bootstrap the new box**:
   ```bash
   ssh root@<new-ip>
   apt update && apt install -y docker.io docker-compose-plugin git rclone gnupg curl
   systemctl enable --now docker
   mkdir -p /opt/github-store-backend
   ```
4. **Install rclone config** for the Storage Box. From your laptop:
   ```bash
   scp ~/.config/rclone/rclone.conf root@<new-ip>:/root/.config/rclone/rclone.conf
   ```
   (Or run `rclone config` on the box and re-add the `storage_box` remote with the same name.)
5. **Clone the code**:
   ```bash
   cd /opt
   git clone git@github.com:rainxchzed/github-store-backend.git github-store-backend-src
   cp -a github-store-backend-src/. github-store-backend/
   ```
6. **Restore `.env`** from your password manager into `/opt/github-store-backend/.env`. `chmod 600 /opt/github-store-backend/.env`. Confirm `RCLONE_REMOTE` and `BACKUP_GPG_PASSPHRASE` are present so the next backup runs end-to-end.
7. **Pull the latest dump**:
   ```bash
   set -a; . /opt/github-store-backend/.env; set +a
   cd /tmp
   rclone copy "$RCLONE_REMOTE" . --include "backup-*.dump.gpg" --max-age 2d
   LATEST=$(ls -1t backup-*.dump.gpg | head -1)
   gpg --batch --pinentry-mode loopback --passphrase "$BACKUP_GPG_PASSPHRASE" \
       --decrypt "$LATEST" > "${LATEST%.gpg}"
   ```
8. **Bring up Postgres + Meilisearch first** (so the dump can land before the app boots):
   ```bash
   cd /opt/github-store-backend
   docker compose -f docker-compose.prod.yml up -d postgres meilisearch
   docker compose -f docker-compose.prod.yml ps   # wait until both are healthy
   ```
9. **Restore the dump**:
   ```bash
   docker exec -i github-store-backend-postgres-1 \
       pg_restore -U githubstore -d githubstore --clean --if-exists \
       < /tmp/${LATEST%.gpg}
   ```
   Meilisearch rebuilds from Postgres on first sync, so no Meili dump is needed — the Python fetcher's `meili_sync.py` will repopulate it.

   `resource_cache` comes back empty: the dump holds its schema but not its rows (`--exclude-table-data` in `ops/backup_postgres.sh`). It holds cached upstream responses (GitHub and forge proxies such as `/repo`, `/releases`, `/readme`, `/user`, plus external-match results) and refills on demand as clients request them. Expect slower first loads and more GitHub API calls on those routes for a while; nothing else is lost.
10. **Start the app and Caddy**:
    ```bash
    docker compose -f docker-compose.prod.yml up -d --build
    curl -sf http://localhost:8080/v1/health   # in-container path differs; deploy.sh shows the canonical check
    ```
11. **Re-enable cron**: install the backup cron line from `ops/backup_postgres.sh` header, restore `/opt/fetcher/` if the Python fetcher lived on this box, re-link `/usr/local/bin/backup_postgres.sh`.
12. **Validate end-to-end**:
    - `curl https://api-direct.github-store.org/v1/health` returns 200 with `postgres: ok`, `meilisearch: ok`.
    - Once Gcore origin DNS has propagated: `curl https://api.github-store.org/v1/health` does the same.
    - Hit `/v1/internal/metrics` with `X-Admin-Token` to confirm workers are running.

## 2. `.env` is lost but the VPS is alive

If `/opt/github-store-backend/.env` was deleted or corrupted but Postgres/Meili volumes are intact, you reconstruct env vars in place. Restart the app once it's complete; you don't need to restore data.

| Var | How to get it back |
| --- | --- |
| `APP_ENV` | Just set `production`. |
| `DATABASE_URL`, `DATABASE_USER` | Static values. Copy from `.env.example`. |
| `POSTGRES_PASSWORD` | Already baked into the running postgres container's role. Read it back: `docker exec github-store-backend-postgres-1 env \| grep POSTGRES_PASSWORD`. Put the same value in `.env` so the new app container can connect. |
| `MEILI_URL` | Static. |
| `MEILI_MASTER_KEY` | Same trick — read from the running meilisearch container: `docker exec github-store-backend-meilisearch-1 env \| grep MEILI_MASTER_KEY`. |
| `GITHUB_OAUTH_CLIENT_ID` | github.com → Settings → Developer settings → OAuth Apps → "GitHub Store" → Client ID. (Not a secret — fine to recover.) |
| `GITHUB_TOKEN`, `GH_TOKEN_TRENDING`, `GH_TOKEN_NEW_RELEASES`, `GH_TOKEN_MOST_POPULAR`, `GH_TOKEN_TOPICS` | Cannot be recovered — GitHub does not show PAT values after creation. **Regenerate four fresh fine-grained PATs** at github.com → Settings → Developer settings → Personal access tokens → Fine-grained. Scope: public repo read-only. Update both `.env` here and the Python fetcher's env on `/opt/fetcher/`, since both consume the same pool. Set `GITHUB_TOKEN` to the same value as one of the four (typically `GH_TOKEN_TOPICS`) for the quiet-window fallback. |
| `ADMIN_TOKEN` | Arbitrary secret. Regenerate: `openssl rand -hex 32`. The dashboard URL needs the new value too. |
| `SENTRY_DSN` | sentry.io → Settings → Projects → github-store-backend → Client Keys (DSN). |
| `BADGE_USER_COUNT` | Whatever the current install base is (manually maintained). |
| `DEVICE_ID_PEPPER` | Arbitrary secret. Regenerate: `openssl rand -hex 32`. **This invalidates all existing hashed device_ids in the events table** — past events stay, but the same device after rotation hashes differently. Acceptable for analytics. |
| `RCLONE_REMOTE`, `BACKUP_GPG_PASSPHRASE`, `HEALTHCHECK_URL` | From the password manager (these are operator-only and never derivable from the running system). If the password manager is also gone, regenerate `BACKUP_GPG_PASSPHRASE` and accept that historical encrypted off-host backups become unrecoverable; new ones use the new passphrase. |
| `TOKEN_QUIET_START_UTC`, `TOKEN_QUIET_END_UTC`, `PORT`, `DATABASE_POOL_SIZE` | Defaults. Copy from `.env.example`. |

After rebuilding `.env`, `chmod 600 /opt/github-store-backend/.env`, then:

```bash
cd /opt/github-store-backend
docker compose -f docker-compose.prod.yml up -d --force-recreate app
docker compose -f docker-compose.prod.yml logs -f app | head -50
```

The `validateProductionEnv` check will fail loudly on missing required vars — that's by design.

## 3. One container is broken

App is crashlooping, or Meilisearch wedged after an OOM. The other containers are fine.

```bash
ssh root@<VPS_IP>
cd /opt/github-store-backend
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=200 <service>
docker compose -f docker-compose.prod.yml restart <service>
docker compose -f docker-compose.prod.yml logs -f <service>
```

If the app fails its healthcheck after restart, hit `/v1/health` from inside the container to see which dependency it's reporting as down:

```bash
docker exec github-store-backend-app-1 curl -sf http://localhost:8080/v1/health
```

Postgres restarts are usually fine (data is on the named volume). Meilisearch restarts from disk too; if its index is corrupt, blow away the `msdata` volume and the next `meili_sync.py` run will rebuild from Postgres.

## 4. Rollback to the previous image

The app image is built from source on each deploy with the implicit `:latest` tag. To get a one-step rollback, tag the running image as `:previous` *before* each deploy. Add this to your deploy workflow (or run by hand):

```bash
# Right before `docker compose ... up -d --build`:
ssh root@<VPS_IP> 'docker tag github-store-backend-app:latest github-store-backend-app:previous'
```

To roll back a bad deploy:

```bash
ssh root@<VPS_IP>
cd /opt/github-store-backend
docker tag github-store-backend-app:previous github-store-backend-app:latest
docker compose -f docker-compose.prod.yml up -d --no-build app
docker exec github-store-backend-app-1 curl -sf http://localhost:8080/v1/health
```

This swaps the image without rebuilding from source, so it's quick (seconds, not minutes) and survives a `git` state you don't trust. Caddy and the data containers are untouched. Once the rolled-back app is healthy, fix forward in a new branch — don't leave `:latest` and `:previous` pointing at the same digest for long, because the next deploy needs a real previous to roll back to.

If the bad deploy also touched `Caddyfile.prod` or `docker-compose.prod.yml`, `git checkout` the previous commit on the VPS first, then run the rollback above.
