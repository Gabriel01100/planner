# Evolución de CalenFleX

## Etapa 0 — auditoría antes de cambios

Base: commit 0942752. Checkout sin cambios y sincronizado con origin/main. Backend FastAPI con planificador determinista, documento SQLAlchemy con revisión; Android Kotlin/Compose, Room con documento local v1 y DataStore para conexión cifrada. El CI compila, ejecuta pruebas JVM y tests de dispositivo y publica APK tras validación. Línea base ejecutada: 71 tests backend; última entrega validó 13 JVM y 2 instrumentados.

Brechas: espacio único personal, ausencia de memoria educativa y MCP/OAuth, asistente de un turno con contexto completo, sin recepción de contenido compartido. El editor muestra restricciones avanzadas junto con campos básicos; el inicio mezcla métricas y acciones; cinco destinos obligan a navegar entre configuración, estadísticas y trabajo cotidiano.

## Orden y verificación

0. Auditoría y línea base (este documento).
1. Memoria: entidades, invariantes, deduplicación, correcciones, migración aditiva y aislamiento. Mantener los documentos anteriores intactos. Tests de migración y evidencia antes de continuar.
2. Inteligencia: propuestas tipadas, mínimo contexto, sesión confirmada, próximos pasos y validación humana. Proveedores simulados en CI.
3. MCP: herramientas tipadas sobre los mismos servicios, OAuth como servidor de recursos, permisos, revocación, cuotas, auditoría y confirmación versionada de cambios importantes.
4. Android: importación explícita de texto, enlaces y documentos; vista editable antes de guardar. Sin lectura de conversaciones privadas ni descarga automática de enlaces.
5. Planificación: conservar algoritmo actual; evaluar CP-SAT en módulo experimental y benchmark reproducible. Activarlo solo después de comparación. Dependencias, carga y estabilidad con validación independiente.
6. Comportamiento: sesiones reales frente a estimadas, recomendaciones fundamentadas y cambios reversibles.
7. UX: Hoy / Agenda / Objetivos / Asistente; configuración y estadísticas secundarias; formularios progresivos. Conservar CRUD y copias, sin botones decorativos.
8. Producción: CI, APK, HTTPS y PostgreSQL configurable. OAuth/proveedor/hosting necesitan configuración externa real; no declarar conexión a ChatGPT ni despliegue si no se ejecutaron.

Cada etapa debe actualizar su evidencia de pruebas y limitaciones; no se elimina la suite anterior. Los commits se organizan por etapa y se revisan para excluir secretos antes de subir.

## Evidencia — etapa 1

Implementados contratos tipados y validados para las nueve entidades educativas y preferencias. Servicios transaccionales con revisión optimista, sesiones confirmadas e idempotentes, trazabilidad de fuentes, corrección y borrado en cascada. No se infiere dominio ni tiempo real desconocido. API `/api/v1/learning`, permisos de lectura/escritura, credenciales por hash, revocación y aislamiento por principal. La tabla de agenda original permanece intacta.

Ejecutado: línea base **71/71**; después de la etapa 1 **85/85** tests de backend (incluidas las 71 anteriores), con una advertencia de deprecación del cliente de tests. Casos nuevos: migración idempotente preservando bytes y revisión, ciclos y referencias inválidas, aislamiento, scopes, revocación, confirmación, deduplicación, corrección con conflicto y duraciones desconocidas. Falta integrar MCP, cliente Android de memoria y validar conexión OAuth real/hosting. SQLite verificado; no se declara PostgreSQL probado en esta etapa.

## Etapas 2–3 — servicios y herramientas

Se implementa un adaptador OpenAI Responses con Structured Outputs para propuestas sin escritura, validación de salida y contexto limitado a contenido explícitamente compartido y títulos/IDs de objetivos. No se transmite el historial privado completo. Textos y PDF se tratan como datos, no instrucciones; enlaces no se descargan. El decodificador PDF se ejecuta sin credenciales y con límites de memoria, CPU, tamaño, páginas y tiempo en un proceso separado.

El MCP oficial implementa las 17 herramientas solicitadas y una herramienta adicional para listar objetivos. Contratos de entrada/salida estrictos, permisos por herramienta, JWT OAuth verificado, anotaciones de cambios importantes, revocación y cuota por usuario/proceso. Los comandos usan los mismos servicios que REST; no ejecutan SQL generado por modelos.

Se añade un envoltorio del planificador existente que conserva sesiones válidas, sin cambiar el algoritmo original. Las propuestas tienen revisión y vencimiento; aplicar una propuesta usada, obsoleta, ajena o conflictiva se rechaza. Revertir exige que no haya ediciones posteriores. El modo completamente autónomo no se activa.

Pruebas de protocolo ejecutadas: **7/7** sobre initialize/tools/list/tools/call HTTP, registro idempotente, aislamiento, permisos, esquemas, audiencia/emisor/expiración y aprobación/reversión. OAuth real, conexión a una cuenta de ChatGPT y llamadas pagas a OpenAI siguen requiriendo configuración externa. Los endpoints de propuestas aún no implican que la app Android anterior tenga nueva navegación ni Share Intent; esas etapas se entregan por separado.

## Evidencia de la entrega Android 1.1

Se implementó la nueva navegación y el editor progresivo, recepción de texto/enlaces y documentos mediante Share Intent, revisión de propuestas y registro de sesiones/recursos. La migración Room agrega caché y cola de aprendizaje sin tocar agenda. El cronómetro es persistente y su duración se corrige al finalizar; no se asigna dominio automático. Las copias educativas conservan pendientes y requieren recuperación explícita para no sobrescribir otra memoria.

La evaluación CP-SAT quedó experimental, con resultados reales de veinte casos y objetivo distinto al de producción. Se conserva el motor original. Las sugerencias de duración/repaso se basan en evidencias, se muestran como estimaciones y no se aplican automáticamente. No se activa fully_autonomous.

Ver VALIDATION.md para los resultados finales de esta entrega. Siguen pendientes el optimizador completo con dependencias y cargas multiobjetivo, la aplicación automática de adaptaciones acotadas, vinculación/login OAuth móvil, un despliegue HTTPS real y validación PostgreSQL. No se presenta esta entrega por etapas como toda la visión terminada.
