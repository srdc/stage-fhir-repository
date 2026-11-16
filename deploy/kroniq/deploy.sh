#!/bin/bash
# Deploys STAGE components (pulls their images, starts them, waits until they are healthy):
#
#   ./deploy.sh <component>...     shared-infra, fhir, onauth, apps, ml (in this order when several)
#
# Settings: .env (images, ports, volumes); ONAUTH_REALM_INIT=true ./deploy.sh onauth reloads onAuth's realm (README);
# PULL=false ./deploy.sh ... uses the images already on the host.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

compose() { docker compose --env-file "$DIR/.env" -f "$DIR/$1/docker-compose.yml" "${@:2}"; }

wait_healthy() {
  local container="$1" waited=0 status
  echo "  waiting for $container..."
  while true; do
    status="$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || echo not-found)"
    case "$status" in
      healthy|running) echo "  -> $container: $status"; return 0 ;;
      not-found) echo "ERROR: container $container not found" >&2; return 1 ;;
    esac
    if [ "$waited" -ge "$TIMEOUT_SECONDS" ]; then
      echo "ERROR: $container not healthy after ${TIMEOUT_SECONDS}s (status: $status); its last log lines:" >&2
      docker logs --tail 50 "$container" >&2 || true
      return 1
    fi
    sleep 3; waited=$((waited + 3))
  done
}

# one-shot container: wait until it exits, fail unless it exited with 0
wait_done() {
  local container="$1" code
  echo "  waiting for $container to finish..."
  code="$(docker wait "$container")"
  docker logs "$container" 2>&1 | tail -5 | sed 's/^/    /'
  [ "$code" = 0 ] || { echo "ERROR: $container exited with $code" >&2; return 1; }
}

docker network create stage-network >/dev/null 2>&1 || true

[ $# -gt 0 ] || { sed -n '2,8p' "$0"; exit 1; }
for component in "$@"; do
  [ -f "$DIR/$component/docker-compose.yml" ] || { echo "unknown component: $component" >&2; exit 1; }
  echo "=== $component"
  [ "${PULL:-true}" = false ] || compose "$component" pull --quiet
  compose "$component" up -d
  case "$component" in
    shared-infra) wait_healthy stage-mongo; wait_healthy stage-redis; wait_healthy stage-db; wait_done stage-db-setup ;;
    fhir)         wait_healthy stage-fhir ;;
    onauth)       wait_healthy stage-onauth ;;
    apps)         wait_healthy stage-smart-apps ;;
    ml)           wait_healthy stage-shap-client; wait_healthy stage-fhir-rag-med; wait_healthy stage-onfhir-feast ;;
  esac
done
