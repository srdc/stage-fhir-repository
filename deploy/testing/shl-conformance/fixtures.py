#!/usr/bin/env python3
"""
Links created on the Node shl-server, to check that a replacement still serves them after taking over its data
(test_existing_links.py): QR codes already handed out must keep working.

  python fixtures.py --server https://stage.testing.eu/stage-shl --legacy-container stage-testing-stage-shl \
      --out ../local/shl-fixtures.json

Writes the links with their keys, passcodes and management tokens (test data only) and the expected file contents.
--legacy-container also writes a link of the first shl-server version (a file holding only the JWE of a health card)
into that container's data folder, as found in older deployments.
"""
import argparse
import base64
import json
import os
import pathlib
import subprocess
import time

from shl import SAMPLE_BUNDLE, SAMPLE_PATIENT, SAMPLE_VC, ShlServer, b64url_encode, encrypt_jwe, js_json

ATTEMPTS = 5


def files_of(server, link, passcode=None):
    return [{'contentType': f['contentType'], 'plaintext': base64.b64encode(f['plaintext']).decode(),
             'lastUpdated': f['entry']['lastUpdated'], 'jwe': f['jwe']}
            for f in server.files(link, passcode=passcode)]


def fixture(kind, link, **extra):
    return {'kind': kind, 'id': link['id'], 'payload': link['payload'], 'shlink': link['shlink'],
            'managementToken': link['managementToken'], **extra}


def create(server: ShlServer, legacy_container: str | None, legacy_dir: str):
    out = []
    link = server.created({'shc': SAMPLE_VC, 'fhir': [SAMPLE_BUNDLE, SAMPLE_PATIENT], 'label': 'Ayşe Yılmaz\'s data for IPS'})
    out.append(fixture('plain', link, files=files_of(server, link)))

    link = server.created({'shc': SAMPLE_VC, 'passcode': 'pw', 'flag': 'L', 'exp': int(time.time()) + 365 * 86400})
    for _ in range(2):
        server.manifest(link['payload']['url'], {'recipient': 'fixtures', 'passcode': 'wrong'})
    out.append(fixture('passcode', link, passcode='pw', remainingAttempts=ATTEMPTS - 2, files=files_of(server, link, 'pw')))

    link = server.created({'fhir': SAMPLE_PATIENT, 'flag': 'U'})
    response = server.request('GET', link['payload']['url'], params={'recipient': 'fixtures'})
    out.append(fixture('flag-u', link, files=[{'contentType': 'application/fhir+json', 'jwe': response.content.decode(),
                                              'plaintext': base64.b64encode(js_json(SAMPLE_PATIENT).encode()).decode()}]))

    link = server.created({'shc': SAMPLE_VC})
    assert server.revoke(link['id'], link['managementToken']).status_code == 204
    out.append(fixture('revoked', link))

    link = server.created({'shc': SAMPLE_VC, 'passcode': 'pw'})
    for _ in range(ATTEMPTS):
        server.manifest(link['payload']['url'], {'recipient': 'fixtures', 'passcode': 'wrong'})
    out.append(fixture('passcode-exhausted', link, passcode='pw'))

    link = server.created({'shc': SAMPLE_VC, 'exp': int(time.time()) + 2})
    out.append(fixture('expired', link))

    if legacy_container:
        # first shl-server version: <data folder>/<id> holds the JWE of a SMART Health Card; the key was only in the link
        key, link_id = b64url_encode(os.urandom(32)), b64url_encode(os.urandom(32))
        content = js_json({'verifiableCredential': [SAMPLE_VC]}).encode()
        jwe = encrypt_jwe(content, key, 'application/smart-health-card')
        subprocess.run(['docker', 'exec', '-i', legacy_container, 'sh', '-c', f'cat > {legacy_dir}/{link_id}'],
                       input=jwe.encode(), check=True)
        payload = {'url': f'{server.base}/shl/{link_id}/m', 'key': key, 'label': 'legacy'}
        out.append({'kind': 'legacy', 'id': link_id, 'payload': payload,
                    'shlink': 'shlink:/' + b64url_encode(js_json(payload).encode()), 'managementToken': None,
                    'files': [{'contentType': 'application/smart-health-card', 'jwe': jwe,
                               'plaintext': base64.b64encode(content).decode()}]})
    time.sleep(3)  # the 'expired' link has expired
    return {'server': server.base, 'createdAt': int(time.time()), 'links': out}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--server', required=True, help='the Node shl-server (its PUBLIC_BASE_URL)')
    parser.add_argument('--legacy-container', help='docker container of that server, to add a legacy link file')
    parser.add_argument('--legacy-dir', default='/home/node/app/shc', help='its data folder (SHL_DATA_DIR)')
    parser.add_argument('--out', default=str(pathlib.Path(__file__).parent.parent / 'local' / 'shl-fixtures.json'))
    args = parser.parse_args()
    result = create(ShlServer(args.server), args.legacy_container, args.legacy_dir)
    pathlib.Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(args.out).write_text(json.dumps(result, indent=1, ensure_ascii=False), encoding='utf-8')
    print(f'{len(result["links"])} links written to {args.out}: {", ".join(link["kind"] for link in result["links"])}')
