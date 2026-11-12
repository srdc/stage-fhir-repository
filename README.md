# STAGE FHIR Repository

An [onFHIR](https://onfhir.io) R4 repository for STAGE, simplified from `adlife-fhir-repository`: no ADLIFE / CarePath /
HS Monitor configurations, CDS services or custom operations, only what the STAGE apps use, plus SMART Health Cards
issuing (`Patient/[id]/$health-cards-issue`) and the STAGE CDS Hooks services, served by the same server under
`<server>/cds-hooks/cds-services`. The CDS services were the separate `smart-cds` project (imported here with its history
from its `onfhir-feast-integration` branch); they use this server's FHIR configuration.

CDS service calls that need patient data (a `patientId` in the context, or prefetches the server has to fetch) use the
FHIR API rights of the caller (`CdsSecurity`): the request must carry the app's SMART access token
(`fhirAuthorization.access_token`, or a Bearer header), the token must be able to read `Patient/{patientId}`, and the
missing prefetches are read from this server's FHIR API with that token. Calls on supplied data only (e.g. `definition`,
`valueset`) and the discovery endpoint are open. Off when `fhir.authorization.method = none`; see `stage.cds.security`
in `application.conf`.

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
| `cds-conf/` | CDS Hooks service and hook definitions, concept definitions and card templates (`definitions/`), bundles and ValueSets the services read (`bundle/`, `valueset/`) |
| `scripts/generate_valuesets.py` | Regenerates `fhir-conf/valueset` |
| `src/main/scala/srdc/stage/fhir` | Boot, the four operations, startup storage of ValueSets / Questionnaires |
| `src/main/scala/srdc/stage/cds` | CDS Hooks services (formerly `smart-cds`), `CdsModule` builds their route |

### ValueSets

`scripts/generate_valuesets.py` (Python with `openpyxl`, run from the workspace) builds them from the STAGE project
documents (not in git), which it expects in `Documents/` of the workspace:

- the *ValueSets* sheet of `Documents/WP7/profiling/FHIR_profiles.xlsx`;
- `Documents/WP7/profiling/liva-goal-valueset.xlsx` (`liva-goal`; the intervention name is a designation);
- the CDS services (`cds-conf`): one `shc-<service>` ValueSet per service with the codes of the data it uses, the
  `credentialValueSet` of its Smart Health Card (`http://stage-healthyaging.eu/fhir/ValueSet/shc-<service>`; the
  smart-apps use them when `healthCardValueSetBaseUrl` is set to `http://stage-healthyaging.eu/fhir/ValueSet/`);
- `cds-conf/valueset` (copied as is).

## Related repositories

This repository is built and run together with other repositories, at the branches below. Check them out side by side in
one folder (the workspace); the commands of the READMEs and the defaults of `deploy/testing` assume this layout (another
layout: the `*_DIR` variables in `deploy/testing/.env`, see its README):

```
<workspace>/
  onfhir/  onfhir-cds/  stage-fhir-repository/  ng-smart-on-fhir/  smart-apps/  stage-onauth/
  shap-client/  fhir-rag-med/  onfhir-feast/  fhir-path-feature-extraction/
```

| Checkout | Repository | Branch | Version | Used for |
|---|---|---|---|---|
| `onfhir` | `git@github.com:srdc/onfhir.git` | `smart-health-cards-2.13` | 3.4.1-SNAPSHOT (Scala 2.13) | building this repository |
| `onfhir-cds` | `git@gitlab.srdc.com.tr:onfhir/onfhir-cds.git` | `onfhir-cds-2.13-stage` | 1.1-SNAPSHOT | building this repository (CDS services) |
| `stage-fhir-repository` | `git@github.com:srdc/stage-fhir-repository.git` | `merge-smart-cds` | 1.0 | this repository |
| `ng-smart-on-fhir` | `git@github.com:srdc/ng-smart-on-fhir.git` | `main` | 1.3.0 (npm) | the apps' SMART library; smart-apps installs it from npm, the checkout is only needed to change it |
| `smart-apps` | `git@github.com:srdc/smart-on-fhir-web-apps.git` | `merging-ml-llm` | | the STAGE apps |
| `stage-onauth` | `https://gitlab.srdc.com.tr/stage/stage-onauth.git` | `master` | | onAuth image and realm `smart` (`deploy/testing`) |
| `shap-client` | `git@gitlab.srdc.com.tr:onfhir/shap-client.git` | `feature/stage-model-integration` | | risk prediction models (`deploy/testing`) |
| `fhir-rag-med` | `git@gitlab.srdc.com.tr:onfhir/fhir-rag-med.git` | `master` | | LLM explanations (`deploy/testing`) |
| `onfhir-feast` | `git@gitlab.srdc.com.tr:onfhir/onfhir-feast.git` | `main` | 1.1-SNAPSHOT | feature extraction (`deploy/testing`) |
| `fhir-path-feature-extraction` | not in git: the STAGE project folder of the WP7 test patients | | | optional test patients (`deploy/testing/seed.py`) |

```
git clone -b smart-health-cards-2.13 git@github.com:srdc/onfhir.git
git clone -b onfhir-cds-2.13-stage git@gitlab.srdc.com.tr:onfhir/onfhir-cds.git
git clone -b merge-smart-cds git@github.com:srdc/stage-fhir-repository.git
git clone -b main git@github.com:srdc/ng-smart-on-fhir.git
git clone -b merging-ml-llm git@github.com:srdc/smart-on-fhir-web-apps.git smart-apps
git clone -b master https://gitlab.srdc.com.tr/stage/stage-onauth.git stage-onauth
git clone -b feature/stage-model-integration git@gitlab.srdc.com.tr:onfhir/shap-client.git
git clone -b master git@gitlab.srdc.com.tr:onfhir/fhir-rag-med.git
git clone -b main git@gitlab.srdc.com.tr:onfhir/onfhir-feast.git
```

Pull all of them before building (`git -C <checkout> pull`). onFHIR and onfhir-cds are SNAPSHOT versions: Maven uses
what was installed last into `~/.m2`, so install them again after pulling, before building this repository.

## Build

JDK 11 or later (the image runs on Java 11), Maven 3.9. In the workspace:

```
cd onfhir && mvn -pl onfhir-common,onfhir-core,onfhir-operations,onfhir-r4,onfhir-server-r4 -am -DskipTests install && cd ..
cd onfhir-cds && mvn -DskipTests install && cd ..
cd stage-fhir-repository && mvn clean install -DskipTests
```

## Secrets (not in git)

Gitignored, to be provided on each machine (keep them out of commits):

- `fhir-conf/jwks.json`, `fhir-conf/jwks-ehr.json`: the private EC keys signing the SMART Health Cards;
- `fhir-conf/protected-resource-server-metadata.json`: the onAuth client registration of the repository
  (see `fhir-conf/protected-resource-server-metadata.example.json`);
- `fhir-conf/secrets.conf`: other secret settings, e.g. the HS256 secret of the onAuth access tokens and the client
  secret of the LLM service (`ask_llm`)
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
| SMART Health Links | http://127.0.0.1:8080/shl | http://127.0.0.1:8180/shl |

Both repositories use the same onAuth (`fhir-conf/*-local.json` locally); in a real setup each organisation has its own.

Docker: `docker-compose.yml` runs MongoDB and both repositories (`application-docker.conf`, per-EHR values in the
environment: `FHIR_ROOT_URL`, `DB_NAME`, `JWKS_PATH`, `SHC_SIGNING_KEY_ID`); each repository serves its SMART Health
Links itself (`<server>/shl`). Deployment on kroniq.srdc.com.tr: `deploy/kroniq`.

## License

GNU General Public License v3.0, see `LICENSE.txt`. The CDS services implement third-party risk algorithms (QRISK3,
ADVANCE, ACC/AHA, SCORE2): their licenses, terms and sources are in `NOTICE.txt`.

## Disclaimer

This software is a research demonstration of the STAGE project. Its CDS services incorporate the QRISK, ADVANCE,
ACC/AHA and SCORE2 algorithms and machine learning risk models for illustrative purposes only. It is **not intended**
for use in real-world clinical decision-making. The results it generates should not be considered as medical advice.

## Acknowledgements

This software has been developed as part of the [STAGE project](https://stage-healthyageing.eu/).

STAGE has received funding from the European Union’s Horizon Europe Research and Innovation Programme under grant agreement nº 101137146. UK participants in Horizon Europe Project STAGE are supported by UKRI grant numbers 10112787 (Beta Technology), 10099041 (University of Bristol) and 10109957 (Imperial College London).

For more information about the project, visit the [STAGE project website](https://stage-healthyageing.eu/).
