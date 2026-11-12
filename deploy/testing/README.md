# Local simulation of the STAGE server: https://stage.testing.eu

The deployment of `deploy/kroniq` on one machine, under a test domain (127.0.0.1 `stage.testing.eu` in the hosts file),
with the same paths, so the apps, onAuth and the repositories behave as on https://kroniq.srdc.com.tr:

| Path | Container |
|---|---|
| `/stage-fhir` (FHIR, CDS Hooks under `/stage-fhir/cds-hooks`) | stage-fhir (`stage/fhir-repository:testing`, database `stage`, also on 127.0.0.1:7702) |
| `/ehr` (FHIR, CDS Hooks under `/ehr/cds-hooks`) | ehr-fhir (database `ehr`, also on 127.0.0.1:7707) |
| `/stage-shl/shl`, `/shl-ehr/shl` | SMART Health Links of stage-fhir and ehr-fhir (`<server>/shl`, links in each database's `shl_links`) |
| `/stage-onauth` | onAuth (realm smart, database `stage_onauth`) |
| `/smart-apps` | smart-apps (`/smart-apps/risk-prediction/`, `/qrisk3/`, `/dashboard/`) |

MongoDB (`stage-testing-mongo`, 127.0.0.1:27117), PostgreSQL (`stage-testing-postgres`, 127.0.0.1:15432), Redis
(`stage-testing-redis`) and the data are separate from any other local setup (volumes of the `stage-testing` project).

The services share one Redis and one PostgreSQL:

| Store | Service | |
|---|---|---|
| Redis `stage-testing-redis` (7, AOF; no password, no published port) | onAuth | database 0 (onAuth cannot select another one; it flushes it when set up from scratch) |
| | fhir-rag-med | database 1 (`REDIS_URL=redis://redis:6379/1`; session state, expiring after 6 h) |
| PostgreSQL `stage-testing-postgres` (16) | onfhir-feast | database and login role `onfhir_feast` (password `local/postgres-onfhir_feast.secret`); the superuser (`local/postgres.secret`) only administers |

`postgres-setup` (`postgres/databases.sh`) creates the services' databases and roles on every `docker compose up` and hands
over objects created by another role; it exits when done. Another service gets a line there, a secret file in setup.sh
and a mount. Do not set a Redis `maxmemory` eviction policy: it would also drop onAuth's sessions and tokens.

The ML services of the CDS services run as containers too, from the checkouts next to this repository (the workspace
of "Related repositories" in the main README; mounted read-only, so `docker restart <container>` picks up code changes).
Checkouts elsewhere: set their `.env` variable (`deploy/testing/.env`, gitignored), a path relative to this folder or
absolute, e.g. `FHIR_RAG_MED_DIR=../../../../other/fhir-rag-med`:

| Container | Checkout (`.env` variable) | Used by |
|---|---|---|
| shap-client (`stage/shap-client:testing`: Python runtime only) | `shap-client` (`SHAP_CLIENT_DIR`) | risk_prediction, risk_prediction_diseases |
| fhir-rag-med (its own image, `src` mounted), Redis database 1 | `fhir-rag-med` (`FHIR_RAG_MED_DIR`) | shap_explain, ask_llm (calls its LLM provider on the internet) |
| onfhir-feast (`onfhir-feast-standalone.jar` and `definitions` mounted), PostgreSQL database `onfhir_feast` | `onfhir-feast` (`ONFHIR_FEAST_DIR`) | risk_prediction_form, risk_prediction |

onfhir-feast reads the patients' data from the repositories (data sources `stageFhir` and `ehrFhir`) with a token of
the client `stage-services` (client credentials, `user/*.read`), which `setup.sh` adds to the testing realm only. To use
services on the host instead, set `ML_SERVICE_URL`, `LLM_SERVICE_URL`, `ONFHIR_FEAST_URL` in `.env`
(e.g. `http://host.docker.internal:8001`).

## 1. Once

- `mkcert -install` (trusts the local CA in Windows; restart the browser. Firefox: also needs `certutil` / NSS, see mkcert).
- The checkouts of "Related repositories" in the main README, pulled, at their branches.
- Secrets as for kroniq, in `fhir-conf/` (gitignored): `jwks.json`, `jwks-ehr.json`, `secrets.conf`,
  `protected-resource-server-metadata.json`; and the `stage-onauth` checkout next to this repository (`STAGE_ONAUTH_DIR`)
  with its realm `smart` (`conf/realms/smart`).
- `./setup.sh`: writes `local/` (gitignored): the certificate, the onAuth metadata of stage.testing.eu for the
  repositories, the onAuth configuration with realm smart (redirect URIs of all the apps on stage.testing.eu, client
  `stage-services`), the onfhir-feast configuration and the generated secrets (`stage-services.secret`,
  `postgres.secret`, `postgres-onfhir_feast.secret`). Run it again after changing those sources.

## 2. Images

From the workspace (onFHIR and onfhir-cds installed first, see Build in the main README):

```
# repository (FHIR + CDS)
cd stage-fhir-repository && mvn package -DskipTests && docker build -t stage/fhir-repository:testing .
# apps (Node 20; libraries first, then the apps as in build-docker.bat:
#   npx ng build <app> --configuration production --base-href /smart-apps/<path>/ --deploy-url /smart-apps/<path>/
# for qrisk2 (path qrisk), qrisk3, advance, acc_aha, score (score2), shc-generator, risk-prediction, dashboard)
# Git Bash: export MSYS_NO_PATHCONV=1 first, or it turns --base-href /smart-apps/... into C:/Program Files/Git/smart-apps/...
# npm ci first (ng-smart-on-fhir comes from npm)
cd smart-apps
npx ng build cds-hooks && npx ng build ui-components && npx ng build common
npx ng build risk-prediction --configuration production --base-href /smart-apps/risk-prediction/ --deploy-url /smart-apps/risk-prediction/
...
docker build -t srdc/smart-apps:testing -f ./docker/Dockerfile .
# ML services
docker build -t stage/shap-client:testing stage-fhir-repository/deploy/testing/ml/shap-client
cd fhir-rag-med && docker build -t fhir-rag-med .
cd onfhir-feast && mvn package -DskipTests   # onfhir-feast-server/target/onfhir-feast-standalone.jar
```

onAuth: built from the stage-onauth checkout, `cd stage-onauth && IMAGE=stage/onauth:testing ./build.sh` (builds
onauth-server and onauth-manager from their checkouts first, see its README; `./build.sh image` when they are built
already). Its entrypoint points onauth-manager's built-in `/onauth` paths to `/stage-onauth`; the nexus image of
2026-10-06 does not, and its login page then redirects to `/onauth/onauth-manager/...`. Other
images via `.env` (`FHIR_IMAGE`, `SMART_APPS_IMAGE`, `ONAUTH_IMAGE`, `SHL_IMAGE` (the Node reference), `SHAP_CLIENT_IMAGE`,
`FHIR_RAG_MED_IMAGE`).

The apps' docker environments take the host from the page (`window.location.origin`), so the same image works on
kroniq and here.

## 3. Run

```
docker compose up -d
docker compose logs -f stage-fhir
```

https://stage.testing.eu/smart-apps/risk-prediction/ (login "STAGE" or "EHR", users of realm smart).

On the first start onAuth creates its realms and may stop with `Futures timed out after [10 seconds]` (its fixed
start-up timeout; the container keeps running because of its own nginx): `docker restart stage-testing-onauth`, the
second start skips what is already stored.

onAuth stores the realm on its first start only. After changing it (`./setup.sh`), recreate it (this logs everyone out):

```
docker exec stage-testing-mongo mongo --quiet --eval 'db.getSiblingDB("stage_onauth").dropDatabase(); db.getSiblingDB("stage_onauth_smart").dropDatabase()'
docker exec stage-testing-redis redis-cli -n 0 FLUSHDB    # onAuth's database only (not FLUSHALL: database 1 is fhir-rag-med's)
docker restart stage-testing-onauth
```

### Test patients

The repositories start with the STAGE conformance resources, ValueSets and Questionnaires only. `seed.py` loads the
test patients (synthetic, HTEST tagged) from `local/seed/` with a `stage-services` token:

- STAGE: the patients of realm smart's patient users: helen_wright (`6fd322a8-...`), ahmet_kilic (`pa01`) and
  john_wick (`pa-demo-01`), the last two copies of other test patients' records;
- EHR: the other seven test patients of the source (practitioner anna_svensson selects them). No record of a STAGE
  patient is in EHR (same ids, or the source records of the copies), so they can be imported from STAGE into EHR
  (xShare) in a demo;
- STAGE, also: the feature extraction patients of the `fhir-path-feature-extraction` checkout (`FEATURE_EXTRACTION_DIR`),
  read from there on every load: the WP7 storyboard patients `sb-p1`..`sb-p4` (one per model stratum), `wp6-test-complete`
  and the profiles `123456`..`523456`. They have no realm users: a clinician (anna_svensson) opens them. To also remove what a demo run added, use its
  `storyboard_patients/reset_patients.py https://stage.testing.eu/stage-fhir --token <token>`.

```
python seed.py load
```

`local/seed/` is written by `python seed.py export --source <FHIR base without authorization>`, e.g. from a temporary
repository on a copy of a local database holding the test patients (`stage_ehr_a` of the local development MongoDB):

```
docker exec mongo mongodump --quiet --db stage_ehr_a --archive > ehr_a.archive
docker exec -i stage-testing-mongo mongorestore --quiet --archive --noIndexRestore --nsFrom 'stage_ehr_a.*' --nsTo 'seed_source.*' --drop < ehr_a.archive
# a repository on seed_source without authorization (fhir.authorization.method = none) on 127.0.0.1:7799, then
python seed.py export
```

Stop with `docker compose down` (add `-v` to delete the data).

## 4. Check

```
curl -s https://stage.testing.eu/stage-fhir/metadata | head -c 100
curl -s https://stage.testing.eu/stage-fhir/cds-hooks/cds-services | head -c 100
curl -s https://stage.testing.eu/stage-onauth/api/smart/.well-known/openid-configuration | head -c 100
# onfhir-feast through its authenticated data source (about 30 s)
docker exec stage-testing-nginx curl -s 'http://onfhir-feast:8085/onfhir-feast/api/DataSource/stageFhir/FeatureSet/stage_wp6_features/$retrieve-feature-values?subject=pa01&format=fhir' | head -c 100
```

(Git Bash curl: add `--ssl-no-revoke` for the mkcert certificate.)

SMART Health Links: the conformance tests in `shl-conformance/` (see its README), against the repository's SHL and, for
comparison, the former Node shl-server (`docker compose --profile shl-reference up -d node-shl`, at /node-shl):

```
SHL_URL=https://stage.testing.eu/stage-shl SHL_REFERENCE_URL=https://stage.testing.eu/node-shl python -m pytest -q shl-conformance
```
