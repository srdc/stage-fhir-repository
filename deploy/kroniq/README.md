# STAGE and EHR repositories on https://kroniq.srdc.com.tr

EHR is the second repository for the xShare (SMART Health Cards/Links) tests; it goes away afterwards.

| | STAGE | EHR |
|---|---|---|
| FHIR repository / SHC issuer | https://kroniq.srdc.com.tr/stage-fhir | https://kroniq.srdc.com.tr/ehr |
| Issuer keys | …/stage-fhir/.well-known/jwks.json | …/ehr/.well-known/jwks.json |
| SMART Health Links server | https://kroniq.srdc.com.tr/stage-shl | https://kroniq.srdc.com.tr/shl-ehr |
| Containers (port on 127.0.0.1) | stage-fhir (7702), stage-shl (7703) | ehr-fhir (7707), shl-ehr (7708) |
| MongoDB (stage-repo-mongo, network stage-network) | database of the old repository (`STAGE_DB_NAME`) | ehr |

Apps (smart-apps container, port 7700): https://kroniq.srdc.com.tr/smart-apps/risk-prediction/, …/qrisk3/,
…/dashboard/ (login clients "STAGE" with a patient context and "EHR" for clinicians without a patient).

## 1. Images

```
cd ADLIFE/onfhir && mvn -pl onfhir-common,onfhir-core,onfhir-operations,onfhir-r4,onfhir-server-r4 -am -DskipTests install
cd STAGE/stage-fhir-repository && mvn clean install -DskipTests && docker build -t nexus.srdc.com.tr:18445/stage/fhir-repository .
cd STAGE/shl-server && docker build -t nexus.srdc.com.tr:18445/stage/shl-server .
cd STAGE/smart-apps && build-docker.bat
```

(ng-smart-on-fhir first: `ng build ng-smart-on-fhir` in its repository, linked into smart-apps.)

## 2. Server

- Replaces the old compose (`stage-repo` on 7702, `stage-repo-mongo`): same `stage-network`, resource limits and
  MongoDB container, which keeps its data volume. In `.env`, set `STAGE_MONGO_VOLUME` to the volume of the old compose
  (`docker volume ls | grep vstagerepomongo`) and `STAGE_DB_NAME` to the `mongodb.db` of its `/repo/application.conf`.
  Stop the old compose without deleting volumes (`docker compose down` in its folder, no `-v`), and the container on
  7703 (/stage-shl) if it is not part of it.
- Secrets are not in git: put `fhir-conf/jwks.json`, `fhir-conf/jwks-ehr.json`,
  `fhir-conf/protected-resource-server-metadata.json` and `fhir-conf/secrets.conf` (see `fhir-conf/secrets.conf.example`)
  into `REPO_DIR`
- Copy (or check out) this repository on the server: the compose file mounts `fhir-conf/` and `application-docker.conf`
  from `../../`. Then in this folder: `docker compose --env-file .env up -d`
- smart-apps: `docker compose up -d` in `smart-apps/docker` (port 7700).
- nginx: replace the `/stage-fhir` and `/stage-shl` locations with those of `nginx-locations.conf` (adds `/ehr` and
  `/shl-ehr`), then `nginx -t && systemctl reload nginx`.

## 3. onAuth (stage-onauth, realm smart, https://kroniq.srdc.com.tr/stage-onauth/api/smart)

Its own container (port 7709, database `stage_onauth` in stage-repo-mongo, stage-network). Client `smart-test`: redirect URIs `https://kroniq.srdc.com.tr/smart-apps/risk-prediction/callback` and
`https://kroniq.srdc.com.tr/smart-apps/dashboard/callback` (and their `/login` post-logout redirects), next to the qrisk3
ones. Both repositories accept tokens with audience `fhir-repo`.

## 4. Check

```
curl -s https://kroniq.srdc.com.tr/ehr/metadata | head -c 200
curl -s https://kroniq.srdc.com.tr/stage-fhir/.well-known/jwks.json
curl -s https://kroniq.srdc.com.tr/ehr/.well-known/jwks.json
curl -si -X POST https://kroniq.srdc.com.tr/shl-ehr/shl/unknown/m -H "Content-Type: application/json" -d "{\"recipient\":\"test\"}" | head -1
```

The JWKS responses must contain only public keys (no `d`), and the last call answers `404`.
