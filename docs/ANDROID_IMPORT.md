# Android 1.1 — compartir y aprender

Cuatro destinos: Hoy, Agenda, Objetivos y Asistente. Agenda conserva calendario y CRUD; configuración y estadísticas están en la cabecera. Los campos menos frecuentes de actividades se despliegan en Opciones avanzadas. Al revisar una tarea interpretada por IA con fecha desconocida, la fecha queda vacía y debe completarse antes de guardar.

Objetivos permite crear sin conexión, registrar sesiones confirmadas, consultar tiempos reales y próximos hitos, corregir objetivos sincronizados y eliminar memoria con confirmación. La sesión en curso y su hora de inicio quedan en Room; el tiempo transcurrido se puede corregir. Una actividad general puede completarse sin crear un registro educativo ni inventar un objetivo.

La tabla nueva learning_cache se agrega mediante migración Room 1→2 sin tocar filas de agenda. Conserva caché, revisión, sesión en curso y comandos pendientes. Los comandos usan IDs estables: si el servidor confirma y se pierde la respuesta, el reintento no duplica el registro. Un retroceso de revisión o una recuperación ambigua no borra la memoria local silenciosamente.

## Compartir desde otra aplicación

En ChatGPT, NotebookLM, un navegador o un lector compatible, usar Compartir y elegir CalenFleX. Se admiten ACTION_SEND y ACTION_SEND_MULTIPLE, texto/enlaces y hasta cinco documentos compatibles. El acceso depende de que la aplicación de origen realmente permita compartir texto, enlaces o archivos. No se leen conversaciones privadas.

Los documentos admitidos son texto UTF-8, Markdown, JSON y PDF; hasta 2 MB por documento. Solo se reciben URI content:// con acceso concedido por Android; no se aceptan file:// ni se descargan enlaces. PDF se analiza en el backend, sin OCR, hasta veinte páginas y 20000 caracteres, con límites de CPU/memoria/tiempo y sin ejecutar scripts.

Primero se muestra el contenido. Analizar envía únicamente lo seleccionado y contexto de objetivos limitado al proveedor configurado. Los resultados son propuestas: se puede crear un objetivo, revisar tareas, registrar una sesión o guardar una referencia. Para una sesión o recurso hay que elegir el objetivo; la app no escoge uno por una coincidencia ambigua. Los cambios importantes se guardan solo al confirmar el formulario.

## Asistente

Conserva hasta ocho turnos recientes durante la sesión de la app. Estos turnos no forman memoria de dominio por sí mismos ni quedan archivados como aprendizaje. Compartir progreso resumido de un objetivo es una opción explícita; no envía notas privadas ni la agenda completa. Sin credencial de IA en el servidor, las funciones locales y el registro manual siguen disponibles.

## Copias

Configuración permite exportar aprendizaje con su cola pendiente a JSON; la agenda se respalda por separado con el formato anterior. Guardar ambas copias en un lugar privado. Importar aprendizaje exige confirmación y bloquea sincronización automática hasta elegir recuperación. Se puede continuar si el servidor contiene los registros conocidos o restaurar una copia en un servidor vacío. Nunca se reemplaza automáticamente una memoria remota no vacía.

Si se cambia entre APK de depuración con firmas distintas, Android puede requerir desinstalar. Exportar agenda y aprendizaje antes de hacerlo. Para actualizaciones normales sin ese paso, el despliegue necesita una clave estable de firma de distribución; no se generó ni publicó una clave privada de producción.

Las correcciones y eliminaciones de memoria sincronizada necesitan conexión y una cola vacía para usar la revisión remota correcta. Los registros nuevos y sus copias sí se guardan offline. Las propuestas y mensajes recientes del asistente son temporales hasta que se confirme un registro; no se presentan como conocimiento adquirido ni como archivo permanente de conversaciones.
