#!/usr/bin/env bash
# Writes the local (gitignored) files of the stage.testing.eu simulation into ./local:
#   local/certs        TLS certificate of stage.testing.eu (mkcert; run `mkcert -install` once to trust its CA)
#   local/fhir-conf    onAuth metadata of the repositories, from fhir-conf with the host replaced
#   local/onauth/conf  onAuth configuration and realm "smart", from stage-onauth/conf with the host replaced
#                      (holds secrets: the client secrets and users of the realm)
# Run again after changing the sources; it overwrites ./local (the data in the Docker volumes stays).
#   STAGE_ONAUTH_DIR   stage-onauth checkout (default: ../../../stage-onauth)
set -euo pipefail
cd "$(dirname "$0")"

HOST=stage.testing.eu
KRONIQ=kroniq.srdc.com.tr
REPO=../..
ONAUTH_DIR=${STAGE_ONAUTH_DIR:-$REPO/../stage-onauth}

for f in "$REPO/fhir-conf/authorization-server-metadata.json" "$REPO/fhir-conf/smart-configuration.json" \
         "$REPO/fhir-conf/secrets.conf" "$REPO/fhir-conf/jwks.json" "$REPO/fhir-conf/jwks-ehr.json" \
         "$ONAUTH_DIR/conf/application-matrix.conf" "$ONAUTH_DIR/conf/realms/smart/realm.json"; do
  [ -f "$f" ] || { echo "Missing $f" >&2; exit 1; }
done

mkdir -p local/certs local/fhir-conf local/onauth/conf

# 1) Certificate
if [ ! -f local/certs/$HOST.pem ]; then
  mkcert -cert-file local/certs/$HOST.pem -key-file local/certs/$HOST-key.pem $HOST
fi

# 2) onAuth metadata of the repositories (issuer and endpoints on stage.testing.eu)
for f in authorization-server-metadata.json smart-configuration.json; do
  sed "s#https://$KRONIQ/#https://$HOST/#g" "$REPO/fhir-conf/$f" > local/fhir-conf/$f
done

# 3) onAuth: configuration (reverse proxy host) and realm smart (redirect URIs on stage.testing.eu, patient context
#    from the STAGE repository container)
rm -rf local/onauth/conf/realms
mkdir -p local/onauth/conf/realms
cp -r "$ONAUTH_DIR/conf/realms/smart" local/onauth/conf/realms/smart
sed -e "s#$KRONIQ#$HOST#g" \
    -e "s#^\(\s*\)init = .*#\1init = false#" \
    "$ONAUTH_DIR/conf/application-matrix.conf" > local/onauth/conf/application-testing.conf
python - "$HOST" "$KRONIQ" <<'EOF'
import json, pathlib, sys
host, kroniq = sys.argv[1], sys.argv[2]
for p in pathlib.Path('local/onauth/conf/realms/smart/clients').glob('*.json'):
    client = json.loads(p.read_text(encoding='utf-8'))
    if 'redirect_uris' in client:
        uris = [u.replace(f'https://{kroniq}/', f'https://{host}/') for u in client['redirect_uris']]
        client['redirect_uris'] = list(dict.fromkeys(uris))
    resolution = client.get('smart_context_resolution')
    if resolution and 'fhirServerBaseUrl' in resolution:
        resolution['fhirServerBaseUrl'] = 'http://stage-fhir:8080/fhir'
    p.write_text(json.dumps(client, indent=2), encoding='utf-8')
EOF

echo "Done: ./local is ready. Images: see README.md, then: docker compose up -d"
