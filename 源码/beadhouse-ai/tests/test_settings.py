import pytest
from pydantic import ValidationError
from app.settings import Settings


@pytest.mark.parametrize("value", ["", "local-dev-token", "short", "replace-with-a-random-internal-token"])
def test_rejects_missing_or_template_token(value):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, internal_token=value)


def test_explicit_configuration_has_no_default_milvus_password():
    settings = Settings(_env_file=None, internal_token="test-only-internal-token-at-least-32-bytes")
    assert settings.milvus_token != "root:Milvus"


def test_milvus_mode_requires_explicit_credentials():
    with pytest.raises(ValidationError):
        Settings(_env_file=None, backend="milvus", milvus_token="", internal_token="test-only-internal-token-at-least-32-bytes")
