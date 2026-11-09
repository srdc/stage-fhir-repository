"""POST /shl/create: the link (payload, shlink:/ URI, QR code, management token) and what is encrypted"""
import base64
import json
import time
from datetime import datetime, timezone

import pytest

from shl import (SAMPLE_BUNDLE, SAMPLE_PATIENT, SAMPLE_VC, Jwe, b64url_encode, decode_qr, decode_shlink,
                 content_value, is_b64url_token, is_node)

SHC = 'application/smart-health-card'
FHIR = 'application/fhir+json'


def test_response_shape(server):
    response = server.create({'shc': {'verifiableCredential': [SAMPLE_VC]}, 'label': 'Ayşe Yılmaz\'s data for IPS'})
    assert response.status_code == 201
    assert response.headers['Cache-Control'] == 'no-store'
    assert response.headers['Content-Type'].startswith('application/json')
    link = response.json()
    assert list(link) == ['id', 'payload', 'manifest', 'shlink', 'qr', 'managementToken', 'ownerBound']
    assert is_b64url_token(link['id']) and is_b64url_token(link['managementToken'])
    assert link['manifest'] == link['payload']  # kept for older clients
    assert link['ownerBound'] is False
    payload = link['payload']
    assert list(payload) == ['url', 'key', 'label']
    assert payload['url'] == f'{server.base}/shl/{link["id"]}/m'
    assert is_b64url_token(payload['key']) and len(base64.urlsafe_b64decode(payload['key'] + '=')) == 32
    assert payload['label'] == 'Ayşe Yılmaz\'s data for IPS'
    assert len({link['id'], payload['key'], link['managementToken']}) == 3


def test_also_created_at_shl_root(server):
    response = server.create({'shc': SAMPLE_VC}, path='/shl')
    assert response.status_code == 201 and response.json()['payload']['url'].endswith('/m')


def test_shlink_encodes_the_payload(server):
    """shlink:/ + base64url (no padding) of the payload JSON; payload fields in the order url, key, exp, flag, label"""
    exp = int(time.time()) + 3600
    link = server.created({'shc': SAMPLE_VC, 'label': 'Yılmaz 😀', 'exp': exp, 'flag': 'L', 'passcode': '1234'})
    payload = link['payload']
    assert list(payload) == ['url', 'key', 'exp', 'flag', 'label']
    assert payload['exp'] == exp and payload['flag'] == 'LP'
    raw, decoded = decode_shlink(link['shlink'])
    assert decoded == payload and list(decoded) == list(payload)
    encoded = link['shlink'][len('shlink:/'):]
    assert not set(encoded) & set('=+/') and b64url_encode(raw) == encoded


def test_qr_code(server):
    link = server.created({'shc': SAMPLE_VC, 'label': 'QR'})
    text, ec_level, size, image = decode_qr(link['qr'])
    assert text == link['shlink']
    assert ec_level in (None, 'M')  # error correction M (the SMART logo covers the centre)
    assert size == (400, 400)
    # the SMART logo in the centre: coloured pixels, which a black and white QR code does not have
    centre = image.convert('RGB').crop((170, 170, 230, 230))
    assert any(max(pixel) - min(pixel) > 60 for pixel in list(centre.getdata())), 'no logo in the centre of the QR code'


@pytest.mark.parametrize('shc', [
    SAMPLE_VC,
    [SAMPLE_VC, SAMPLE_VC + 'x'],
    {'verifiableCredential': [SAMPLE_VC]},
], ids=['jws', 'jws-array', 'shc-file'])
def test_shc_file(server, shc):
    link = server.created({'shc': shc})
    [file] = server.files(link)
    expected = {'verifiableCredential': [shc] if isinstance(shc, str) else shc if isinstance(shc, list) else shc['verifiableCredential']}
    assert file['contentType'] == SHC
    assert json.loads(file['plaintext']) == expected
    assert Jwe(file['jwe']).header == {'alg': 'dir', 'enc': 'A256GCM', 'cty': 'application/smart-health-card'}


def test_files_in_order_and_content_types(server):
    """shc first, then each fhir resource, then files; a string content is encrypted as is"""
    link = server.created({
        'files': [{'contentType': FHIR + ';fhirVersion=4.0.1', 'content': SAMPLE_PATIENT},
                  {'contentType': 'application/smart-api-access', 'content': '{"aud":"x"}'}],
        'fhir': [SAMPLE_BUNDLE, SAMPLE_PATIENT],
        'shc': SAMPLE_VC,
    })
    files = server.files(link)
    assert [f['contentType'] for f in files] == [SHC, FHIR, FHIR, FHIR + ';fhirVersion=4.0.1', 'application/smart-api-access']
    assert [Jwe(f['jwe']).header['cty'] for f in files] == [f['contentType'] for f in files]
    assert json.loads(files[1]['plaintext']) == SAMPLE_BUNDLE
    assert json.loads(files[3]['plaintext']) == SAMPLE_PATIENT
    assert files[4]['plaintext'] == b'{"aud":"x"}'
    # a new nonce for every encryption
    assert len({Jwe(f['jwe']).iv_b64 for f in files}) == len(files)


def test_single_fhir_resource(server):
    [file] = server.files(server.created({'fhir': SAMPLE_PATIENT}))
    assert file['contentType'] == FHIR and json.loads(file['plaintext']) == SAMPLE_PATIENT


TRICKY_JSON = ('{"resourceType":"Observation","valueQuantity":{"value":1.50,"unit":"mmol/L"},"a":1.0,"b":1e21,'
               '"c":1e-7,"d":-0,"e":12345678901234567890,"f":0.1,"g":2.50e2,"h":"Yılmaz 😀 </script> \\u2028",'
               '"i":[true,false,null],"j":{},"k":[]}')


def test_content_keeps_its_json_values(server):
    """Numbers, Unicode, escapes, empty objects and arrays: the shared file holds the same JSON values"""
    response = server.create(raw=('{"fhir":' + TRICKY_JSON + '}').encode('utf-8'))
    assert response.status_code == 201
    [file] = server.files(response.json())
    assert content_value(file['plaintext']) == content_value(TRICKY_JSON.encode('utf-8'))
    assert list(json.loads(file['plaintext'])) == list(json.loads(TRICKY_JSON))  # member order


def test_fhir_decimals_keep_their_precision(server):
    """FHIR decimals are shared as written (1.50 stays 1.50); the Node server rewrote them (1.5), see README.md"""
    response = server.create(raw=('{"fhir":' + TRICKY_JSON + '}').encode('utf-8'))
    if is_node(response):
        pytest.skip('the Node shl-server re-serializes numbers (JSON.stringify)')
    [file] = server.files(response.json())
    text = file['plaintext'].decode('utf-8')
    assert '"value":1.50' in text and '12345678901234567890' in text


@pytest.mark.parametrize('requested,passcode,expected', [
    (None, None, None), ('', None, None), ('L', None, 'L'), ('l', None, 'L'), ('U', None, 'U'), ('UL', None, 'LU'),
    (None, 'pw', 'P'), ('P', 'pw', 'P'), ('L', 'pw', 'LP'), ('PL', 'pw', 'LP'),
])
def test_flags(server, requested, passcode, expected):
    body = {'shc': SAMPLE_VC}
    if requested is not None:
        body['flag'] = requested
    if passcode is not None:
        body['passcode'] = passcode
    payload = server.created(body)['payload']
    assert payload.get('flag') == expected
    assert 'flag' in payload or expected is None


@pytest.mark.parametrize('exp', ['iso', 'float'])
def test_expiration_formats(server, exp):
    seconds = int(time.time()) + 7200
    value = datetime.fromtimestamp(seconds, timezone.utc).isoformat().replace('+00:00', 'Z') if exp == 'iso' else seconds + 0.9
    assert server.created({'shc': SAMPLE_VC, 'exp': value})['payload']['exp'] == seconds


def test_null_passcode_and_empty_exp_are_ignored(server):
    payload = server.created({'shc': SAMPLE_VC, 'passcode': None, 'exp': ''})['payload']
    assert 'flag' not in payload and 'exp' not in payload


INVALID = [
    ({}, 'Nothing to share: provide "shc", "fhir" or "files".'),
    ({'files': []}, 'Nothing to share: provide "shc", "fhir" or "files".'),
    ({'shc': {}}, '"shc" must hold at least one verifiable credential (compact JWS).'),
    ({'shc': []}, '"shc" must hold at least one verifiable credential (compact JWS).'),
    ({'shc': [SAMPLE_VC, 1]}, '"shc" must hold at least one verifiable credential (compact JWS).'),
    ({'shc': ''}, '"shc" must hold at least one verifiable credential (compact JWS).'),
    ({'fhir': {'id': 'x'}}, '"fhir" must hold FHIR resources.'),
    ({'fhir': [SAMPLE_PATIENT, 'x']}, '"fhir" must hold FHIR resources.'),
    ({'files': [{'contentType': 'text/plain', 'content': 'x'}]},
     'Unsupported file: contentType must be application/smart-health-card, application/fhir+json or '
     'application/smart-api-access, with content.'),
    ({'files': [{'contentType': FHIR}]},
     'Unsupported file: contentType must be application/smart-health-card, application/fhir+json or '
     'application/smart-api-access, with content.'),
    ({'shc': SAMPLE_VC, 'label': 'x' * 81}, '"label" must be a string of at most 80 characters.'),
    ({'shc': SAMPLE_VC, 'label': 5}, '"label" must be a string of at most 80 characters.'),
    ({'shc': SAMPLE_VC, 'passcode': ''}, '"passcode" must be a non-empty string.'),
    ({'shc': SAMPLE_VC, 'passcode': 1234}, '"passcode" must be a non-empty string.'),
    ({'shc': SAMPLE_VC, 'exp': 1000}, '"exp" must be a future time (epoch seconds or ISO date time).'),
    ({'shc': SAMPLE_VC, 'exp': 'tomorrow'}, '"exp" must be a future time (epoch seconds or ISO date time).'),
    ({'shc': SAMPLE_VC, 'flag': 'LX'}, '"flag" may only contain L, P and U.'),
    ({'shc': SAMPLE_VC, 'flag': 'P'}, 'Flag P needs a passcode.'),
    ({'shc': SAMPLE_VC, 'flag': 'U', 'passcode': 'pw'}, 'Flag U cannot be combined with a passcode (P).'),
    ({'shc': SAMPLE_VC, 'fhir': SAMPLE_PATIENT, 'flag': 'U'}, 'Flag U needs exactly one file.'),
]


@pytest.mark.parametrize('body,message', INVALID, ids=[str(i) for i in range(len(INVALID))])
def test_invalid_requests(server, body, message):
    response = server.create(body)
    assert response.status_code == 400
    assert response.headers['Cache-Control'] == 'no-store'
    assert response.json() == {'error': message}


def test_label_of_80_characters_is_accepted(server):
    assert server.created({'shc': SAMPLE_VC, 'label': 'x' * 80})['payload']['label'] == 'x' * 80


def test_malformed_json(server):
    assert server.create(raw=b'{"shc": ').status_code == 400


def test_large_body(server):
    """Patient summaries can be large: at least 5 MB are accepted (the Node default limit is 10 MB)"""
    big = dict(SAMPLE_PATIENT, text={'status': 'generated', 'div': '<div>' + 'x' * 5_000_000 + '</div>'})
    link = server.created({'fhir': big})
    [file] = server.files(link, embedded_length_max=0)
    assert json.loads(file['plaintext']) == big
