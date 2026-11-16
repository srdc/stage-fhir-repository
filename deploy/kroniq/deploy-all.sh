#!/bin/bash
# Deploys the STAGE stack in dependency order, each part healthy before the next:
#   shared-infra (stage-mongo, stage-redis, stage-db) -> fhir (stage-fhir) -> onauth -> apps
# The ML services are not included yet: ./deploy.sh ml. The FAIR Data Point is deployed separately (FDP-docker).
set -euo pipefail
exec "$(dirname "${BASH_SOURCE[0]}")/deploy.sh" shared-infra fhir onauth apps
