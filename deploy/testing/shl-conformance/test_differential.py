"""
The server under test (SHL_URL) against the Node shl-server (SHL_REFERENCE_URL): the same requests, compared after
removing what is random (ids, keys, tokens, nonces, times). Catches what the other tests do not pin down explicitly.
"""
import json
import time

import pytest

from shl import SAMPLE_BUNDLE, SAMPLE_PATIENT, SAMPLE_VC, Jwe, decode_qr, decode_shlink

FUTURE = int(time.time()) + 86400

CREATE = {
    'shc-object': {'shc': {'verifiableCredential': [SAMPLE_VC]}, 'label': 'Ayşe Yılmaz\'s data for IPS'},
    'shc-string': {'shc': SAMPLE_VC},
    'fhir-array': {'fhir': [SAMPLE_BUNDLE, SAMPLE_PATIENT], 'flag': 'L', 'exp': FUTURE},
    'files': {'files': [{'contentType': 'application/fhir+json;fhirVersion=4.0.1', 'content': SAMPLE_PATIENT},
                        {'contentType': 'application/smart-api-access', 'content': 'plain text'}]},
    'passcode': {'shc': SAMPLE_VC, 'passcode': 'pw', 'flag': 'l'},
    'flag-u': {'fhir': SAMPLE_PATIENT, 'flag': 'U'},
    'numbers': ('{"fhir":{"resourceType":"Observation","valueQuantity":{"value":1.50},"a":1.0,"b":1e21,"c":-0,'
                '"d":12345678901234567890,"e":"\\u00e9\\ud83d\\ude00\\u2028"}}'),
    'unknown-fields': {'shc': SAMPLE_VC, 'extra': {'ignored': True}},
    'invalid-flag': {'shc': SAMPLE_VC, 'flag': 'Z'},
    'invalid-exp': {'shc': SAMPLE_VC, 'exp': 5},
    'nothing': {'label': 'x'},
    'label-not-string': {'shc': SAMPLE_VC, 'label': ['x']},
    'array-body': [1, 2],
}


def observe_create(server, body):
    raw = body.encode() if isinstance(body, str) else json.dumps(body, ensure_ascii=False).encode()
    response = server.create(raw=raw)
    observed = {'status': response.status_code, 'cache': response.headers.get('Cache-Control'),
                'cors': response.headers.get('Access-Control-Allow-Origin')}
    if response.status_code != 201:
        observed['body'] = response.json() if response.headers.get('Content-Type', '').startswith('application/json') else None
        return observed, None
    link = response.json()
    payload = dict(link['payload'])
    raw_payload, decoded = decode_shlink(link['shlink'])
    text, ec_level, size, _ = decode_qr(link['qr'])
    observed.update({
        'keys': list(link),
        'payload': {**payload, 'url': payload['url'].replace(server.base, '<base>').replace(link['id'], '<id>'),
                    'key': len(payload['key'])},
        'shlink-is-payload': raw_payload == json.dumps(payload, separators=(',', ':'), ensure_ascii=False).encode(),
        'qr': (text == link['shlink'], ec_level, size),
        'ownerBound': link['ownerBound'],
    })
    return observed, link


def observe_files(server, link, passcode=None):
    """Manifest and decrypted files (embedded, and through locations)"""
    if 'U' in link['payload'].get('flag', ''):
        response = server.request('GET', link['payload']['url'], params={'recipient': 'x'})
        jwe = Jwe(response.content.decode())
        return [{'status': response.status_code, 'type': response.headers['Content-Type'],
                 'header': jwe.header_bytes, 'plaintext': jwe.decrypt(link['payload']['key'])}]
    observed = []
    for limit in (None, 0):
        files = server.files(link, passcode=passcode, embedded_length_max=limit)
        observed.append([{'contentType': f['contentType'], 'entry': sorted(f['entry']), 'header': Jwe(f['jwe']).header_bytes,
                          'plaintext': f['plaintext']} for f in files])
    return observed


@pytest.mark.parametrize('name', list(CREATE))
def test_same_links(server, reference, name):
    expected, expected_link = observe_create(reference, CREATE[name])
    actual, actual_link = observe_create(server, CREATE[name])
    assert actual == expected
    if expected_link:
        passcode = 'pw' if name == 'passcode' else None
        assert observe_files(server, actual_link, passcode) == observe_files(reference, expected_link, passcode)


MANIFEST_REQUESTS = {
    'no-recipient': {},
    'embedded-negative': {'recipient': 'x', 'embeddedLengthMax': -5},
    'no-passcode': {'recipient': 'x'},
    'wrong-passcode': {'recipient': 'x', 'passcode': 'nope'},
    'right-passcode': {'recipient': 'x', 'passcode': 'pw'},
}


@pytest.mark.parametrize('name', list(MANIFEST_REQUESTS))
def test_same_manifest_answers(server, reference, name):
    def observe(target):
        link = target.created({'shc': SAMPLE_VC, 'passcode': 'pw'})
        response = target.manifest(link['payload']['url'], MANIFEST_REQUESTS[name])
        body = response.json()
        if response.status_code == 200:
            body = {'status': body['status'], 'files': [sorted(f) for f in body['files']]}
        return response.status_code, response.headers.get('Cache-Control'), body
    assert observe(server) == observe(reference)


@pytest.mark.parametrize('path,method', [('/shl/' + 'A' * 43 + '/m', 'POST'), ('/shl/' + 'A' * 43 + '/m', 'GET'),
                                         ('/shl/x/f/y', 'GET'), ('/shl/' + 'A' * 43, 'DELETE'), ('/shl/owned', 'GET')])
def test_same_answers_for_unknown_links(server, reference, path, method):
    def observe(target):
        response = target.request(method, path, json={'recipient': 'x'} if method == 'POST' else None)
        return response.status_code, response.json()
    assert observe(server) == observe(reference)
