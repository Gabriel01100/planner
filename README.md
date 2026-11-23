# CalenFleX 1.2

Asistente personal de productividad y aprendizaje: Android nativo, memoria educativa y backend propio. Organiza horarios, registra sesiones y propone próximos pasos con evidencia. Mantiene una interfaz simple y exige revisión para cambios importantes.

[Descargar APK validado](https://github.com/Gabriel01100/planner/releases) · [Informe de consolidación](docs/CONSOLIDATION_REPORT.md) · [Estado real y roadmap](docs/ROADMAP.md) · [Pruebas](docs/TESTING.md)

## Funciones y estado

- **Hoy:** próxima actividad, horario/duración/objetivo, comenzar y finalizar sesión con tiempo corregible.
- **Agenda:** calendario mensual real y vistas de semana/día con bloques por hora, disponibilidad y bloqueos. CRUD, recurrencias, dependencias, restricciones y previsualización conservados.
- **Objetivos:** sesiones, contenidos, ejercicios, dificultades, recursos y próximos pasos. Competencias solo con resultados evaluados explícitos y confianza declarada; importar texto no demuestra dominio.
- **Asistente:** propuestas Responses estructuradas y revisables, sin escrituras directas. Share Intent de texto, enlaces y documentos abre una revisión antes de analizar/guardar.
- **Autonomía acotada:** desactivada por defecto. El usuario autoriza movimientos pequeños del mismo día con presupuesto semanal y reversión. Cambios importantes siguen requiriendo aprobación.
- **Sync:** cambios independientes se fusionan; conflictos incompatibles conservan base/local/remoto exportables. Cola e intentos sobreviven a cierres/pérdidas de respuesta.
- **MCP:** 19 herramientas, OAuth/JWT/scopes y vinculación del espacio móvil mediante doble posesión y PKCE. Requiere configurar proveedor/hosting y consentimiento reales.

Sin conexión funcionan los registros y consultas locales. IA, generación de planes y sincronización necesitan backend HTTPS. No se ha conectado una cuenta real de ChatGPT, contratado hosting ni usado una clave paga de IA. CP-SAT sigue experimental. APK debug; no es una publicación en Play Store. Capturas reales del calendario se generan en el job de dispositivo, artefacto Android-device-reports; no se presentan mockups como capturas.

## Arquitectura y tecnologías

Compose → AppViewModel → repositorios/Room → REST; ChatGPT → MCP → los mismos servicios de dominio. LearningWorkflow coordina aprendizaje y agenda en una transacción. SQLAlchemy guarda agregados versionados, cifrados en producción. El motor y su validador comparten reglas. El agente solo devuelve propuestas tipadas. [Diagrama y responsabilidades](docs/ARCHITECTURE.md).

Python 3.12, FastAPI/Pydantic, SQLAlchemy, SQLite/PostgreSQL 17 (psycopg), MCP oficial, JWT RS256 y Responses API. Android: Kotlin 2.1.20, Compose Material 3, Room, DataStore, Retrofit y WorkManager; JDK 17, SDK 35, Gradle 8.13. OR-Tools es opcional, fuera del backend de producción.

```text
backend/app/          servicios, contratos, reglas, REST/MCP, web de vinculación
backend/tests/        suite principal
backend/integration/  PostgreSQL real y transferencia
backend/experiments/  comparación opcional del optimizador
backend/webtests/     protocolo web OAuth simulado
android/              app nativa y pruebas JVM/dispositivo
deploy/               proxy TLS
.github/workflows/    CI y APK condicionado a validación
docs/                 contratos, operación, decisiones y evidencia
```

## Instalación local del backend

Requiere Python 3.12. Desde la raíz:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
cp .env.example .env
```

Configurar variables en el entorno del proceso o un gestor de secretos; uvicorn directo **no carga .env automáticamente**. Para desarrollo local:

```sh
export CALENFLEX_TOKEN="CAMBIAR-POR-UN-TOKEN-LOCAL-ALEATORIO"
export DATABASE_URL="sqlite:///./calenflex.db"
export CALENFLEX_ENV=development
cd backend
python -m app.manage migrate
uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log
```

Abrir http://127.0.0.1:8000/docs. /health indica proceso vivo; /ready comprueba base. Sin bearer las rutas protegidas se rechazan. Development sin clave de cifrado guarda texto claro: no usar esa configuración como servicio público.

| Variables | Uso |
| --- | --- |
| CALENFLEX_TOKEN | token histórico del espacio personal; generar aleatorio |
| DATABASE_URL | SQLite local o postgresql+psycopg; no cambia ni migra datos automáticamente |
| CALENFLEX_ENV, CALENFLEX_DATA_KEY | producción exige clave Fernet, respaldada por separado |
| AI_API_KEY, AI_BASE_URL, AI_MODEL, AI_REQUESTS_PER_HOUR | IA opcional y cuota por owner/proceso |
| OAUTH_ISSUER, OAUTH_JWKS_URL, OAUTH_RESOURCE_URL | proveedor real y audiencia HTTPS exacta de MCP |
| OIDC_LINK_CLIENT_ID, OIDC_AUDIENCE_PARAMETER | cliente público PKCE de /connect; sin client secret |
| PUBLIC_DOMAIN, POSTGRES_PASSWORD | despliegue Compose de producción |
| CORS_ORIGINS, ALLOWED_HOSTS | origins explícitos y hosts permitidos |

.env.example contiene nombres sin secretos. Nunca pegar credenciales en chat/Git ni en Kotlin. Los tokens administrados se emiten con `python -m app.manage issue-token --owner IDENTIDAD --scopes memory:read schedule:read --label dispositivo`; usar solo scopes necesarios y transferir la credencial por un canal privado.

## Android y APK

Requiere Android 8/API 26+. Abrir `android` en Android Studio con JDK 17/SDK 35, o compilar:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest  # emulador o dispositivo conectado
```

APK local: android/app/build/outputs/apk/debug/app-debug.apk. Descargar una prerelease cuyo CI esté aprobado. **Antes de instalar otra firma debug, exportar agenda y aprendizaje**: Android puede exigir desinstalar. La firma estable de distribución no se incluye en Git.

En Configuración guardar URL HTTPS del servidor y credencial del propio espacio; se verifica identidad. No usar localhost desde el teléfono. Usar el asistente solo cuando AI_* esté configurado en servidor. [Android](docs/ANDROID.md), [calendario y UX](docs/UX.md), [importación](docs/ANDROID_IMPORT.md).

## Docker, PostgreSQL y HTTPS

Definir secretos reales en .env, incluida CALENFLEX_DATA_KEY. Desarrollo con volumen SQLite: `docker compose up -d --build` (escucha en 127.0.0.1:8000). Producción necesita PUBLIC_DOMAIN/DNS, POSTGRES_PASSWORD URL-safe y puertos 80/443:

```sh
docker compose -f docker-compose.yml -f docker-compose.production.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.production.yml up -d --build
```

Incluye PostgreSQL persistente, Caddy/TLS y readiness. [Despliegue, backup, restore y rollback](docs/DEPLOYMENT.md). Si ya hay datos SQLite, no cambiar DATABASE_URL sin [transferencia offline a destino vacío](docs/DATABASE.md). Un worker; escalar requiere cuotas distribuidas. DNS/hosting/TLS públicos siguen siendo configuración externa.

## ChatGPT y MCP

Registrar proveedor/API OAuth, cliente público PKCE para `https://TU_DOMINIO/connect` y configurar las variables. Desde Android crear código de vinculación, abrir /connect, ingresar con el proveedor y confirmar ambas conexiones. Luego agregar /mcp en una cuenta de ChatGPT compatible y completar su consentimiento OAuth con la misma identidad. La vinculación no instala la app en ChatGPT ni concede acceso a conversaciones privadas. [Procedimiento exacto, scopes y herramientas](docs/MCP.md).

## Pruebas y colaboración

```sh
PYTHONPATH=backend pytest -q backend/tests
node --test backend/webtests/account-link.test.cjs
# PostgreSQL desechable y optimizador se ejecutan con comandos de docs/TESTING.md.
```

CI valida backend/web, PostgreSQL, experimento, Android/JVM/lint y dispositivo. Solo publica APK si todo aprueba. Resultados/limitaciones: [TESTING.md](docs/TESTING.md). Las pruebas de proveedor son simuladas; las llamadas reales son opcionales y no se inventan resultados.

Trabajar en rama y abrir PR con pruebas/decisiones/riesgos; no sobreescribir main. [CONTRIBUTING.md](CONTRIBUTING.md). El repositorio no declara una licencia de distribución: consultar al titular antes de redistribuirlo bajo otra licencia.

## Documentación

[Arquitectura](docs/ARCHITECTURE.md) · [Datos/migraciones](docs/DATABASE.md) · [REST](docs/API.md) · [Memoria](docs/LEARNING_API.md) · [MCP](docs/MCP.md) · [IA](docs/AI.md) · [Planificador](docs/PLANNER.md) · [Sincronización](docs/SYNC.md) · [Android](docs/ANDROID.md) · [Seguridad](docs/SECURITY.md) · [Despliegue](docs/DEPLOYMENT.md) · [Pruebas](docs/TESTING.md) · [UX](docs/UX.md) · [Roadmap](docs/ROADMAP.md) · [ADR](docs/adr/0002-consolidation.md).
