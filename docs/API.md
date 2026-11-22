# API

Consultar `/docs` o `/openapi.json` para el contrato completo generado desde Pydantic. Todas las rutas `/api/*` requieren `Authorization: Bearer <CALENFLEX_TOKEN>`. Los errores usan `detail` y código HTTP.

- `GET /health`: estado del proceso y disponibilidad de credencial de IA; no garantiza acceso al proveedor.
- `POST /api/plan`, `POST /api/reorganize`: `{state, start: "AAAA-MM-DD", days: 1..93}`. Devuelven `{schedule, pending, conflicts, changes}`. No persisten ni sobrescriben planes.
- `POST /api/validate`: recibe State y devuelve `{valid, conflicts}`.
- `GET /api/sync`: `{revision, state}`; un espacio nuevo devuelve revisión 0.
- `PUT /api/sync`: recibe `{revision, state}`, guarda atómicamente si coincide la revisión y devuelve revisión incrementada. HTTP 409 conserva la versión anterior.
- `POST /api/import/validate`: valida State sin persistirlo.
- `POST /api/ai`: `{message, state}` → `{answer, proposedActivity}`. HTTP 503 si no hay credencial; 429 al superar cuota; 502 ante salida inválida o error del proveedor.

Los nombres JSON usan camelCase y las fechas ISO 8601. Los horarios son minutos desde medianoche local (0..1440); días 0=lunes y 6=domingo. Los horarios no representan instantes UTC. La zona IANA del usuario interpreta cada fecha y hora; WorkManager usa esa zona al programar recordatorios.

State contiene `schemaVersion: 1`, `activities`, `availability`, `blocked`, `schedule`, `preferences`, `history`. UUID identifica cada actividad. Una ocurrencia se identifica por actividad y fecha. Importar reemplaza el documento local después de validar, con confirmación. Exportar no incluye la credencial ni la URL del servidor.
