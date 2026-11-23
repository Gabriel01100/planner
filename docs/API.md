# API REST

Contrato generado: `/openapi.json` y `/docs`. Nombres camelCase, fechas ISO 8601 y minutos locales 0..1440; zona IANA en preferencias. Rutas `/api/*` requieren bearer salvo configuración pública de cliente PKCE. Los errores usan detail y códigos 401/403/409/413/422/429/502/503. Cada respuesta incluye X-Request-ID. No se acepta owner elegido por JSON.

| Ruta | Contrato y efecto |
| --- | --- |
| GET /health | proceso vivo; disponibilidad de clave IA, no acceso garantizado al proveedor |
| GET /ready | SELECT 1 y versión de esquema; 503 si base falla |
| POST /api/plan, /api/reorganize | `{state,start,days,now?}` → PlanResult; legacy, sin guardar |
| POST /api/validate | State → `{valid,conflicts}` con reglas compartidas |
| POST /api/import/validate | State validado, sin guardar |
| GET /api/sync | `{revision,state}` del owner |
| PUT /api/sync | reemplazo legacy con revisión CAS; 409 conserva remoto |
| POST /api/v1/sync/merge | `{requestId,revision,base,state}`; fusión por entidad/campo; respuesta idempotente; SYNC.md |
| POST /api/v1/planning/preview | previsualización estable, sin escribir |
| POST /api/v1/planning/proposals | `{start,days}` → propuesta versionada con vencimiento |
| POST /api/v1/planning/proposals/{id}/approve, /undo | `{confirmed:true}`, permiso de aprobación y revisión aplicable |
| POST /api/v1/planning/automatic | evalúa consentimiento y aplica solo movimientos seguros; devuelve applied/reason/revision y proposalId reversible si aplica |
| PUT /api/v1/learning/preferences | `{revision,preferences,confirmed}`; preferences:write |
| POST /api/ai | adaptador legacy: `{message,state}` → answer/proposedActivity |
| POST /api/v1/agent/propose, /imports/propose | propuesta educativa tipada; no escribe; AI.md |
| POST /api/v1/imports/document | multipart acotado; ANDROID_IMPORT.md |
| GET /api/v1/connections/identity | workspaceId pseudónimo y tipo de autenticación |
| POST /api/v1/connections/link/start | código de doble posesión de cinco minutos |
| GET /api/v1/connections/link/config | configuración pública SPA/PKCE; sin secret |
| POST /api/v1/connections/link/claim | OAuth + `{code,confirmed}`; nunca reemplaza un espacio con datos |
| GET /api/v1/connections/links | identidades vinculadas del espacio autenticado |
| POST /api/v1/connections/links/{id}/revoke | `{confirmed:true}`; desvincula sin borrar datos |
| POST /api/v1/connections/revoke | revoca bearer actual |

Memoria, sesiones, recursos y correcciones: [LEARNING_API.md](LEARNING_API.md). Herramientas: [MCP.md](MCP.md). Autonomía/constraints: [PLANNER.md](PLANNER.md). La previsualización local no es la aprobación de una propuesta remota.

State conserva schemaVersion 1 y agrega Activity.dependencies (UUID existentes, DAG) y Preferences.maxDailyMinutes. goalId es opcional y puede apuntar a un objetivo histórico eliminado sin borrar agenda. Servidor y Android rechazan ciclos y dependencias inexistentes. Duración 5..1440, prioridad 1 mayor a 4 menor. Las API previas siguen disponibles y no pueden borrar la tabla educativa nueva.
