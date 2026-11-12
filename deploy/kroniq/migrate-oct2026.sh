#!/bin/bash
# One-time switch of kroniq.srdc.com.tr from the October 2026 containers to this layout (see README.md, "Migration").
# Keeps all data volumes; logs everyone out (onAuth's realm is reloaded from onauth/conf/realms/smart, its Redis starts
# empty). Run from this folder after package.py has filled it and the images are pushed; update nginx afterwards.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Removing the containers of the previous setup (volumes are kept)"
# stage-smart-cds, stage-shl, shl-ehr: replaced by the repositories (CDS Hooks and SMART Health Links);
# stage-repo-mongo: becomes stage-mongo (same volume; the old name stays as a network alias for the FAIR Data Point);
# stage-onauth-redis: replaced by stage-redis; the others are recreated by the new compose files.
for c in stage-smart-cds stage-shl shl-ehr stage-fhir ehr-fhir stage-onauth stage-onauth-redis stage-smart-apps stage-repo-mongo; do
  if docker inspect "$c" >/dev/null 2>&1; then docker rm -f "$c" >/dev/null && echo "  removed $c"; else echo "  ($c not present)"; fi
done

"$DIR/deploy.sh" shared-infra fhir

echo "=== onAuth: reloading realm smart (ONAUTH_REALM_INIT=true for this start)"
ONAUTH_REALM_INIT=true "$DIR/deploy.sh" onauth
for i in $(seq 1 100); do
  docker logs stage-onauth 2>&1 | grep -q "Rule metadata is stored - /sps/conf/realms/smart" && break
  [ "$i" = 100 ] && { echo "ERROR: realm smart not stored after 300s; see docker logs stage-onauth" >&2; exit 1; }
  sleep 3
done
echo "  realm smart stored; recreating onAuth with ONAUTH_REALM_INIT=false"
ONAUTH_REALM_INIT=false "$DIR/deploy.sh" onauth

"$DIR/deploy.sh" apps

echo
echo "Done. Next: nginx locations (nginx-locations.conf), then the checks of README.md."
echo "Old volumes no longer used (delete when no longer needed): stage-fhir_stage-shl-data, stage-fhir_shl-ehr-data (Node SHL test links)."
