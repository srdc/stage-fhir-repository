# STAGE FHIR Repository

An [onFHIR](https://onfhir.io) R4 repository for STAGE, simplified from `adlife-fhir-repository`: no ADLIFE / CarePath /
HS Monitor configurations, CDS services or custom operations, only what the STAGE apps use, plus SMART Health Cards
issuing (`Patient/[id]/$health-cards-issue`). CDS stays in `smart-cds`, which works with either repository: it takes the
FHIR server and token from each CDS request.

It can run as two separate repositories, STAGE and EHR (own port, database and signing key), to demonstrate sharing data between
organisations with SMART Health Cards / Links.

## Layout

| Path | Content |
|---|---|
| `fhir-conf/capabilitystatement.json` | Capabilities of the repository |
| `fhir-conf/compartment/` | Patient and Practitioner compartments |
| `fhir-conf/operation/` | `$account-details`, `$admin-panel-patient-list`, `$delete-account`, `$distinct-pages` (`$health-cards-issue` comes with onFHIR) |
| `fhir-conf/searchparameter/` | `Goal.target-code`, `Observation.codeText`, `Questionnaire.questionnaire-code`, `context`, `language`, `RiskAssessment.code` |
| `fhir-conf/questionnaire/` | STAGE questionnaires (WHO-5, life space mobility, ...), stored at startup |
| `fhir-conf/valueset/` | STAGE ValueSets, stored at startup (see below) |
| `fhir-conf/*.json` | onAuth / SMART metadata, `jwks.json` (STAGE keys) and `jwks-ehr.json` (EHR keys) |
| `scripts/generate_valuesets.py` | Regenerates `fhir-conf/valueset` |
| `src/main/scala` | Boot, the four operations, startup storage of ValueSets / Questionnaires |

### ValueSets

`scripts/generate_valuesets.py` (Python with `openpyxl`, run from the STAGE folder) builds them from:

- the *ValueSets* sheet of `Documents/WP7/profiling/FHIR_profiles.xlsx`;
- `Documents/WP7/profiling/liva-goal-valueset.xlsx` (`liva-goal`; the intervention name is a designation);
- the smart-cds CDS services: one `shc-<service>` ValueSet per service with the codes of the data it uses, the
  `credentialValueSet` of its Smart Health Card (`http://stage-healthyaging.eu/fhir/ValueSet/shc-<service>`; the
  smart-apps use them when `healthCardValueSetBaseUrl` is set to `http://stage-healthyaging.eu/fhir/ValueSet/`);
- `smart-cds/valueset` (copied as is).

## Build

onFHIR must be the 3.4.1 (Scala 2.13) line with the SMART Health Cards changes (`ADLIFE/onfhir`, branch `smart-health-cards-2.13`):

```
cd ../../ADLIFE/onfhir && mvn -pl onfhir-common,onfhir-core,onfhir-operations,onfhir-r4,onfhir-server-r4 -am -DskipTests install
mvn clean install -DskipTests
```

## Secrets (not in git)

Gitignored, to be provided on each machine (keep them out of commits):

- `fhir-conf/jwks.json`, `fhir-conf/jwks-ehr.json`: the private EC keys signing the SMART Health Cards;
- `fhir-conf/protected-resource-server-metadata.json`: the onAuth client registration of the repository
  (see `fhir-conf/protected-resource-server-metadata.example.json`);
- `fhir-conf/secrets.conf`: other secret settings, e.g. the HS256 secret of the onAuth access tokens
  (see `fhir-conf/secrets.conf.example`); included by `application.conf` and `application-docker.conf`.

## Run (MongoDB on localhost:27017, from this folder)

```
java -Dconfig.file=application-stage.conf -jar target/stage-fhir-repository-standalone.jar
java -Dconfig.file=application-ehr.conf -jar target/stage-fhir-repository-standalone.jar
```

| | STAGE | EHR |
|---|---|---|
| FHIR | http://127.0.0.1:8080/fhir | http://127.0.0.1:8180/fhir |
| Database | `stage` | `ehr` |
| Card signing key (`kid`) | `fhir-conf/jwks.json` (`Fd_ncCoG...`) | `fhir-conf/jwks-ehr.json` (`ctJZJqbA...`) |
| Issuer keys for verifiers | `<root>/.well-known/jwks` | `<root>/.well-known/jwks.json` |
| SHL server (`shl-server`) | `npm run start:stage` (port 3000) | `npm run start:ehr` (port 3001) |

Both repositories use the same onAuth (`fhir-conf/*-local.json` locally); in a real setup each organisation has its own.

Docker: `docker-compose.yml` runs MongoDB, both repositories (`application-docker.conf`, per-EHR values in the
environment: `FHIR_ROOT_URL`, `DB_NAME`, `JWKS_PATH`, `SHC_SIGNING_KEY_ID`) and an SHL server per repository. Deployment on kroniq.srdc.com.tr: `deploy/kroniq`.
