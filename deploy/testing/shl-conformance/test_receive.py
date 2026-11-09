"""What SHL receivers do: manifest requests, file locations, flag U links, expiry; HTTP details they rely on (CORS)"""
import json
import time

import pytest

from shl import SAMPLE_PATIENT, SAMPLE_VC, Jwe, is_b64url_token

NOT_FOUND = {'error': 'SMART Health Link not found or no longer valid.'}


@pytest.fixture(scope='module')
def two_files(server):
    return server.created({'shc': SAMPLE_VC, 'fhir': SAMPLE_PATIENT, 'label': 'two files'})


def test_manifest(server, two_files):
    response = server.manifest(two_files['payload']['url'])
    assert response.status_code == 200
    assert response.headers['Cache-Control'] == 'no-store'
    manifest = response.json()
    assert list(manifest) == ['status', 'files'] and manifest['status'] == 'finalized'
    assert [list(f) for f in manifest['files']] == [['contentType', 'embedded', 'lastUpdated']] * 2
    # lastUpdated: the creation time, ISO 8601 in UTC with milliseconds
    assert all(len(f['lastUpdated']) == 24 and f['lastUpdated'].endswith('Z') for f in manifest['files'])
    # the stored file is served as stored: the same JWE on every request
    assert server.manifest(two_files['payload']['url']).json()['files'] == manifest['files']


def test_embedded_length_max(server, two_files):
    url = two_files['payload']['url']
    embedded = server.manifest(url).json()['files']
    shortest = min(len(f['embedded']) for f in embedded)
    mixed = server.manifest(url, {'recipient': 'x', 'embeddedLengthMax': shortest}).json()['files']
    assert ['embedded' in f for f in mixed] == [len(f['embedded']) <= shortest for f in embedded]
    located = server.manifest(url, {'recipient': 'x', 'embeddedLengthMax': 0}).json()['files']
    assert all(list(f) == ['contentType', 'location', 'lastUpdated'] for f in located)
    for entry, original in zip(located, embedded):
        prefix = f'{server.base}/shl/{two_files["id"]}/f/'
        assert entry['location'].startswith(prefix) and is_b64url_token(entry['location'][len(prefix):])
        for _ in range(2):  # usable more than once while valid
            response = server.request('GET', entry['location'])
            assert response.status_code == 200
            assert response.headers['Content-Type'] == 'application/jose'
            assert response.headers['Cache-Control'] == 'no-store'
            assert response.content.decode() == original['embedded']
    # a new location for every manifest request
    assert server.manifest(url, {'recipient': 'x', 'embeddedLengthMax': 0}).json()['files'][0]['location'] != located[0]['location']


def test_file_location_of_another_link_or_unknown(server, two_files):
    other = server.created({'shc': SAMPLE_VC})
    location = server.manifest(two_files['payload']['url'], {'recipient': 'x', 'embeddedLengthMax': 0}).json()['files'][0]['location']
    ticket = location.rsplit('/', 1)[1]
    for url in (f'{server.base}/shl/{other["id"]}/f/{ticket}', f'{server.base}/shl/{two_files["id"]}/f/{"A" * 43}'):
        response = server.request('GET', url)
        assert response.status_code == 404
        assert response.json() == {'error': 'File location not found or expired; request the manifest again.'}


@pytest.mark.parametrize('body', [{}, {'recipient': ''}, {'recipient': '   '}, {'recipient': 5}])
def test_recipient_required(server, two_files, body):
    response = server.manifest(two_files['payload']['url'], body)
    assert response.status_code == 400 and response.json() == {'error': '"recipient" is required in the manifest request.'}


@pytest.mark.parametrize('value', [-1, 1.5, '10', None])
def test_invalid_embedded_length_max(server, two_files, value):
    """Only an absent embeddedLengthMax means "no limit"; null is invalid"""
    response = server.manifest(two_files['payload']['url'], {'recipient': 'x', 'embeddedLengthMax': value})
    assert response.status_code == 400
    assert response.json() == {'error': '"embeddedLengthMax" must be a non-negative integer.'}


@pytest.mark.parametrize('link_id', ['A' * 43, 'not-a-link-id'])
def test_unknown_link(server, link_id):
    response = server.manifest(f'{server.base}/shl/{link_id}/m')
    assert response.status_code == 404 and response.json() == NOT_FOUND


def test_path_traversal(server):
    """Not a link id: never read from the data folder (a reverse proxy may already answer 404 itself)"""
    assert server.manifest(f'{server.base}/shl/..%2F..%2Fetc%2Fpasswd/m').status_code == 404


def test_get_on_a_manifest_url(server, two_files):
    response = server.request('GET', two_files['payload']['url'] + '?recipient=x')
    assert response.status_code == 405 and response.headers['Allow'] == 'POST'
    assert response.json() == {'error': 'POST a manifest request to this URL.'}


def test_flag_u(server):
    link = server.created({'fhir': SAMPLE_PATIENT, 'flag': 'U'})
    url = link['payload']['url']
    response = server.request('GET', url, params={'recipient': 'SHL conformance tests'})
    assert response.status_code == 200
    assert response.headers['Content-Type'] == 'application/jose'
    assert response.headers['Cache-Control'] == 'no-store'
    assert json.loads(Jwe(response.content.decode()).decrypt(link['payload']['key'])) == SAMPLE_PATIENT
    missing = server.request('GET', url)
    assert missing.status_code == 400 and missing.json() == {'error': '"recipient" query parameter is required.'}
    posted = server.manifest(url)
    assert posted.status_code == 405 and posted.headers['Allow'] == 'GET'
    assert posted.json() == {'error': 'This link has a single file (flag U): GET the link URL with ?recipient=...'}


def test_expired_link(server):
    link = server.created({'shc': SAMPLE_VC, 'exp': int(time.time()) + 2})
    assert server.manifest(link['payload']['url']).status_code == 200
    time.sleep(3)
    response = server.manifest(link['payload']['url'])
    assert response.status_code == 404 and response.json() == NOT_FOUND


def test_cors(server, two_files):
    """Receivers are browser apps on other origins"""
    origin = {'Origin': 'https://receiver.example'}
    response = server.request('POST', two_files['payload']['url'], json={'recipient': 'x'}, headers=origin)
    assert response.headers.get('Access-Control-Allow-Origin') == '*'
    preflight = server.request('OPTIONS', two_files['payload']['url'], headers={
        **origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type'})
    assert preflight.status_code in (200, 204)
    assert preflight.headers.get('Access-Control-Allow-Origin') == '*'
    assert 'POST' in preflight.headers.get('Access-Control-Allow-Methods', '')
    assert 'content-type' in preflight.headers.get('Access-Control-Allow-Headers', '').lower()
    for method in ('GET', 'DELETE'):
        preflight = server.request('OPTIONS', f'{server.base}/shl/{two_files["id"]}', headers={
            **origin, 'Access-Control-Request-Method': method, 'Access-Control-Request-Headers': 'authorization'})
        assert method in preflight.headers.get('Access-Control-Allow-Methods', '')
