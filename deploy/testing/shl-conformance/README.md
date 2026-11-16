# SMART Health Links server: conformance tests

Black-box HTTP tests of a SMART Health Links server: the repository's own (`srdc.stage.shl`, at `<server>/shl`), which
replaced the Node `shl-server`. Written against the Node server first, so they pin the contract the apps
(`smart-apps/projects/common/.../shl-api.service.ts`, ng-smart-on-fhir's SHL receiver) and external SHL receivers rely on;
both servers pass them.

## What "the same" means

A server generates link ids, keys, management tokens, file location tickets and JWE nonces at random, so two servers
never produce the same bytes for a link. The tests compare everything else, files by their content (the same JSON
values), not by their bytes:

| | Checked |
|---|---|
| `POST /shl/create` | status, headers, response fields and their order; payload (`url`, `key`, `exp`, `flag`, `label`, in this order); `shlink:/` = base64url (no padding) of the payload JSON; QR code decodes to the `shlink`, error correction M, 400×400, SMART logo; every validation error and its message |
| Files | JWE parameters (`alg` dir, `enc` A256GCM, `cty`), 12-byte nonce, a new nonce per file; decrypted content with the same JSON values as shared (numbers compared as numbers), strings as is; file order and content types |
| Receivers | manifest (`status`, `files`, `embedded`/`location`, `lastUpdated`), `embeddedLengthMax`, file locations (reusable, per link, 404 when unknown), flag U (`GET ?recipient`), 405 + `Allow`, expiry, CORS, `Cache-Control: no-store`, `application/jose` without charset |
| Passcodes | 401 `{remainingAttempts}`, lifetime limit of 5 wrong passcodes (also under parallel requests), disabled link afterwards |
| Revoking | management token (403/204/404), files gone afterwards, flag U links; link owners (`/shl/owned`, revoking with the user's access token) when enabled |

`test_differential.py` sends the same requests to the server under test and to the Node server and compares the
results after removing the random parts, which also covers what the other tests do not state explicitly.

### Numbers

The Node server encrypted `JSON.stringify` of the request, so numbers were rewritten: a FHIR decimal `1.50` was shared as
`1.5` (FHIR treats the precision of decimals as significant) and integers beyond 2^53 lost digits. The repository keeps
them as written (`test_fhir_decimals_keep_their_precision`, skipped for the Node server); both hold the same values.

Links of the Node server are not migrated (test data only).

## Run

```
python -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt   # (Linux/macOS: .venv/bin/python)
# the server under test; https://stage.testing.eu uses the mkcert CA
# (or set SHL_CA_BUNDLE)
SHL_URL=https://stage.testing.eu/stage-shl .venv/Scripts/python -m pytest -q
# compared with the former Node server (docker compose --profile shl-reference up -d node-shl)
SHL_URL=https://stage.testing.eu/stage-shl SHL_REFERENCE_URL=https://stage.testing.eu/node-shl .venv/Scripts/python -m pytest -q
```

Link owner tests: `SHL_OWNER_TOKEN` and `SHL_OWNER_TOKEN_2`, access tokens of two users of the repository (e.g. two
patients logged in to STAGE through the apps). The repository validates them as its FHIR API does; the Node server needed
`SHL_OWNER_JWKS_URL`.

The tests create links on the server they run against (synthetic data, labelled as test links); run them on test
deployments only.
