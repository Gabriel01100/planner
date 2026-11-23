# IA y memoria con evidencia

AI_API_KEY, AI_BASE_URL, AI_MODEL y AI_REQUESTS_PER_HOUR se configuran exclusivamente en backend. Sin clave, el asistente devuelve 503 y el registro manual funciona. ResponsesLearningAgent implementa el protocolo LearningAgent; usa Responses API, Structured Outputs, store=false y validación Pydantic. El adaptador legacy de Chat Completions conserva compatibilidad. Ambos comparten cuota por owner/proceso, contando llamadas fallidas; para múltiples procesos se necesita un límite distribuido/gateway y presupuesto del proveedor.

El agente propone objetivos, sesiones, tareas, recursos, planes y próximos pasos; solicita aclaraciones ante datos insuficientes. No puede guardar ni generar SQL. Todas las propuestas se revisan y pasan por servicios de dominio. Se envían contenido explícito, hasta ocho turnos recientes y hasta veinte títulos/IDs de objetivos. Progreso resumido requiere selección y opt-in; no se envían notas privadas ni la agenda completa.

Documentos/conversaciones son material no confiable. No cambian instrucciones ni autorizan acciones. Enlaces no se descargan. PDF se decodifica sin credenciales en un proceso aislado: 2 MB, 20 páginas, 20000 caracteres, CPU/memoria/tiempo acotados, sin OCR ni ejecución de scripts.

La sesión registra contenidos, ejercicios, evaluación disponible, dificultades, fuente, tiempo real conocido y tareas pendientes. Unknown permanece null. Comprensión reportada no equivale a dominio. Una evaluación opcional requiere resultados correctos/total, método y confianza declarada explícitamente. El nivel es la proporción del último resultado; la confianza declarada no es una probabilidad calibrada. Se conserva el evento de evidencia, su fecha y fuente, sin inventar competencias al importar texto. Corrección/borrado se realizan en memoria versionada, con trazabilidad. Próximos pasos y duración son estimaciones revisables, no nuevos hechos.

CI usa dobles del proveedor y material adversarial. No se realizaron consultas pagas ni se usó una suscripción de ChatGPT como clave API. La conexión MCP es un mecanismo distinto: MCP.md.
