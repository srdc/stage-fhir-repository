"""
Configuration (environment variables):
  SHL_URL            the server under test (its PUBLIC_BASE_URL), default https://stage.testing.eu/stage-shl
  SHL_REFERENCE_URL  the Node shl-server to compare with (test_differential.py); skipped when not set
  SHL_CA_BUNDLE      CA file for TLS (default: the mkcert root of the local simulation)
  SHL_OWNER_TOKEN, SHL_OWNER_TOKEN_2
                     access tokens of two different users of the repository, for the link owner tests
"""
import os

import pytest

from shl import ShlServer


@pytest.fixture(scope='session')
def server() -> ShlServer:
    return ShlServer(os.environ.get('SHL_URL', 'https://stage.testing.eu/stage-shl'))


@pytest.fixture(scope='session')
def reference() -> ShlServer:
    url = os.environ.get('SHL_REFERENCE_URL')
    if not url:
        pytest.skip('SHL_REFERENCE_URL (the Node shl-server) is not set')
    return ShlServer(url)
