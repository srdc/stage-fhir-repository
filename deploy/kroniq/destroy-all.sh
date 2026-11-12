#!/bin/bash
# Stops the STAGE stack in reverse order (volumes are kept). The FAIR Data Point uses stage-mongo too: it loses its
# database until shared-infra is up again.
set -euo pipefail
exec "$(dirname "${BASH_SOURCE[0]}")/destroy.sh" ml apps onauth fhir shared-infra
