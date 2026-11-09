"""
Links created earlier by the Node shl-server (fixtures.py, SHL_FIXTURES) are served unchanged by the server under test,
once it has taken over the Node server's data: same files (byte-identical JWEs), passcodes, attempts left, revocations.

Checks that change the links' state (attempts, revoking) run only with SHL_FIXTURES_STATEFUL=1, once per fixture set.
SHL_URL may differ from the server the fixtures were created on: their URLs are rewritten to it.
"""
import base64
import json
import os
import pathlib

import pytest

from shl import Jwe

NOT_FOUND = {'error': 'SMART Health Link not found or no longer valid.'}
STATEFUL = os.environ.get('SHL_FIXTURES_STATEFUL') == '1'


def load():
    path = os.environ.get('SHL_FIXTURES')
    if not path:
        return None
    return json.loads(pathlib.Path(path).read_text(encoding='utf-8'))


FIXTURES = load()
pytestmark = pytest.mark.skipif(FIXTURES is None, reason='SHL_FIXTURES (fixtures.py output) is not set')


def links(*kinds):
    return [link for link in (FIXTURES or {'links': []})['links'] if link['kind'] in kinds]


def url(server, link):
    return link['payload']['url'].replace(FIXTURES['server'], server.base)


def check_files(server, link, manifest_files):
    expected = link['files']
    assert [f['contentType'] for f in manifest_files] == [f['contentType'] for f in expected]
    for entry, file in zip(manifest_files, expected):
        assert entry['embedded'] == file['jwe']  # the stored JWE, as it was
        assert Jwe(entry['embedded']).decrypt(link['payload']['key']) == base64.b64decode(file['plaintext'])
        if 'lastUpdated' in file:
            assert entry['lastUpdated'] == file['lastUpdated']


@pytest.mark.parametrize('link', links('plain', 'legacy'), ids=lambda link: link['kind'])
def test_served(server, link):
    response = server.manifest(url(server, link))
    assert response.status_code == 200, response.text
    manifest = response.json()
    assert manifest['status'] == 'finalized'
    check_files(server, link, manifest['files'])
    # through file locations as well
    located = server.manifest(url(server, link), {'recipient': 'x', 'embeddedLengthMax': 0}).json()['files']
    assert [server.request('GET', f['location']).content.decode() for f in located] == [f['jwe'] for f in link['files']]


@pytest.mark.parametrize('link', links('passcode'), ids=lambda link: link['kind'])
def test_passcode_link(server, link):
    response = server.manifest(url(server, link), {'recipient': 'x'})
    assert response.status_code == 401 and response.json() == {'remainingAttempts': link['remainingAttempts']}
    response = server.manifest(url(server, link), {'recipient': 'x', 'passcode': link['passcode']})
    assert response.status_code == 200
    check_files(server, link, response.json()['files'])
    if STATEFUL:
        wrong = server.manifest(url(server, link), {'recipient': 'x', 'passcode': 'wrong'})
        assert wrong.json() == {'remainingAttempts': link['remainingAttempts'] - 1}


@pytest.mark.parametrize('link', links('flag-u'), ids=lambda link: link['kind'])
def test_flag_u_link(server, link):
    response = server.request('GET', url(server, link), params={'recipient': 'x'})
    assert response.status_code == 200 and response.content.decode() == link['files'][0]['jwe']


@pytest.mark.parametrize('link', links('revoked', 'passcode-exhausted', 'expired'), ids=lambda link: link['kind'])
def test_not_served(server, link):
    body = {'recipient': 'x'}
    if link.get('passcode'):
        body['passcode'] = link['passcode']
    response = server.manifest(url(server, link), body)
    assert response.status_code == 404 and response.json() == NOT_FOUND


@pytest.mark.skipif(not STATEFUL, reason='revokes the fixture links (SHL_FIXTURES_STATEFUL=1)')
@pytest.mark.parametrize('link', links('plain', 'flag-u', 'legacy'), ids=lambda link: link['kind'])
def test_revoke_with_the_original_management_token(server, link):
    if link['managementToken'] is None:
        # legacy links have no management token: nobody can revoke them through the API
        assert server.revoke(link['id'], 'anything').status_code == 403
        return
    assert server.revoke(link['id'], 'wrong').status_code == 403
    assert server.revoke(link['id'], link['managementToken']).status_code == 204
    assert server.manifest(url(server, link)).status_code == 404
