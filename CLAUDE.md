# CLAUDE.md

Guidance for Claude Code (and developers) working on the STAGE FHIR repository, especially when setting it up from
scratch. The READMEs are the reference; this file says where to look and what goes wrong.

## What this is

An onFHIR 3.4.1 (Scala 2.13, Java 11) FHIR R4 server for STAGE that also serves:

- the STAGE CDS Hooks services (`src/main/scala/srdc/stage/cds`, `cds-conf/`) under `<server>/cds-hooks`;
- SMART Health Cards issuing (`Patient/[id]/$health-cards-issue`, from onFHIR);
- SMART Health Links (`src/main/scala/srdc/stage/shl`, `<server>/shl`, links in MongoDB collection `shl_links`).

It runs as two instances, STAGE and EHR, to demonstrate sharing data between organisations. Deployments:
`deploy/kroniq` (the server, https://kroniq.srdc.com.tr) and `deploy/testing` (a local copy of it on
https://stage.testing.eu, with onAuth, the apps and the ML services). Read `README.md`, then the README of the
deployment you work on.

## Related repositories: check out, pull, use the right branch

The project needs several repositories side by side in one workspace folder. The table, clone commands and branches
are in `README.md`, "Related repositories". Before building or running:

1. Check that every checkout of that table exists next to this repository, is on the listed branch
   (`git -C ../<checkout> branch --show-current`) and is up to date (`git -C ../<checkout> pull`). Ask the developer
   before switching the branch of a checkout that has local changes.
2. onFHIR and onfhir-cds are SNAPSHOT dependencies resolved from `~/.m2`: after pulling them, `mvn install` them again
   (commands in README "Build") before building this repository, or it compiles against stale jars.
3. smart-apps installs `ng-smart-on-fhir` from npm (version in its `package.json`). Only when changing the library:
   build it in its checkout (`npm run build -- ng-smart-on-fhir`) and link its `dist/ng-smart-on-fhir` as
   `smart-apps/node_modules/ng-smart-on-fhir`; then publish a new version (from cmd or PowerShell: npm's browser/passkey
   confirmation does not work in Git Bash) and point `package.json` to it, so others do not build against an
   unpublished library. `ls -la smart-apps/node_modules | grep ng-smart` shows whether a link is in place.
4. Checkouts in another place: set `SHAP_CLIENT_DIR`, `FHIR_RAG_MED_DIR`, `ONFHIR_FEAST_DIR` in `deploy/testing/.env`
   (gitignored), `STAGE_ONAUTH_DIR` for `setup.sh`, `FEATURE_EXTRACTION_DIR` for `seed.py`. Never write a developer's own
   absolute paths into tracked files.

## Secrets: never in git, never printed

Provided by the team, gitignored, see `README.md` "Secrets" and the `*.example` files:
`fhir-conf/jwks.json`, `fhir-conf/jwks-ehr.json` (card signing keys), `fhir-conf/protected-resource-server-metadata.json`,
`fhir-conf/secrets.conf` (onAuth token secret, LLM client secret). `deploy/testing/setup.sh` generates the rest into
`deploy/testing/local/` (gitignored). Do not commit, echo or `cat` these files; to check a value, compare lengths or
hashes. Do not print test users' passwords from the onAuth realm either.

## From scratch: local simulation (`deploy/testing/README.md`)

Prerequisites: JDK 11+, Maven 3.9, Node 20 (Angular CLI via `npx`), Python 3, Docker Desktop, mkcert, `127.0.0.1
stage.testing.eu` in the hosts file.

1. Checkouts and secrets (above); `mkcert -install`.
2. `cd deploy/testing && ./setup.sh`.
3. Images: the repository (`mvn package` + `docker build -t stage/fhir-repository:testing .`), smart-apps (libraries
   `cds-hooks`, `ui-components`, `common` first, then each app with its `--base-href`/`--deploy-url`, then
   `docker build -t srdc/smart-apps:testing -f ./docker/Dockerfile .`), onAuth from `stage-onauth`, the ML services.
4. `docker compose up -d`; `python seed.py load` for the test patients.
5. Checks: README section 4; SHL conformance tests in `deploy/testing/shl-conformance`.

## Pitfalls

- Windows Git Bash rewrites arguments starting with `/`: `export MSYS_NO_PATHCONV=1` before
  `ng build ... --base-href /smart-apps/<app>/`, then check the served `index.html` has the right `<base href>`.
- After changing `ui-components` or `common` in smart-apps, rebuild the library and then every deployed app that uses
  it (e.g. dashboard and risk-prediction), then the smart-apps image.
- onAuth stores its realm on the first start only: after `setup.sh` changes it, drop its databases and flush Redis
  database 0 (commands in the testing README). On a first start it may time out: `docker restart stage-testing-onauth`.
- Shared Redis: database 0 is onAuth's, 1 fhir-rag-med's. Use `FLUSHDB` on the right database, never `FLUSHALL`; no
  `maxmemory` eviction.
- Restoring a Redis `dump.rdb` into a volume with `appendonly yes` and no AOF starts empty: load it with appendonly off,
  then `CONFIG SET appendonly yes`.
- Repository configuration (`fhir-conf`) is reloaded on restart; a resource type that patients must read needs to be in
  `fhir-conf/compartment/Patient.json` and the CapabilityStatement, and in onAuth's resource types.
- Many files use CRLF: edit without rewriting line endings; check with `git diff --stat --ignore-cr-at-eol`.

## Conventions

- Commit only when asked; do not push unless asked. Do not change onFHIR (a separate repository) from here.
- Test data are synthetic (tagged HTEST); the SHL tests create links on the server they run against, so run them on
  test deployments only.
