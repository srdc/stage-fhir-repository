#!/bin/bash
# Stops and removes the containers of STAGE components; volumes (data) are kept.
#
#   ./destroy.sh <component>...    shared-infra, fhir, onauth, apps, ml
#
# Stop shared-infra last: the other components (and the FAIR Data Point, which uses stage-mongo) depend on it.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ $# -gt 0 ] || { sed -n '2,7p' "$0"; exit 1; }
for component in "$@"; do
  [ -f "$DIR/$component/docker-compose.yml" ] || { echo "unknown component: $component" >&2; exit 1; }
  echo "=== $component"
  docker compose --env-file "$DIR/.env" -f "$DIR/$component/docker-compose.yml" down
done
