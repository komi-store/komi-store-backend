#!/usr/bin/env bash
# Nightly Postgres backup for the GitHub Store backend.
#
# Runs inside the VPS (not the container). Uses `docker exec` to pg_dump
# from the running postgres container. Output is a compressed custom-format
# archive (pg_dump -Fc) so it restores with pg_restore.
#
# resource_cache is dumped schema-only (--exclude-table-data). Its rows are
# cached GitHub responses the app refetches on demand, and including them made
# each dump about 8.5 GB. A restore starts with an empty cache.
#
# Retention:
#   - 7 days local, in $BACKUP_DIR (rotated by file mtime).
#   - 30 days off-host, on the rclone remote (rotated by `rclone delete --min-age`).
#
# Off-host copy:
#   If RCLONE_REMOTE and BACKUP_GPG_PASSPHRASE are set in the environment,
#   the dump is GPG-symmetric-encrypted (AES256) and `rclone copy`'d to
#   the remote. If either is unset, the script logs a loud warning and
#   keeps the local-only behaviour — a partial setup is still better than
#   no backup at all.
#
# Healthcheck.io:
#   If HEALTHCHECK_URL is set, the script pings it on success only.
#   `curl -fsS -m 10`, no retries — a missed ping is the alert.
#
# Install: deploy.sh rsyncs this file to /opt/github-store-backend/ops/. Link it
# to the path the existing crontab calls:
#   ssh root@<vps> 'ln -sf /opt/github-store-backend/ops/backup_postgres.sh /usr/local/bin/backup_postgres.sh'
# Cron expects the env file to exist at /opt/github-store-backend/.env so
# RCLONE_REMOTE / BACKUP_GPG_PASSPHRASE / HEALTHCHECK_URL are visible:
#   30 3 * * * . /opt/github-store-backend/.env && /usr/local/bin/backup_postgres.sh >> /var/log/postgres_backup.log 2>&1
#
# Restore:
#   docker exec -i github-store-backend-postgres-1 pg_restore -U githubstore -d githubstore --clean --if-exists < /var/backups/postgres/backup-YYYY-MM-DD.dump
# Off-host restore:
#   rclone copy "$RCLONE_REMOTE/backup-YYYY-MM-DD.dump.gpg" .
#   gpg --batch --pinentry-mode loopback --passphrase "$BACKUP_GPG_PASSPHRASE" \
#       --decrypt backup-YYYY-MM-DD.dump.gpg > backup-YYYY-MM-DD.dump
# See ops/RECOVERY.md for the full disaster-recovery runbook.

set -euo pipefail

BACKUP_DIR="/var/backups/postgres"
CONTAINER="github-store-backend-postgres-1"
DB_USER="githubstore"
DB_NAME="githubstore"
LOCAL_RETENTION_DAYS=7
REMOTE_RETENTION_DAYS=30
DATE=$(date -u +%Y-%m-%d)
OUT="$BACKUP_DIR/backup-$DATE.dump"

RCLONE_REMOTE="${RCLONE_REMOTE:-}"
BACKUP_GPG_PASSPHRASE="${BACKUP_GPG_PASSPHRASE:-}"
HEALTHCHECK_URL="${HEALTHCHECK_URL:-}"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { echo "[$(ts)] $*"; }
warn() { echo "[$(ts)] WARN: $*" >&2; }
err() { echo "[$(ts)] ERROR: $*" >&2; }

mkdir -p "$BACKUP_DIR"

log "Starting backup to $OUT"

# pg_dump inside the container, pipe to host file. -Fc = custom format,
# compressed, restorable with pg_restore. Write to .tmp first so a crash
# mid-dump doesn't leave a half-written file with today's name. Idempotent
# across same-day reruns: today's existing file gets overwritten.
if [[ -f "$OUT" ]]; then
    log "Backup for $DATE already exists, overwriting"
fi
# A failed dump (disk full, Postgres down) must not leave a multi-GB .tmp
# behind. After the mv there is no .tmp, so this is a no-op on success.
trap 'rm -f "$OUT.tmp"' EXIT
docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc \
    --exclude-table-data=resource_cache > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"

SIZE=$(du -h "$OUT" | cut -f1)
log "Local dump complete: $OUT ($SIZE)"

# --- Off-host copy --------------------------------------------------------
# Only proceeds if both rclone target and encryption passphrase are set.
# A failure here leaves the local dump in place — never delete it as a
# side effect of off-host trouble.
OFFHOST_OK=0
if [[ -n "$RCLONE_REMOTE" && -n "$BACKUP_GPG_PASSPHRASE" ]]; then
    if ! command -v rclone >/dev/null 2>&1; then
        err "rclone not installed but RCLONE_REMOTE is set; skipping off-host copy"
    elif ! command -v gpg >/dev/null 2>&1; then
        err "gpg not installed but BACKUP_GPG_PASSPHRASE is set; skipping off-host copy"
    else
        ENC="$OUT.gpg"
        log "Encrypting and uploading to $RCLONE_REMOTE"
        # Passphrase via stdin (--passphrase-fd 0) keeps it off argv / ps.
        if printf '%s' "$BACKUP_GPG_PASSPHRASE" | \
                gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 \
                    --symmetric --cipher-algo AES256 --output "$ENC" "$OUT"; then
            if rclone copy "$ENC" "$RCLONE_REMOTE" --quiet; then
                log "Off-host upload complete: $RCLONE_REMOTE/$(basename "$ENC")"
                OFFHOST_OK=1
            else
                err "rclone copy failed; local dump preserved at $OUT"
            fi
            rm -f "$ENC"
        else
            err "gpg encryption failed; local dump preserved at $OUT"
            rm -f "$ENC"
        fi

        # Remote rotation. --min-age only deletes files older than the
        # threshold; harmless to skip if the upload above failed.
        if [[ "$OFFHOST_OK" -eq 1 ]]; then
            if rclone delete "$RCLONE_REMOTE" --min-age "${REMOTE_RETENTION_DAYS}d" --quiet; then
                log "Remote rotation complete (kept ${REMOTE_RETENTION_DAYS}d)"
            else
                warn "Remote rotation failed; remote may accumulate old dumps"
            fi
        fi
    fi
else
    warn "RCLONE_REMOTE or BACKUP_GPG_PASSPHRASE unset — running local-only. Hardware loss = data loss."
fi

# --- Local rotation -------------------------------------------------------
# Run after the off-host step so we never delete a local dump before its
# encrypted twin is safely on the remote.
find "$BACKUP_DIR" -name "backup-*.dump" -type f -mtime +"$LOCAL_RETENTION_DAYS" -print -delete

log "Done. Local dumps in $BACKUP_DIR:"
ls -lh "$BACKUP_DIR"/backup-*.dump 2>/dev/null | tail -20 || true

# --- Healthcheck ping -----------------------------------------------------
# Ping only on real success. If off-host was attempted and failed, we
# deliberately do NOT ping so the missed-ping alert fires.
if [[ -n "$HEALTHCHECK_URL" ]]; then
    if [[ -n "$RCLONE_REMOTE" && -n "$BACKUP_GPG_PASSPHRASE" && "$OFFHOST_OK" -ne 1 ]]; then
        warn "Off-host upload failed; skipping healthcheck ping so the alert fires"
    else
        if curl -fsS -m 10 "$HEALTHCHECK_URL" >/dev/null; then
            log "Healthcheck ping sent"
        else
            warn "Healthcheck ping failed (curl error)"
        fi
    fi
fi
