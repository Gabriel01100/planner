# IA

Configurar AI_API_KEY, AI_BASE_URL (endpoint base compatible con OpenAI), AI_MODEL y AI_REQUESTS_PER_HOUR en el backend. Reiniciar el servicio. Sin clave, el asistente devuelve 503 y el resto de la aplicación funciona. Nunca agregar la clave a Kotlin, Gradle, git ni a un JSON exportado.

El adaptador actual usa Chat Completions con respuesta JSON. El esquema AIResponse incluye una respuesta textual y una Activity opcional. Pydantic rechaza propuestas inválidas. El usuario revisa y corrige la propuesta antes de guardarla; luego el motor determinista organiza. El asistente no tiene herramientas de escritura ni autorización para aplicar planes.

Las pruebas usan dobles HTTP y verifican salida válida, salida inválida y proveedor ausente. No verifican una consulta paga real: requiere credenciales del proveedor. El límite horario es por proceso y cuenta también solicitudes fallidas; configurar además presupuesto en el proveedor para un tope monetario real.
