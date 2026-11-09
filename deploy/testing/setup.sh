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
        if client.get('client_id') == 'smart-test':
            # the callbacks of all the apps of the smart-apps image (docker/default.conf.template)
            uris += [f'https://{host}/smart-apps/{app}/callback' for app in
                     ('qrisk', 'qrisk3', 'score2', 'advance', 'acc_aha', 'risk-prediction', 'dashboard', 'shc-generator')]
        client['redirect_uris'] = list(dict.fromkeys(uris))
    resolution = client.get('smart_context_resolution')
    if resolution and 'fhirServerBaseUrl' in resolution:
        resolution['fhirServerBaseUrl'] = 'http://stage-fhir:8080/fhir'
    p.write_text(json.dumps(client, indent=2), encoding='utf-8')
EOF

# 4) Service client "stage-services" (testing realm only): client credentials with user/*.* on the repositories, for
#    onfhir-feast (reads the patients' data for the risk prediction services) and seed.py (loads the test patients).
#    Its secret is generated once into local/stage-services.secret.
[ -f local/stage-services.secret ] || python -c "import secrets; print(secrets.token_urlsafe(32))" > local/stage-services.secret
python - <<'EOF'
import json, pathlib
realm = pathlib.Path('local/onauth/conf/realms/smart')
secret = pathlib.Path('local/stage-services.secret').read_text().strip()
base = json.loads((realm / 'clients/smart-test.json').read_text(encoding='utf-8'))
client = {k: v for k, v in base.items() if k not in ('redirect_uris', 'registration_access_token')}
client.update({
    'client_id': 'stage-services', 'client_name': 'STAGE services (onfhir-feast, test data)', 'client_secret': secret,
    'token_endpoint_auth_method': 'client_secret_basic', 'scope': 'user/*.* user/*.read', 'grant_types': ['client_credentials'],
    'response_types': ['code'], 'access_type': 'bearer-only'})
files = {
    'clients/stage-services.json': client,
    'policies/stage-services-policy.json': {
        'id': 'stage-services-policy', 'rs_id': 'fhir-repo', 'logic': 'positive', 'policy_type': 'client',
        'client_id': 'stage-services'},
    'rules/stage-services-can-access-all-data.json': {
        'id': 'stage-services-can-access-all-data', 'description': 'STAGE services can access all data',
        'client_id': 'fhir-repo', 'scopes': ['user/*.*', 'user/*.read'], 'policies': ['stage-services-policy'], 'strategy': 'unanimous'},
}
for name, content in files.items():
    (realm / name).write_text(json.dumps(content, indent=2), encoding='utf-8')
EOF

# 5) onfhir-feast: one data source per repository (the ids the repositories send, ONFHIR_FEAST_DATA_SOURCE_ID), read
#    through the containers' network with a read-only token of stage-services
mkdir -p local/onfhir-feast
# passwords of the shared postgres container, generated once: its superuser, and onfhir-feast's own role (database
# onfhir_feast; postgres/databases.sh creates both)
[ -f local/postgres.secret ] || python -c "import secrets; print(secrets.token_urlsafe(24), end='')" > local/postgres.secret
[ -f local/postgres-onfhir_feast.secret ] || python -c "import secrets; print(secrets.token_urlsafe(24), end='')" > local/postgres-onfhir_feast.secret
python - <<'EOF'
import pathlib
secret = pathlib.Path('local/stage-services.secret').read_text().strip()
postgres_password = pathlib.Path('local/postgres-onfhir_feast.secret').read_text().strip()
source = '''  %s {
    id = "%s"
    interface = "fhir"
    sourceType = "fhir-api"
    version = "R4B"
    baseUrl = "%s"
    numRecordsPerPage = 500
    numRecordsPerBatch = 10000
    numEntityPerQuery = 250
    numOfPartitions = 4
    requestTimeout = "300 seconds"
    paginationParam = "_page"
    auth {
      type = "client-credentials"
      tokenEndpoint = "http://stage-onauth/stage-onauth/api/smart/token"
      clientId = "stage-services"
      clientSecret = "%s"
      scopes = ["user/*.read"]
      aud = "fhir-repo"
    }
  }
'''
conf = ('# Written by setup.sh (holds the secret of the stage-services client)\n'
        'include classpath("application.conf")\n\n'
        'onfhir-feast.server.root-url = "http://onfhir-feast:8085/onfhir-feast"\n'
        'onfhir-feast.etl.offline-repository.rootDir = "/data/output-data"\n'
        '# jobs, datasets and the akka persistence of onfhir-feast: its database and role in the shared postgres\n'
        'slick-postgres.db.serverName = "postgres"\n'
        'slick-postgres.db.databaseName = "onfhir_feast"\n'
        'slick-postgres.db.properties.user = "onfhir_feast"\n'
        'slick-postgres.db.properties.password = "%s"\n' % postgres_password +
        '# replaces the data sources of the default configuration\n'
        'onfhir-feast.etl.data-sources = null\n'
        'onfhir-feast.etl.data-sources {\n'
        + source % ('stageFhir', 'stageFhir', 'http://stage-fhir:8080/fhir', secret)
        + source % ('ehrFhir', 'ehrFhir', 'http://ehr-fhir:8080/fhir', secret)
        + '}\n')
pathlib.Path('local/onfhir-feast/application.conf').write_text(conf, encoding='utf-8')
EOF

echo "Done: ./local is ready. Images: see README.md, then: docker compose up -d"
