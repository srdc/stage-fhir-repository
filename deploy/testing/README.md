# Local simulation of the STAGE server: https://stage.testing.eu

The deployment of `deploy/kroniq` on one machine, under a test domain (127.0.0.1 `stage.testing.eu` in the hosts file),
with the same paths, so the apps, onAuth and the repositories behave as on https://kroniq.srdc.com.tr:

| Path | Container |
|---|---|
| `/stage-fhir` (FHIR, CDS Hooks under `/stage-fhir/cds-hooks`) | stage-fhir (`stage/fhir-repository:testing`, database `stage`, also on 127.0.0.1:7702) |
| `/ehr` (FHIR, CDS Hooks under `/ehr/cds-hooks`) | ehr-fhir (database `ehr`, also on 127.0.0.1:7707) |
| `/stage-shl`, `/shl-ehr` | SHL servers |
| `/stage-onauth` | onAuth (realm smart, database `stage_onauth`) |
| `/smart-apps` | smart-apps (`/smart-apps/risk-prediction/`, `/qrisk3/`, `/dashboard/`) |

MongoDB (`stage-testing-mongo`, 127.0.0.1:27117) and the data are separate from any other local setup (volumes of the
`stage-testing` project). The ML services of the CDS services (shap-client :8001, fhir-rag-med :8000, onfhir-feast :8085)
are not part of it: run them on the host if needed (the repositories reach them at `host.docker.internal`, or set
`ML_SERVICE_URL`, `LLM_SERVICE_URL`, `ONFHIR_FEAST_URL` in `.env`).

## 1. Once

- `mkcert -install` (trusts the local CA in Windows; restart the browser. Firefox: also needs `certutil` / NSS, see mkcert).
- Secrets as for kroniq, in `fhir-conf/` (gitignored): `jwks.json`, `jwks-ehr.json`, `secrets.conf`,
  `protected-resource-server-metadata.json`; and a `stage-onauth` checkout next to this repository (`STAGE_ONAUTH_DIR`).
- `./setup.sh`: writes `local/` (gitignored): the certificate, the onAuth metadata of stage.testing.eu for the
  repositories, and the onAuth configuration with realm smart (redirect URIs on stage.testing.eu). Run it again after
  changing those sources.

## 2. Images

```
# repository (FHIR + CDS)
cd stage-fhir-repository && mvn package -DskipTests && docker build -t stage/fhir-repository:testing .
# apps (libraries first; build-docker.bat builds all the apps)
cd smart-apps
npx ng build cds-hooks && npx ng build ui-components && npx ng build common
npx ng build risk-prediction --configuration production --base-href /smart-apps/risk-prediction/ --deploy-url /smart-apps/risk-prediction/
npx ng build qrisk3 --configuration production --base-href /smart-apps/qrisk3/ --deploy-url /smart-apps/qrisk3/
npx ng build dashboard --configuration production --base-href /smart-apps/dashboard/ --deploy-url /smart-apps/dashboard/
docker build -t srdc/smart-apps:testing -f ./docker/Dockerfile .
```

onAuth and the SHL server: the kroniq images (`nexus.srdc.com.tr:18445/stage/onauth`, `.../stage/shl-server`); other
images via `.env` (`FHIR_IMAGE`, `SMART_APPS_IMAGE`, `ONAUTH_IMAGE`, `SHL_IMAGE`).

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

The repositories start with the STAGE conformance resources, ValueSets and Questionnaires, but no patient data: the
patients of realm smart's users have to be created (e.g. with a user/*.* token). Stop with `docker compose down` (add `-v` to delete the data).

## 4. Check

```
curl -s https://stage.testing.eu/stage-fhir/metadata | head -c 100
curl -s https://stage.testing.eu/stage-fhir/cds-hooks/cds-services | head -c 100
curl -s https://stage.testing.eu/stage-onauth/api/smart/.well-known/openid-configuration | head -c 100
```
