# STAGE stack on https://kroniq.srdc.com.tr

The deployment of the STAGE services on kroniq.srdc.com.tr. This folder (in git, no secrets) is the source;
`package.py` assembles from it, and from the checkouts of the workspace, the folder that is copied to the server
(`<workspace>/Distribution/stage`, holds secrets, not in git). `deploy/testing` runs the same stack locally on
https://stage.testing.eu.

| Component | Containers | Public path (host nginx, `nginx-locations.conf`) | Host port |
|---|---|---|---|
| `shared-infra` | `stage-mongo` (alias `stage-repo-mongo`), `stage-redis`, `stage-db` (PostgreSQL), `stage-db-setup` (one-shot) | none | none |
| `fhir` | `stage-fhir`, `ehr-fhir`: FHIR, CDS Hooks (`<FHIR base>/cds-hooks`), SMART Health Links (`/shl`) | `/stage-fhir`, `/stage-shl/shl`, `/ehr`, `/shl-ehr/shl` | 7702, 7707 |
| `onauth` | `stage-onauth` (realm `smart`) | `/stage-onauth` | 7709 |
| `apps` | `stage-smart-apps` | `/smart-apps` | 7700 |
| `ml` (not deployed yet) | `stage-shap-client`, `stage-fhir-rag-med`, `stage-onfhir-feast` | none (the repositories call them) | none |

All containers are on the external network `stage-network`; host ports listen on 127.0.0.1 only. The FAIR Data Point
(`Distribution/FDP-docker`, `docker-compose-kroniq.yml`) is deployed separately; it uses `stage-mongo` (database `fdp`,
through the alias `stage-repo-mongo`) and its own PostgreSQL `stage-postgres`.

### Shared stores

| Store | Used by |
|---|---|
| `stage-mongo` (MongoDB 4.4, volume `stage_vstagerepomongo` of 2024, kept) | `onfhir` (STAGE repository), `ehr`, `stage_onauth` and `stage_onauth_smart` (onAuth), `fdp` (FAIR Data Point) |
| `stage-redis` (Redis 7, AOF) | database 0: onAuth (flushed when its realm is reloaded); 1: fhir-rag-med. Never `FLUSHALL`, no `maxmemory` |
| `stage-db` (PostgreSQL 16) | database and role `onfhir_feast`; the superuser only administers (`shared-infra/postgres/databases.sh`, run by `stage-db-setup` on every deploy) |

## Images

Built on a development machine from the workspace (README of the repository, "Related repositories": pull every
checkout first), then pushed to nexus:

| Image | Build | Contains |
|---|---|---|
| `nexus.srdc.com.tr:18445/stage/fhir-repository` | `cd stage-fhir-repository && mvn clean package -DskipTests && docker build -t nexus.srdc.com.tr:18445/stage/fhir-repository .` | STAGE repository, CDS services, SMART Health Links (configuration mounted) |
| `nexus.srdc.com.tr:18445/stage/smart-apps` | `cd smart-apps && npm ci && build-docker.bat`, then `docker tag srdc/smart-apps nexus.srdc.com.tr:18445/stage/smart-apps` | the apps |
| `nexus.srdc.com.tr:18445/stage/onauth` | `cd stage-onauth && ./build.sh` (its README: onauth-server and onauth-manager checkouts) | onAuth (configuration mounted) |
| `nexus.srdc.com.tr:18445/stage/shap-client` | `cd stage-fhir-repository && docker build -t nexus.srdc.com.tr:18445/stage/shap-client --build-context shap-client=../shap-client -f deploy/kroniq/ml/shap-client.Dockerfile deploy/testing/ml/shap-client` | shap-client code and models (incl. `data/models/stage2`, not in git) |
| `nexus.srdc.com.tr:18445/stage/fhir-rag-med` | `cd fhir-rag-med && docker build -t nexus.srdc.com.tr:18445/stage/fhir-rag-med .` | fhir-rag-med |

```
docker push nexus.srdc.com.tr:18445/stage/<image>
```

onfhir-feast has no image: `package.py` copies its jar (`mvn package` in the onfhir-feast checkout) and definitions,
and `stage-onfhir-feast` runs them on `eclipse-temurin:11-jre`.

The former images `stage/smart-cds`, `stage/shl-server` and `srdc/onfhir:r4` are no longer used.

## Prepare the server folder

```
python deploy/kroniq/package.py        # into <workspace>/Distribution/stage; ONFHIR_FEAST_DIR / STAGE_ONAUTH_DIR for checkouts elsewhere
```

It needs the gitignored secrets of the repository (`fhir-conf/jwks.json`, `jwks-ehr.json`, `secrets.conf`,
`protected-resource-server-metadata.json`) and generates `secrets/` (PostgreSQL passwords, the secret of client
`stage-services`) once. Copy the folder to the server (it keeps `.env` and `secrets/` of an existing copy; the server copy
of `secrets/` must stay, or the PostgreSQL passwords no longer match).

## Deploy

```
./deploy-all.sh                 # shared-infra -> fhir -> onauth -> apps, each healthy before the next
./deploy.sh fhir                # one component (pulls its images first): shared-infra, fhir, onauth, apps, ml
./destroy.sh apps               # stops a component; volumes are kept. destroy-all.sh: all, in reverse order
```

After changing configuration (`package.py` again, copy): `./deploy.sh <component>` recreates what changed; the
repositories read `fhir-conf` on start, so `docker restart stage-fhir ehr-fhir` after a change there.

onAuth stores realm `smart` in its database on the first start only. To load `onauth/conf/realms/smart` again (drops
onAuth's database and Redis database 0: everyone is logged out, changes made only in onauth-manager are lost):

```
ONAUTH_REALM_INIT=true ./deploy.sh onauth
docker logs -f stage-onauth     # until "Rule metadata is stored - /sps/conf/realms/smart"
./deploy.sh onauth              # recreates it with ONAUTH_REALM_INIT=false, or every restart reloads again
```

## Migration from the October 2026 setup (once)

Before: `stage-fhir`/`ehr-fhir` with `stage-repo-mongo` (old `deploy/kroniq`), Node SHL servers `stage-shl` (7703) and
`shl-ehr` (7708), `stage-smart-cds` (7701), `stage-onauth` with `stage-onauth-redis` (stage-onauth's compose),
`stage-smart-apps`.

1. Images pushed (above), `package.py`, folder copied to the server.
2. `./migrate-oct2026.sh`: removes those containers (not their volumes), deploys shared-infra and the repositories,
   onAuth with its realm reloaded from the files, then the apps. Everyone is logged out. The FAIR Data Point loses MongoDB
   for the minute between `stage-repo-mongo` going and `stage-mongo` coming up.
3. nginx: the locations of `nginx-locations.conf` in place of the current STAGE ones, `nginx -t && systemctl reload nginx`.
4. Checks below. Later, when no longer needed: `docker volume rm stage-fhir_stage-shl-data stage-fhir_shl-ehr-data`
   (test links of the Node SHL servers).

Realm `smart` after the reload is the one of the files: clients `liva` and `abacus` have the secrets of the files (give
them to LIVA and ABACUS, or put the secrets they have into the files before), users as in the files (`stage_admin`,
`liva_admin`, `abacus_admin`, test patients and practitioner).

## Checks

```
curl -s https://kroniq.srdc.com.tr/stage-fhir/metadata | head -c 100
curl -s https://kroniq.srdc.com.tr/ehr/cds-hooks/cds-services | head -c 100
curl -s https://kroniq.srdc.com.tr/stage-onauth/api/smart/.well-known/openid-configuration | head -c 100
curl -s https://kroniq.srdc.com.tr/stage-fhir/.well-known/jwks.json      # public keys only (no "d")
curl -si -X POST https://kroniq.srdc.com.tr/shl-ehr/shl/unknown/m -H "Content-Type: application/json" -d '{"recipient":"test"}' | head -1   # 404
```

Then https://kroniq.srdc.com.tr/smart-apps/dashboard/ (login STAGE and EHR). The SHL conformance tests
(`deploy/testing/shl-conformance`, `SHL_URL=https://kroniq.srdc.com.tr/stage-shl`) run against the server too; they
create test links.

## ML services (later)

Images `stage/shap-client` and `stage/fhir-rag-med` pushed, onfhir-feast jar in `ml/onfhir-feast` (package.py), then
`./deploy.sh ml`. The repositories already point to them (`fhir/docker-compose.yml`); onfhir-feast reads the
repositories with client `stage-services` (realm `smart`, added by package.py) and keeps its jobs in `stage-db`.
