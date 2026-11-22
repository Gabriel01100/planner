from fastapi.testclient import TestClient
import httpx
import pytest
from app.main import create_app
from app.models import State

@pytest.fixture
def client(tmp_path):
    with TestClient(create_app('sqlite:///' + str(tmp_path / 'test.db'), token='test-token')) as c:
        c.headers['Authorization'] = 'Bearer test-token'
        yield c


def test_authentication(client):
    assert client.get('/api/sync', headers={'Authorization': 'Bearer wrong'}).status_code == 401


def test_unconfigured_server(tmp_path):
    with TestClient(create_app('sqlite:///' + str(tmp_path / 'other.db'), token='')) as c:
        assert c.get('/api/sync').status_code == 503


def test_sync_persists_and_detects_conflicts(client):
    state = State().model_dump(mode='json')
    state['preferences']['name'] = 'Gabriel'
    assert client.get('/api/sync').json()['revision'] == 0
    assert client.put('/api/sync', json={'revision': 0, 'state': state}).json()['revision'] == 1
    assert client.get('/api/sync').json()['state']['preferences']['name'] == 'Gabriel'
    assert client.put('/api/sync', json={'revision': 0, 'state': State().model_dump(mode='json')}).status_code == 409
    assert client.get('/api/sync').json()['state'] == state


def test_import_validates(client):
    state = State().model_dump(mode='json')
    state['preferences']['timezone'] = 'unknown/timezone'
    assert client.post('/api/import/validate', json=state).status_code == 422


def test_ai_unavailable_is_explicit(client, monkeypatch):
    monkeypatch.delenv('AI_API_KEY', raising=False)
    assert client.post('/api/ai', json={'message': 'Hola', 'state': State().model_dump(mode='json')}).status_code == 503


def test_ai_validated_provider(client, monkeypatch):
    monkeypatch.setenv('AI_API_KEY', 'test-only')
    async def post(self, url, **kwargs):
        assert kwargs['headers']['Authorization'] == 'Bearer test-only'
        return httpx.Response(200, json={'choices': [{'message': {'content': '{"answer":"Tenés la semana libre","proposedActivity":null}'}}]}, request=httpx.Request('POST', url))
    monkeypatch.setattr(httpx.AsyncClient, 'post', post)
    response = client.post('/api/ai', json={'message': 'Analizá', 'state': State().model_dump(mode='json')})
    assert response.status_code == 200
    assert response.json()['proposedActivity'] is None


def test_ai_invalid_provider(client, monkeypatch):
    monkeypatch.setenv('AI_API_KEY', 'test-only')
    async def post(self, url, **kwargs):
        return httpx.Response(200, json={'choices': [{'message': {'content': '{}'}}]}, request=httpx.Request('POST', url))
    monkeypatch.setattr(httpx.AsyncClient, 'post', post)
    assert client.post('/api/ai', json={'message': 'Hola', 'state': State().model_dump(mode='json')}).status_code == 502


def test_plan_and_validation_endpoints(client):
    state = State().model_dump(mode='json')
    assert client.post('/api/plan', json={'state': state, 'start': '2027-01-04'}).status_code == 200
    assert client.post('/api/reorganize', json={'state': state, 'start': '2027-01-04'}).status_code == 200
    assert client.post('/api/validate', json=state).json()['valid']


def test_database_survives_new_app_and_token_rotation(tmp_path):
    path = 'sqlite:///' + str(tmp_path / 'persist.db')
    state = State().model_dump(mode='json')
    state['preferences']['name'] = 'Persistente'
    with TestClient(create_app(path, token='first')) as c:
        assert c.put('/api/sync', headers={'Authorization': 'Bearer first'}, json={'revision': 0, 'state': state}).status_code == 200
    with TestClient(create_app(path, token='rotated')) as c:
        response = c.get('/api/sync', headers={'Authorization': 'Bearer rotated'})
        assert response.json()['state']['preferences']['name'] == 'Persistente'
        assert response.json()['revision'] == 1


def test_ai_rate_limit(client, monkeypatch):
    monkeypatch.setenv('AI_API_KEY', 'test-only')
    monkeypatch.setenv('AI_REQUESTS_PER_HOUR', '0')
    assert client.post('/api/ai', json={'message': 'Hola', 'state': State().model_dump(mode='json')}).status_code == 429


def test_excessive_body_is_rejected(client):
    assert client.post('/api/import/validate', content=' ' * 5_000_001).status_code == 413
