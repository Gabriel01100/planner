"""Authenticated at-rest envelopes; key is supplied by the deployment, never stored."""
import os
from cryptography.fernet import Fernet, InvalidToken
from sqlalchemy.types import TypeDecorator, Text

PREFIX = 'enc:v1:'

def cipher():
    key = os.getenv('CALENFLEX_DATA_KEY', '')
    if not key:
        if os.getenv('CALENFLEX_ENV') == 'production':
            raise RuntimeError('Producción requiere CALENFLEX_DATA_KEY; conservá una copia segura de la clave')
        return None
    try:
        return Fernet(key.encode())
    except Exception:
        raise RuntimeError('CALENFLEX_DATA_KEY debe ser una clave Fernet válida')


def seal(value):
    protector = cipher()
    return PREFIX + protector.encrypt(value.encode()).decode() if protector else value


def unseal(value):
    if not value.startswith(PREFIX):
        return value
    protector = cipher()
    if protector is None:
        raise RuntimeError('La base está cifrada; restaurá CALENFLEX_DATA_KEY antes de abrirla')
    try:
        return protector.decrypt(value[len(PREFIX):].encode()).decode()
    except InvalidToken:
        raise RuntimeError('No se pudo verificar la clave de cifrado; no se modificaron los datos')

class ProtectedText(TypeDecorator):
    impl = Text
    cache_ok = True
    def process_bind_param(self, value, dialect):
        return seal(value) if value is not None else value
    def process_result_value(self, value, dialect):
        return unseal(value) if value is not None else value
