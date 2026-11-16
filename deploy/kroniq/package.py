"""
Assembles the folder that is copied to kroniq.srdc.com.tr (default: <workspace>/Distribution/stage) from this folder
and the checkouts of the workspace (README.md of the repository, "Related repositories"):

  compose files, scripts, README, nginx locations   this folder (deploy/kroniq)
  shared-infra/postgres/databases.sh                 deploy/testing/postgres
  fhir/fhir-conf, fhir/cds-conf,                     this repository, with its gitignored secrets (signing keys,
  fhir/application-docker.conf                       secrets.conf, protected-resource-server-metadata.json)
  onauth/conf                                        stage-onauth: conf/application-matrix.conf as application.conf, realm
                                                     smart (+ client stage-services for onfhir-feast)
  ml/onfhir-feast                                    onfhir-feast: jar (mvn package first) and definitions; its
                                                     application.conf with the data source of the repository
  secrets/                                           generated once: PostgreSQL passwords, stage-services secret

  python package.py [target]          (environment: STAGE_ONAUTH_DIR, ONFHIR_FEAST_DIR for checkouts elsewhere)

Run it again after changing any of these; it replaces what it writes and keeps .env, secrets/ and everything else of
the target (e.g. fdp/). The target holds secrets: it is not a git folder and must not become one.
"""
import json
import os
import pathlib
import secrets
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
WORKSPACE = REPO.parent
TARGET = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else WORKSPACE / 'Distribution' / 'stage').resolve()
ONAUTH = pathlib.Path(os.environ.get('STAGE_ONAUTH_DIR', WORKSPACE / 'stage-onauth')).resolve()
FEAST = pathlib.Path(os.environ.get('ONFHIR_FEAST_DIR', WORKSPACE / 'onfhir-feast')).resolve()

LAYOUT = ['README.md', 'nginx-locations.conf', 'deploy.sh', 'deploy-all.sh', 'destroy.sh', 'destroy-all.sh',
          'migrate-oct2026.sh', 'shared-infra/docker-compose.yml', 'fhir/docker-compose.yml',
          'onauth/docker-compose.yml', 'apps/docker-compose.yml', 'ml/docker-compose.yml']
# files of the July 2026 layout that this one replaces (Distribution/stage_backup_Oct2026 keeps them)
OBSOLETE = ['stage-repo', 'docker-compose.yml', 'docker-compose-cds.yml', 'docker-compose-shl.yml', 'deploy-apps.sh',
            'deploy-cds.sh', 'deploy-shl.sh', 'destroy-apps.sh', 'destroy-cds.sh', 'destroy-shl.sh', 'onauth/deploy.sh',
            'onauth/destroy.sh', 'onauth/conf/application-stage.conf', 'onauth/conf/realms/stage',
            'shared-infra/deploy.sh', 'shared-infra/destroy.sh']
REPO_SECRETS = ['fhir-conf/jwks.json', 'fhir-conf/secrets.conf',
                'fhir-conf/protected-resource-server-metadata.json']


def text_lf(src: pathlib.Path, dst: pathlib.Path):
    """copies a text file with LF line ends (the server is Linux)"""
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(src.read_bytes().replace(b'\r\n', b'\n'))
    if dst.suffix == '.sh':
        dst.chmod(0o755)


def tree(src: pathlib.Path, dst: pathlib.Path):
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)


def secret(name: str) -> str:
    p = TARGET / 'secrets' / name
    if not p.exists():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(secrets.token_urlsafe(24), encoding='utf-8')
        print('  generated secrets/' + name)
    return p.read_text(encoding='utf-8').strip()


def write_json(p: pathlib.Path, content):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(content, indent=2) + '\n', encoding='utf-8')


missing = [s for s in REPO_SECRETS if not (REPO / s).exists()]
if missing:
    sys.exit(f'missing in {REPO}: {missing} (gitignored secrets, see the README of the repository)')
if not (ONAUTH / 'conf/realms/smart/realm.json').exists():
    sys.exit(f'no stage-onauth checkout at {ONAUTH} (STAGE_ONAUTH_DIR)')

print('target:', TARGET)
TARGET.mkdir(parents=True, exist_ok=True)
for name in OBSOLETE:
    p = TARGET / name
    if p.is_dir():
        shutil.rmtree(p)
        print('  removed obsolete', name + '/')
    elif p.exists():
        p.unlink()
        print('  removed obsolete', name)

# layout
for name in LAYOUT:
    text_lf(HERE / name, TARGET / name)
if not (TARGET / '.env').exists():
    text_lf(HERE / '.env', TARGET / '.env')
elif (TARGET / '.env').read_bytes().replace(b'\r\n', b'\n') != (HERE / '.env').read_bytes().replace(b'\r\n', b'\n'):
    print('  note: .env of the target differs from deploy/kroniq/.env and was kept')
text_lf(REPO / 'deploy/testing/postgres/databases.sh', TARGET / 'shared-infra/postgres/databases.sh')

# secrets of the stack
secret('postgres.secret')
feast_db_password = secret('postgres-onfhir_feast.secret')
services_secret = secret('stage-services.secret')

# repository
tree(REPO / 'fhir-conf', TARGET / 'fhir/fhir-conf')
tree(REPO / 'cds-conf', TARGET / 'fhir/cds-conf')
text_lf(REPO / 'application-docker.conf', TARGET / 'fhir/application-docker.conf')
print('  fhir: fhir-conf, cds-conf, application-docker.conf')

# onAuth
text_lf(ONAUTH / 'conf/application-matrix.conf', TARGET / 'onauth/conf/application.conf')
realm = TARGET / 'onauth/conf/realms/smart'
tree(ONAUTH / 'conf/realms/smart', realm)
# SMART context resolution (patient selection at launch) reads the patients from the STAGE repository on stage-network
repo_client = json.loads((realm / 'clients/fhir-repo.json').read_text(encoding='utf-8'))
if 'smart_context_resolution' in repo_client:
    repo_client['smart_context_resolution']['fhirServerBaseUrl'] = 'http://stage-fhir:8080/fhir'
    write_json(realm / 'clients/fhir-repo.json', repo_client)
# client stage-services (client credentials, read access to all data): onfhir-feast reads the repository with it
base = json.loads((realm / 'clients/smart-test.json').read_text(encoding='utf-8'))
client = {k: v for k, v in base.items() if k not in ('redirect_uris', 'registration_access_token')}
client.update({
    'client_id': 'stage-services', 'client_name': 'STAGE services (onfhir-feast)', 'client_secret': services_secret,
    'token_endpoint_auth_method': 'client_secret_basic', 'scope': 'user/*.read', 'grant_types': ['client_credentials'],
    'response_types': ['code'], 'access_type': 'bearer-only', 'contacts': []})
write_json(realm / 'clients/stage-services.json', client)
write_json(realm / 'policies/stage-services-policy.json', {
    'id': 'stage-services-policy', 'rs_id': 'fhir-repo', 'logic': 'positive', 'policy_type': 'client',
    'client_id': 'stage-services'})
write_json(realm / 'rules/stage-services-can-read-all-data.json', {
    'id': 'stage-services-can-read-all-data', 'description': 'STAGE services (onfhir-feast) can read all data',
    'client_id': 'fhir-repo', 'scopes': ['user/*.read'], 'policies': ['stage-services-policy'], 'strategy': 'unanimous'})
print('  onauth: application.conf, realm smart (+ stage-services)')

# onfhir-feast
feast = TARGET / 'ml/onfhir-feast'
feast.mkdir(parents=True, exist_ok=True)
jar = FEAST / 'onfhir-feast-server/target/onfhir-feast-standalone.jar'
if jar.exists():
    shutil.copy2(jar, feast / jar.name)
    tree(FEAST / 'definitions', feast / 'definitions')
    print('  ml: onfhir-feast jar and definitions from', FEAST)
else:
    print(f'  note: no onfhir-feast jar at {jar} (mvn package in the checkout; ONFHIR_FEAST_DIR): ml/onfhir-feast incomplete')
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
(feast / 'application.conf').write_text(
    '# Written by package.py (holds secrets)\n'
    'include classpath("application.conf")\n\n'
    'onfhir-feast.server.root-url = "http://stage-onfhir-feast:8085/onfhir-feast"\n'
    'onfhir-feast.etl.offline-repository.rootDir = "/data/output-data"\n'
    '# jobs, datasets and the akka persistence of onfhir-feast: its database and role in stage-db\n'
    'slick-postgres.db.serverName = "stage-db"\n'
    'slick-postgres.db.databaseName = "onfhir_feast"\n'
    'slick-postgres.db.properties.user = "onfhir_feast"\n'
    'slick-postgres.db.properties.password = "%s"\n' % feast_db_password +
    '# replaces the data sources of the default configuration\n'
    'onfhir-feast.etl.data-sources = null\n'
    'onfhir-feast.etl.data-sources {\n'
    + source % ('stageFhir', 'stageFhir', 'http://stage-fhir:8080/fhir', services_secret)
    + '}\n', encoding='utf-8')
print('done')
