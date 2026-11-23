# CalenFleX y ChatGPT

Servidor oficial MCP Python (FastMCP, Streamable HTTP) con herramientas y resultados JSON tipados. API de recurso en `/mcp`; metadatos OAuth en `/.well-known/oauth-protected-resource/mcp`. No es un lector de conversaciones ni utiliza la suscripción de ChatGPT como una clave de API.

## Configuración externa necesaria

1. Desplegar el backend con HTTPS público y una base persistente.
2. Configurar un servidor de autorización OAuth/OIDC que emita access tokens JWT RS256, con PKCE y consentimiento por usuario. Debe soportar el registro de clientes que requiera la instalación de ChatGPT, o permitir configurar el cliente explícitamente. No se implementa un proveedor OAuth propio ni un formulario de contraseñas en CalenFleX.
3. Definir OAUTH_ISSUER, OAUTH_JWKS_URL y OAUTH_RESOURCE_URL. El recurso/audiencia debe ser la URL HTTPS exacta de MCP, por ejemplo la URL real de tu despliegue terminada en `/mcp`. Estas variables quedan vacías hasta tener un proveedor real. El servidor devuelve 503 si no están configuradas.
4. Configurar en el proveedor los scopes mínimos `memory:read schedule:read`; agregar `memory:write` y `schedule:write` únicamente si se autorizan escrituras. `schedule:approve` y `preferences:write` son permisos separados para cambios importantes.
5. En una cuenta de ChatGPT con soporte para apps/conectores MCP de desarrollo, agregar la URL MCP del despliegue y completar el consentimiento OAuth. La disponibilidad y el proceso de publicación de una app dependen de la cuenta y de las políticas de OpenAI. No se afirma que la app ya esté instalada o publicada en el directorio.
6. Probar: listar objetivos, elegir uno explícitamente, registrar una sesión revisada, volver a consultar progreso y agenda. Verificar el usuario correcto antes de conectar datos personales.

El SDK publica metadatos de seguridad por herramienta y anotaciones de lectura/escritura/operación importante compatibles con Apps SDK. Es una integración de herramientas; no contiene un widget web ni una UI embebida en ChatGPT.

## Identidad y permisos

Se verifican firma, emisor, audiencia, expiración, iat y sub. La identidad interna es SHA-256 del emisor y sujeto; dos usuarios no comparten memoria ni agenda. El cuerpo de una herramienta nunca puede escoger owner. No se aceptan tokens de OpenAI ni de otras audiencias. Los scopes se verifican de nuevo por herramienta y las revocaciones locales se consultan en cada llamada.

El token histórico CALENFLEX_TOKEN sigue accediendo a personal para no perder datos. Los tokens administrativos nuevos se generan con `python -m app.manage issue-token --owner USUARIO --scopes memory:read schedule:read --label conexion`; se muestran una sola vez, se almacenan por hash y no deben pegarse en chats ni Git. Son útiles para pruebas o clientes administrados, no sustituyen el flujo OAuth de ChatGPT.

`POST /api/v1/connections/revoke` desconecta el token actual. La revocación del consentimiento completo también debe hacerse en el proveedor OAuth y en ChatGPT. Rotar o revocar una conexión no borra datos.

## Herramientas

- `create_study_goal`, `create_milestone`, `create_study_session`.
- `register_study_progress`: recibe goalId confirmado, requestId estable, tema, subtemas, comprensión reportada, refuerzo, dificultades, ejercicios, evaluación, tiempo conocido, siguientes pasos y fuente. confirmed=true requiere revisión explícita. No inventa dominio ni tiempo real desconocido.
- `create_task`, `create_recurring_task`, `add_deadline`.
- `get_schedule`, `get_today_plan`, `get_pending_tasks`.
- `get_study_goals`, `get_goal_details`, `get_learning_progress`, `get_recommended_next_steps`.
- `propose_reorganization`: persiste una propuesta de una hora de vigencia; no aplica cambios.
- `approve_reorganization`: exige confirmación, permiso separado y la revisión original de agenda. Rechaza propuestas vencidas, repetidas o con conflictos. El cliente MCP debe solicitar la confirmación humana; la anotación no sustituye la autorización del servidor.
- `create_study_plan`: crea una recurrencia para un objetivo confirmado; no asigna horarios automáticamente.
- `update_user_preferences`: exige confirmación y revisión actual; no admite modo totalmente autónomo.

## Verificación

`PYTHONPATH=backend pytest -q backend/tests/test_mcp.py` ejecuta initialize, tools/list y tools/call sobre transporte HTTP real dentro de TestClient, con credenciales de prueba, JWT firmados localmente, validación de audiencia/emisor/expiración, scopes, aislamiento, deduplicación y aprobación versionada. No requiere ni demuestra una conexión real a una cuenta de ChatGPT.

## Evolución adicional y despliegue móvil

`get_behavior_insights` agrega sugerencias con evidencia y sin escritura. Hay 19 herramientas en total: las 17 solicitadas y los auxiliares get_study_goals/get_behavior_insights. Las creaciones MCP exigen UUID estables (o requestId en create_study_plan) para que un reintento no duplique datos.

La integración móvil usa tokens de acceso administrados por el servidor. Para que Android y OAuth vean el mismo espacio, emitir el token Android para el owner que deriva del emisor y sub verificados del usuario OAuth: SHA-256 de `issuer_sin_barra_final + '|' + sub`. Hacer esta configuración en el servidor, sin pegar tokens en ChatGPT. El espacio histórico personal se conserva; su traslado o importación a otra identidad debe ser explícito y respaldado. No se implementó inicio de sesión OAuth móvil ni vinculación automática de cuentas.

Las cuotas de IA se comparten entre el asistente anterior y Responses. La cuota y la limitación general son por proceso: en un despliegue con varios workers se requiere también limitación distribuida en el gateway/proveedor para un presupuesto global estricto.
