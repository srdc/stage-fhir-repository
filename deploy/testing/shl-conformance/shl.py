"""
Client and checks for SMART Health Link servers (the Node shl-server and its replacement), used by the tests.

Everything a server generates at random (link id, key, management token, file location tickets, the JWE nonce) differs
between two runs, so "identical" means: the same HTTP contract (statuses, headers, JSON shapes and messages), the same
shlink:/ encoding of the same payload, the same JWE header, and byte-identical decrypted file contents.
"""
import base64
import io
import json
import os
import pathlib
import subprocess
from functools import lru_cache

import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ID_LENGTH = 43  # 32 random bytes, base64url without padding


def b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + '=' * (-len(text) % 4))


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip('=')


def js_json(value) -> str:
    """JSON.stringify of a value without floats (dicts keep their order): what the Node server encrypts"""
    return json.dumps(value, separators=(',', ':'), ensure_ascii=False)


def is_b64url_token(value, length=ID_LENGTH) -> bool:
    return isinstance(value, str) and len(value) == length and all(c.isalnum() or c in '-_' for c in value)


@lru_cache()
def ca_bundle():
    """TLS verification: SHL_CA_BUNDLE, else the mkcert root of the local simulation (https://stage.testing.eu), else the system"""
    if os.environ.get('SHL_CA_BUNDLE'):
        return os.environ['SHL_CA_BUNDLE']
    try:
        root = subprocess.check_output(['mkcert', '-CAROOT'], text=True, stderr=subprocess.DEVNULL).strip()
        pem = pathlib.Path(root) / 'rootCA.pem'
        if pem.exists():
            return str(pem)
    except (OSError, subprocess.CalledProcessError):
        pass
    return True


class Jwe:
    """A compact JWE of a link file (alg dir, enc A256GCM)"""

    def __init__(self, compact: str):
        self.compact = compact
        parts = compact.split('.')
        assert len(parts) == 5, f'not a compact JWE: {compact[:60]}'
        self.header_b64, self.encrypted_key, self.iv_b64, self.ciphertext_b64, self.tag_b64 = parts
        self.header_bytes = b64url_decode(self.header_b64)
        self.header = json.loads(self.header_bytes)

    def decrypt(self, key_b64: str) -> bytes:
        iv, tag = b64url_decode(self.iv_b64), b64url_decode(self.tag_b64)
        assert len(iv) == 12 and len(tag) == 16, (len(iv), len(tag))
        # the protected header (as base64url ASCII) is the additional authenticated data
        return AESGCM(b64url_decode(key_b64)).decrypt(iv, b64url_decode(self.ciphertext_b64) + tag,
                                                      self.header_b64.encode('ascii'))


def encrypt_jwe(plaintext: bytes, key_b64: str, content_type: str) -> str:
    """A compact JWE as the Node server writes it (header {"alg":"dir","enc":"A256GCM","cty":...}), e.g. for legacy files"""
    header = b64url_encode(js_json({'alg': 'dir', 'enc': 'A256GCM', 'cty': content_type}).encode())
    iv = os.urandom(12)
    sealed = AESGCM(b64url_decode(key_b64)).encrypt(iv, plaintext, header.encode('ascii'))
    return '.'.join([header, '', b64url_encode(iv), b64url_encode(sealed[:-16]), b64url_encode(sealed[-16:])])


def decode_shlink(shlink: str) -> tuple[bytes, dict]:
    assert shlink.startswith('shlink:/'), shlink[:20]
    raw = b64url_decode(shlink[len('shlink:/'):])
    return raw, json.loads(raw)


def decode_qr(data_url: str):
    """(decoded text, error correction level, image size) of the QR code PNG data URL"""
    import zxingcpp
    from PIL import Image
    prefix = 'data:image/png;base64,'
    assert data_url.startswith(prefix), data_url[:40]
    image = Image.open(io.BytesIO(base64.b64decode(data_url[len(prefix):])))
    results = zxingcpp.read_barcodes(image.convert('RGB'))
    assert len(results) == 1, f'{len(results)} QR codes found'
    return results[0].text, getattr(results[0], 'ec_level', None), image.size, image


class ShlServer:
    """HTTP client of an SHL server; base = the public base URL (PUBLIC_BASE_URL), e.g. https://stage.testing.eu/stage-shl"""

    def __init__(self, base: str):
        self.base = base.rstrip('/')
        self.session = requests.Session()
        self.session.verify = ca_bundle()

    def __repr__(self):
        return f'ShlServer({self.base})'

    def request(self, method, url, **kwargs):
        if not url.startswith('http'):
            url = self.base + url
        kwargs.setdefault('timeout', 60)
        return self.session.request(method, url, **kwargs)

    def create(self, body=None, raw: bytes | None = None, token: str | None = None, path='/shl/create'):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        data = raw if raw is not None else json.dumps(body, ensure_ascii=False).encode()
        return self.request('POST', path, data=data, headers=headers)

    def created(self, body, token=None) -> dict:
        response = self.create(body, token=token)
        assert response.status_code == 201, (response.status_code, response.text[:300])
        return response.json()

    def manifest(self, url: str, body: dict | None = None):
        return self.request('POST', url, json=body if body is not None else {'recipient': 'SHL conformance tests'})

    def files(self, link: dict, passcode=None, embedded_length_max=None) -> list[dict]:
        """The files of a created link as receivers get them: [{contentType, jwe, plaintext, entry}]"""
        body = {'recipient': 'SHL conformance tests'}
        if passcode is not None:
            body['passcode'] = passcode
        if embedded_length_max is not None:
            body['embeddedLengthMax'] = embedded_length_max
        response = self.manifest(link['payload']['url'], body)
        assert response.status_code == 200, (response.status_code, response.text[:300])
        files = []
        for entry in response.json()['files']:
            if 'embedded' in entry:
                jwe = entry['embedded']
            else:
                located = self.request('GET', entry['location'])
                assert located.status_code == 200, (located.status_code, located.text[:200])
                jwe = located.content.decode('utf-8')
            files.append({'contentType': entry['contentType'], 'jwe': jwe, 'entry': entry,
                          'plaintext': Jwe(jwe).decrypt(link['payload']['key'])})
        return files

    def revoke(self, link_id: str, token: str | None):
        headers = {'Authorization': 'Bearer ' + token} if token is not None else {}
        return self.request('DELETE', f'/shl/{link_id}', headers=headers)


# A SMART Health Card file as the apps send it (the server does not verify the JWS)
SAMPLE_VC = 'eyJ6aXAiOiJERUYiLCJhbGciOiJFUzI1NiIsImtpZCI6InRlc3QifQ.' + b64url_encode(b'not a real health card') + '.c2ln'
SAMPLE_PATIENT = {'resourceType': 'Patient', 'id': 'shl-test', 'name': [{'family': 'Yılmaz', 'given': ['Ayşe']}],
                  'gender': 'female', 'birthDate': '1960-01-02'}
SAMPLE_BUNDLE = {'resourceType': 'Bundle', 'type': 'collection',
                 'entry': [{'fullUrl': 'urn:uuid:1', 'resource': SAMPLE_PATIENT}]}
