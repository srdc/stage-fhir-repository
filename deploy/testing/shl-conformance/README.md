# SMART Health Links server: conformance tests

Black-box HTTP tests of the SHL server, written against the Node `shl-server` (the version deployed on kroniq and in the
local simulation) so that a replacement (the planned Scala implementation in the repository) can be checked against it
before it takes over. They pin the contract the apps (`smart-apps/projects/common/.../shl-api.service.ts`,
ng-smart-on-fhir's SHL receiver) and external SHL receivers rely on.

## What "the same" means

A server generates link ids, keys, management tokens, file location tickets and JWE nonces at random, so two servers
never produce the same bytes for a link. The tests compare everything else:

| | Checked |
|---|---|
| `POST /shl/create` | status, headers, response fields and their order; payload (`url`, `key`, `exp`, `flag`, `label`, in this order); `shlink:/` = base64url of exactly `JSON.stringify(payload)`; QR code decodes to the `shlink`, error correction M, 400×400, SMART logo; every validation error and its message |
| Files | JWE header bytes (`{"alg":"dir","enc":"A256GCM","cty":...}`), 12-byte nonce, a new nonce per file; **decrypted content byte-identical** to the Node server's (`JSON.stringify` of the request JSON, see below); file order and content types |
| Receivers | manifest (`status`, `files`, `embedded`/`location`, `lastUpdated`), `embeddedLengthMax`, file locations (reusable, per link, 404 when unknown), flag U (`GET ?recipient`), 405 + `Allow`, expiry, CORS, `Cache-Control: no-store`, `application/jose` without charset |
| Passcodes | 401 `{remainingAttempts}`, lifetime limit of 5 wrong passcodes (also under parallel requests), disabled link afterwards |
| Revoking | management token (403/204/404), files gone afterwards, flag U links; link owners (`/shl/owned`, revoking with the user's access token) when enabled |
| Existing links | links created by the Node server (all kinds, plus a link file of the first version) are served unchanged after the data is migrated: same JWEs, passcodes, attempts left, revocations, management tokens |

`test_differential.py` sends the same requests to the server under test and to the Node server and compares the
results after removing the random parts, which also covers what the other tests do not state explicitly.

### Content serialization (a decision for the replacement)

The Node server parses the request and encrypts `JSON.stringify` of it, so numbers are rewritten: a FHIR decimal
`1.50` is shared as `1.5` (FHIR treats the precision of decimals as significant), integers beyond 2^53 lose digits,
`1e21` becomes `1e+21`. `test_create.py::test_content_is_serialized_as_json_stringify` and the `numbers` case of
`test_differential.py` pin this. A replacement that keeps the request's original text is more correct but not
identical: if that is chosen, change these two tests deliberately.

## Run

```
python -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt   # (Linux/macOS: .venv/bin/python)
# the server under test; https://stage.testing.eu uses the mkcert CA (or set SHL_CA_BUNDLE)
SHL_URL=https://stage.testing.eu/stage-shl .venv/Scripts/python -m pytest -q
# against the Node server as well
SHL_URL=<replacement> SHL_REFERENCE_URL=https://stage.testing.eu/stage-shl .venv/Scripts/python -m pytest -q
```

Link owner tests: `SHL_OWNER_TOKEN` and `SHL_OWNER_TOKEN_2`, access tokens of two users, on a server with
`SHL_OWNER_JWKS_URL` (disabled in the local simulation).

Existing links (before switching a deployment to the replacement):

```
# 1. links of every kind on the Node server, plus a first-version link file in its data volume (local/ is gitignored)
.venv/Scripts/python fixtures.py --server https://stage.testing.eu/stage-shl --legacy-container stage-testing-stage-shl
# 2. migrate the Node server's data to the replacement, then
SHL_URL=<replacement> SHL_FIXTURES=../local/shl-fixtures.json .venv/Scripts/python -m pytest -q test_existing_links.py
# once per fixture set, also the checks that use attempts and revoke links
SHL_FIXTURES_STATEFUL=1 ...
```

The tests create links on the server they run against (synthetic data, labelled as test links); run them on test
deployments only.
