# API de aprendizaje y propuestas v1

Las rutas nuevas usan `/api/v1`; la API original de agenda conserva `/api/sync`, `/api/plan`, `/api/reorganize` y `/api/ai`. La memoria educativa se guarda en una tabla separada, por principal autenticado, con su propia revisión. Los clientes Android anteriores no la pueden sobrescribir al sincronizar la agenda.

- `GET /api/v1/learning`: `{revision, memory}` para exportación/consulta autorizada. Requiere memory:read.
- `POST /api/v1/learning/goals`: crear LearningGoal con UUID estable; repetir el mismo ID y contenido es idempotente. Requiere memory:write.
- `GET /api/v1/learning/goals/{id}`: objetivo, minutos realmente registrados, sesiones de duración desconocida, hitos, temas y evidencia.
- `POST /api/v1/learning/milestones`, `/sessions`: crear registros tipados para un objetivo existente.
- `POST /api/v1/learning/progress`: SessionRegistration con requestId, goalId y confirmed=true. El reintento no duplica fuentes, sesiones ni progreso. Reusar requestId con otros datos devuelve 409.
- `GET /api/v1/learning/next-steps`: pasos cuyas dependencias estén completadas, ordenados por prioridad. Las estimaciones se identifican como tales.
- `PUT /api/v1/learning`: corrección/importación completa `{revision, memory, confirmed}`. Valida referencias, ciclos y evidencia antes de escribir. Una revisión vieja devuelve 409 sin perder datos.
- `POST /api/v1/learning/goals/{id}/delete`: `{revision, confirmed}`; elimina la memoria asociada al objetivo, no la agenda histórica. Otras metas permanecen intactas.
- `POST /api/v1/agent/propose` y `/imports/propose`: `{content, instruction?, goalId?, sourceType?}`. Devuelven una propuesta editable con preguntas ante ambigüedad. No escriben ni certifican conocimiento.
- `POST /api/v1/imports/document`: multipart `file` y goalId opcional. Texto UTF-8 hasta 20000 caracteres o PDF sin contraseña, hasta 2 MB/20 páginas. En Linux el PDF se decodifica en un proceso con límites; en otros sistemas usar texto. Sin OCR ni descarga de enlaces. No se ejecutan scripts, enlaces ni instrucciones del documento.
- `POST /api/v1/connections/revoke`: revoca el token actual; no borra información.

Consultar `/docs` para el contrato generado. Los errores 401/403/409/422/429/503 distinguen autenticación, permiso, conflicto, validación, cuota y configuración ausente. Los IDs de otro usuario devuelven el mismo resultado de no encontrado que los inexistentes.

## Memoria y evidencia

LearningGoal, LearningMilestone, Topic, Competency, StudySession, ProgressEvent, SourceReference y NextStep se validan como un agregado coherente. Los tiempos completados derivan exclusivamente de actualMinutes en sesiones completadas; plannedMinutes y estimaciones nunca se suman como tiempo real. Una duración desconocida sigue siendo null. Los niveles estimados requieren evidencia confirmada y método de evaluación; importar una conversación no genera competencias por sí solo.

El usuario puede corregir campos con control de revisión, exportar/importar el documento y borrar un objetivo y su memoria asociada. Los recibos de deduplicación se conservan en las copias. La auditoría registra acción, fecha, usuario pseudónimo e identificador, sin notas ni contenido privado.

## Ejemplo manual

Crear un objetivo, guardar su id, luego enviar una sesión con un requestId UUID fijo, goalId confirmado, topic, completedAt con zona horaria, actualMinutes si se conoce y confirmed=true. Consultar el detalle devuelve los minutos reales y los siguientes pasos propuestos. No suministrar un owner en el JSON: lo determina exclusivamente la conexión autenticada.

## Consolidación 1.2

SessionRegistration admite assessment opcional con correctCount, totalCount, method y confidence declarada (0..1). Solo resultados explícitos confirmados generan un evento assessment y actualizan la competencia de ese tema; estimatedLevel=correctCount/totalCount. No es una certificación ni una inferencia del agente. Mismo requestId no duplica eventos. Si activityId está presente debe existir en el mismo espacio y coincidir con el objetivo asociado; LearningWorkflow registra progreso y completa agenda en una única transacción.

PUT de memoria completa exige además preferences:write si cambia preferencias; no permite habilitar autonomía usando solo memory:write. PUT /api/v1/learning/preferences separa esa intención. La lista de endpoints y scopes de conexión está en API.md y MCP.md.
