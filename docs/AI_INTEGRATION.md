# IA

Configurar AI_API_KEY, AI_BASE_URL (endpoint base compatible con OpenAI), AI_MODEL y AI_REQUESTS_PER_HOUR en el backend. Reiniciar el servicio. Sin clave, el asistente devuelve 503 y el resto de la aplicación funciona. Nunca agregar la clave a Kotlin, Gradle, git ni a un JSON exportado.

El adaptador actual usa Chat Completions con respuesta JSON. El esquema AIResponse incluye una respuesta textual y una Activity opcional. Pydantic rechaza propuestas inválidas. El usuario revisa y corrige la propuesta antes de guardarla; luego el motor determinista organiza. El asistente no tiene herramientas de escritura ni autorización para aplicar planes.

Las pruebas usan dobles HTTP y verifican salida válida, salida inválida y proveedor ausente. No verifican una consulta paga real: requiere credenciales del proveedor. El límite horario es por proceso y cuenta también solicitudes fallidas; configurar además presupuesto en el proveedor para un tope monetario real.

# Agente educativo 1.1

`ResponsesLearningAgent` implementa una interfaz separada y usa `/responses`, Structured Outputs y store=false. No recibe herramientas de escritura: devuelve un AgentProposal tipado. Los comandos revisados se aplican mediante los servicios de dominio. Las cuotas horarias se comparten con el adaptador anterior.

El contexto se limita al contenido compartido, hasta ocho turnos recientes y títulos/IDs de hasta veinte objetivos. Compartir progreso exige objetivo seleccionado e includeProgress=true; envía tiempos/conteos/temas y próximos pasos resumidos, sin notas privadas ni la agenda completa. Sin selección inequívoca se pide confirmar el objetivo. El asistente anterior también dejó de enviar el estado completo.

La importación calcula un digest del contenido y conserva su tipo. Registrar recursos no crea sesiones ni competencias. Registrar sesiones distingue tiempo real desconocido de una estimación y conserva fuentes y preguntas. Una coincidencia de texto no prueba dominio. Los contenidos importados nunca son instrucciones para ejecutar acciones o SQL.
