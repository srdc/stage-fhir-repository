"""Passcodes (flag P), revoking links, link owners"""
import json
import os
from concurrent.futures import ThreadPoolExecutor

import pytest

from shl import SAMPLE_PATIENT, SAMPLE_VC

NOT_FOUND = {'error': 'SMART Health Link not found or no longer valid.'}
ATTEMPTS = 5  # SHL_PASSCODE_ATTEMPTS default


def ask(server, link, passcode=None):
    body = {'recipient': 'SHL conformance tests'}
    if passcode is not None:
        body['passcode'] = passcode
    return server.manifest(link['payload']['url'], body)


def test_passcode(server):
    link = server.created({'shc': SAMPLE_VC, 'passcode': 'secret 1'})
    for body in ({'recipient': 'x'}, {'recipient': 'x', 'passcode': ''}, {'recipient': 'x', 'passcode': 1}):
        response = server.manifest(link['payload']['url'], body)
        # no passcode: no attempt used
        assert response.status_code == 401 and response.json() == {'remainingAttempts': ATTEMPTS}
    response = ask(server, link, 'wrong')
    assert response.status_code == 401 and response.json() == {'remainingAttempts': ATTEMPTS - 1}
    assert response.headers['Cache-Control'] == 'no-store'
    [file] = server.files(link, passcode='secret 1')
    assert json.loads(file['plaintext']) == {'verifiableCredential': [SAMPLE_VC]}
    # a correct passcode does not give the attempts back (lifetime limit)
    assert ask(server, link, 'wrong').json() == {'remainingAttempts': ATTEMPTS - 2}


def test_passcode_attempts_are_a_lifetime_limit(server):
    link = server.created({'shc': SAMPLE_VC, 'passcode': 'pw'})
    for remaining in range(ATTEMPTS - 1, -1, -1):
        assert ask(server, link, 'wrong').json() == {'remainingAttempts': remaining}
    # disabled: even the right passcode
    response = ask(server, link, 'pw')
    assert response.status_code == 404 and response.json() == NOT_FOUND
    # and it cannot be revoked any more (it is no longer active)
    assert server.revoke(link['id'], link['managementToken']).status_code == 404


def test_parallel_wrong_passcodes_use_distinct_attempts(server):
    """The limit holds across parallel requests (the spec requires it)"""
    link = server.created({'shc': SAMPLE_VC, 'passcode': 'pw'})
    with ThreadPoolExecutor(ATTEMPTS + 3) as pool:
        responses = list(pool.map(lambda _: ask(server, link, 'wrong'), range(ATTEMPTS + 3)))
    remaining = sorted(r.json()['remainingAttempts'] for r in responses if r.status_code == 401)
    assert remaining == list(range(ATTEMPTS))
    assert sorted(r.status_code for r in responses) == [401] * ATTEMPTS + [404] * 3


def test_unicode_passcode(server):
    link = server.created({'shc': SAMPLE_VC, 'passcode': 'şifre-密码-😀'})
    assert ask(server, link, 'şifre-密码-😀').status_code == 200
    assert ask(server, link, 'sifre-密码-😀').status_code == 401


def test_revoke(server):
    link = server.created({'shc': SAMPLE_VC, 'fhir': SAMPLE_PATIENT})
    location = server.manifest(link['payload']['url'], {'recipient': 'x', 'embeddedLengthMax': 0}).json()['files'][0]['location']
    for token in (None, '', 'wrong', link['id']):
        response = server.revoke(link['id'], token)
        assert response.status_code == 403 and response.json() == {'error': 'Invalid management token.'}
    response = server.revoke(link['id'], link['managementToken'])
    assert response.status_code == 204 and response.content == b''
    assert ask(server, link).json() == NOT_FOUND
    assert server.request('GET', location).status_code == 404
    again = server.revoke(link['id'], link['managementToken'])
    assert again.status_code == 404 and again.json() == {'error': 'SMART Health Link not found.'}


def test_revoke_flag_u(server):
    link = server.created({'shc': SAMPLE_VC, 'flag': 'U'})
    assert server.revoke(link['id'], link['managementToken']).status_code == 204
    response = server.request('GET', link['payload']['url'], params={'recipient': 'x'})
    assert response.status_code == 404 and response.json() == NOT_FOUND


def test_revoke_unknown(server):
    response = server.revoke('A' * 43, 'whatever')
    assert response.status_code == 404 and response.json() == {'error': 'SMART Health Link not found.'}


def test_management_token_belongs_to_its_link(server):
    one, two = server.created({'shc': SAMPLE_VC}), server.created({'shc': SAMPLE_VC})
    assert server.revoke(one['id'], two['managementToken']).status_code == 403


# Link owners: only on servers with SHL_OWNER_JWKS_URL (onAuth); the local simulation has them disabled

def test_owned_links_disabled(server):
    if os.environ.get('SHL_OWNER_TOKEN'):
        pytest.skip('owners are enabled (SHL_OWNER_TOKEN is set)')
    response = server.request('GET', '/shl/owned', headers={'Authorization': 'Bearer x'})
    assert response.status_code == 404 and response.json() == {'error': 'Link owners are not enabled on this server.'}
    # a token sent when creating is ignored
    assert server.created({'shc': SAMPLE_VC}, token='not.a.jwt')['ownerBound'] is False


@pytest.fixture()
def owner_tokens():
    tokens = os.environ.get('SHL_OWNER_TOKEN'), os.environ.get('SHL_OWNER_TOKEN_2')
    if not all(tokens):
        pytest.skip('SHL_OWNER_TOKEN and SHL_OWNER_TOKEN_2 (access tokens of two users) are not set')
    return tokens


def test_owned_links(server, owner_tokens):
    mine, theirs = owner_tokens
    owned = lambda token: server.request('GET', '/shl/owned', headers={'Authorization': 'Bearer ' + token} if token else {})
    assert owned(None).status_code == 401 and owned(None).json() == {'error': 'A valid access token is required.'}
    assert owned('not.a.jwt').status_code == 401
    link = server.created({'shc': SAMPLE_VC, 'label': 'owned', 'flag': 'L'}, token=mine)
    assert link['ownerBound'] is True
    listed = owned(mine).json()
    assert listed[0] == {'id': link['id'], 'label': 'owned', 'createdAt': listed[0]['createdAt'], 'flag': 'L', 'active': True}
    assert all(item['id'] != link['id'] for item in owned(theirs).json())
    # another user cannot revoke it; the owner can, without the management token
    assert server.revoke(link['id'], theirs).status_code == 403
    assert server.revoke(link['id'], mine).status_code == 204
    revoked = next(item for item in owned(mine).json() if item['id'] == link['id'])
    assert revoked['active'] is False and revoked['revokedAt'].endswith('Z')
