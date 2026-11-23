# CalenFleX 1.2 — Correcciones A-01, A-03 y M-01

Fecha: 23 de noviembre de 2026. Rama: `consolidation/product-1.2`.
Base: `40cade4fc0a195a480639405b258f67d7234ff13`.

Se inspeccionaron estado, rama, últimos cinco commits, auditoría completa, modelos,
repositorios, workflows y pruebas antes de editar. El único archivo local previo,
`docs/FINAL_AUDIT.md`, se conservó sin modificaciones. Durante la implementación no se hicieron commits ni push. Posteriormente el
usuario autorizó ambas acciones para revisar los cambios en el PR. No se hicieron
merge, cierres de PR, publicaciones ni despliegues. No se cambiaron
credenciales, dependencias, esquema SQL, planificador, autenticación ni vinculación.

## A. Resultado por hallazgo

### A-01 — Resuelto en código y regresiones JVM

**Problema y causa:** la importación comprobaba objetivos y algunos identificadores;
la reanudación comprobaba presencia de IDs de objetivos/sesiones. Se podían aceptar
entidades inválidas o perder diferencias de valores y colecciones al hacer pull.

**Archivos de producción:** `LearningData.kt`, nuevo `LearningIntegrity.kt` y
`LearningBackup.kt`, en `android/app/src/main/java/com/calenflex/app/`.

**Corrección:** validación previa de estructura, versión, revisión, UUID, tipos,
límites, estados, tiempos, referencias, jerarquías, dependencias, evidencia,
recibos y comandos del agregado existente. Una sesión completada requiere fin;
una entidad referenciada debe existir y corresponder al objetivo/tema apropiado.
Se rechazan campos desconocidos y colecciones omitidas para evitar descartarlos.
Los comandos pendientes se validan también contra sus sesiones y recibos.

La importación realiza una transacción de Room: conserva el payload local completo
en `learning_cache.id=2` y escribe la copia importada en `id=1`. Si falla una
escritura, ambas vuelven al estado anterior. No se sobrescribe una copia protegida
sin una decisión explícita. Se puede exportar la anterior, intercambiar ambas
copias o descartar la protegida con confirmación.

La reanudación compara el contenido de las nueve colecciones y preferencias,
ignorando orden de entidades y diferencias de representación equivalentes del
contrato. Los registros optimistas respaldados por comandos pendientes se tratan
por separado. Elementos adicionales, faltantes o modificados producen un conflicto;
la memoria importada, cola y copia local anterior permanecen intactas.

Se conserva la versión remota revisada hasta finalizar la recuperación, también
al restaurar en un servidor vacío. Esa restauración rechaza preferencias remotas
no predeterminadas que difieran de la copia. Un cambio
remoto posterior impide descartar la copia sin advertencia. El pull se valida y,
durante recuperación, debe conservar los registros confirmados revisados. La cola
persistida se elimina únicamente al completar el pull validado y los insights;
una falla después de confirmar una escritura permite repetir los IDs originales.

**Regresiones ejecutadas:** `LearningIntegrityTest` (7 pruebas, con matrices de
backups inválidos y diferencias entre colecciones), partes de
`LearningIdempotencyTest` y las pruebas previas `LearningBackupTest`.
Verifican payload completo, fila protegida, comandos, recibos y sesión activa antes
y después de errores; también importación válida y replay de backups anteriores.

**Regresión instrumentada agregada:**
`LearningIntegrityPersistenceTest.failedImportRollsBackBothCurrentAndProtectedRows`
inyecta un fallo SQLite después de escribir la fila protegida. Compilada, no ejecutada.
La implementación generada por Room envuelve ambos métodos de recuperación en
`performInTransactionSuspending`; esto se inspeccionó, no sustituye la prueba en dispositivo.

### A-03 — Resuelto en código y regresiones backend/JVM/PostgreSQL

**Problema y causa:** ambos coordinadores usaban la fecha de `completedAt` para
seleccionar un slot. La sesión activa guardaba actividad/inicio, sin fecha elegida.

**Archivos:** `LearningData.kt`, `LearningScreens.kt`, `MainActivity.kt`,
`StudyCoordinator.kt`; `backend/app/learning_models.py`, `learning.py` y
`learning_workflow.py`.

**Corrección:** se conserva la identidad existente `(activityId, date)` como
`occurrenceDate`, desde el slot de Hoy hasta sesión activa, formulario, cola,
workflow y sesión confirmada. No se necesita otro ID: la validación de agenda
existente exige unicidad de ese par. Android rechaza un comando que cambie la
ocurrencia de la sesión activa. El backend valida el slot indicado antes de
escribir y evita reasignar sesiones existentes. Las dos actualizaciones mantienen
las transacciones y el bloqueo compartido existentes. Completar sin registro
educativo también usa la ocurrencia seleccionada.

La deduplicación por fuente tampoco puede reutilizar una sesión perteneciente a
otra actividad/ocurrencia. No se usa `completedAt`, el día actual ni la siguiente
ocurrencia para identificar qué completar.

**Regresiones ejecutadas:** `StudyOccurrenceTest` (3 pruebas con matrices),
`backend/tests/test_learning_integrity.py` (13 casos) y la nueva prueba de
`backend/integration/test_postgres.py`. Cubren mismo día, medianoche, Buenos Aires,
registro tardío, recuperación JSON/repositorio, días consecutivos, identidad
incorrecta, sesiones antiguas, actividad única pospuesta, replay y sincronización.
En PostgreSQL se fuerza un fallo después del flush educativo: ambos documentos
quedan iguales a los anteriores; el reintento completa solo el día seleccionado.

**Regresión instrumentada agregada:**
`LearningIntegrityPersistenceTest.recoveredMidnightSessionCommitsOnlySelectedOccurrenceAndRejectsAChangedSelection`.
Compilada, no ejecutada.

### M-01 — Resuelto en código y regresiones JVM/backend

**Problema y causa:** Android retornaba éxito al encontrar un `requestId` pendiente,
sin comparar el cuerpo. El backend ya devolvía 409 ante otra huella, pero una ruta de deduplicación
por contenido no registraba el nuevo requestId confirmado.

**Archivos:** `LearningData.kt`, `LearningIntegrity.kt`, `LearningScreens.kt`,
`backend/app/learning.py` y las pruebas educativas.

**Corrección:** mismo ID/cuerpo equivalente es una repetición; otro contenido o tipo
de comando genera conflicto antes de persistir. Se comparan todos los campos del
comando, omitiendo solo ID/confirmación en la representación comparada y aplicando
los defaults y normalización del contrato. No se reemplaza la operación original. Un ID nuevo con el mismo contenido manual
crea otra operación, coherente con Android. Se conserva la deduplicación de contenido
importado que exigen las pruebas previas; ahora cada requestId deduplicado recibe
su propio recibo y no puede reutilizarse con otro payload.
Los recibos confirmados usan el SHA-256 existente del servidor, con una prueba de
valor conocido calculado desde `SessionRegistration`; no se inventa otro ID ni se
cambia el fingerprint del backend. El formulario conserva su hora de finalización
entre reintentos para evitar cambios de payload causados solo por pulsar Guardar.

**Regresiones ejecutadas:** `LearningIdempotencyTest` (5 pruebas), las pruebas
previas de repository y el caso backend de mismo ID/cuerpo modificado. Comprueban
30 frente a 90 minutos, tema/notas/fecha modificados, orden de claves, defaults,
recibos, nuevo ID con contenido manual idéntico, recibo de importación deduplicada y fallos de red tanto después del commit como durante el pull.
El payload inicial y toda la cola permanecen intactos ante conflictos.

## B. Compatibilidad y datos

- Se agregan `ActiveStudy.occurrenceDate`, `StudySession.occurrenceDate` y
  `SessionRegistration.occurrenceDate`, opcionales, con default `null`.
- Se agrega `LearningSnapshot.recoveryBase`, opcional con default `null`; protege
  la comparación remota mientras se recupera una copia.
- Los contratos conservan sus versiones. No hay migración SQL/Room: la tabla
  existente admite la fila `id=2`; versión y esquema de Room permanecen iguales.
- El JSON anterior se sigue leyendo. Las copias exportadas por versiones
  anteriores incluyen las colecciones y la cola; se probaron importación y replay.
  Un documento parcial o con campos no soportados se rechaza sin escribir.
- Una sesión recurrente antigua sin fecha sigue siendo consultable/persistida,
  pero su finalización es rechazada de forma recuperable. Puede detenerse sin
  completar y revisarse la agenda; el backend admite una selección explícita.
- Una actividad única antigua puede recuperar su único slot programado, incluso
  si fue pospuesto. Sin slots usa la fecha original de esa actividad única.
  Con varios slots y sin selección explícita también se rechaza la ambigüedad.
- No se rellenan retrospectivamente fechas de sesiones históricas.
- Instalar primero el backend compatible antes del cliente que envía el campo
  nuevo: el backend anterior prohíbe campos desconocidos. No se desplegó nada aquí.
- La comparación de recuperación es conservadora: cambios remotos concurrentes,
  incluso escrituras propias cuya respuesta se perdió durante una recuperación,
  pueden requerir revisión/exportación explícita. No se fusionan automáticamente
  agregados educativos divergentes ni se pierde la cola para eludir ese conflicto.
- La copia protegida se exporta por separado; el backup principal mantiene su
  contrato de una sola memoria. No se eliminan copias protegidas automáticamente.

## C. Pruebas ejecutadas

Se usó un bootstrap SQLite temporal mediante `DATABASE_URL` antes de importar
`app.main`; ninguna base personal o de producción se utilizó. PostgreSQL usó el
servicio temporal de pruebas y schemas `cf_test_*` eliminados por su fixture.

| Prueba/comando | Resultado | Observaciones |
| --- | --- | --- |
| `PYTHONPATH=backend pytest -q backend/tests` | 142 aprobadas | Ejecutadas en venv con bootstrap SQLite temporal; 13 casos nuevos. |
| `PYTHONPATH=backend pytest -q backend/integration/test_postgres.py` | 5 aprobadas | PostgreSQL 17 temporal; 1 regresión nueva de identidad/rollback. |
| Gradle `testDebugUnitTest` | 37 aprobadas | 15 pruebas JVM nuevas, algunas con múltiples escenarios. |
| Gradle `assembleDebug` | Aprobado | APK local de depuración; sin publicación. |
| Gradle `lintDebug` | Aprobado | Sin desactivar validaciones ni agregar supresiones. |
| Gradle `assembleDebugAndroidTest` | Aprobado | Incluye las 2 pruebas instrumentadas nuevas. |
| Pruebas instrumentadas en dispositivo | No ejecutadas | No hay emulador, imagen de sistema ni dispositivo; falta `/dev/kvm`. ADB además falla al crear su carpeta en el home de solo lectura. |
| `git diff --check` | Aprobado | Sin errores de whitespace. |
| Comparación de `docs/FINAL_AUDIT.md` con copia inicial | Idénticos | Archivo previo preservado. |

Gradle se ejecutó con la distribución 8.13, JDK/SDK y cachés ya instalados,
seleccionando el módulo Android. El wrapper de ejecución temporal propaga el código
de salida real de Gradle. No se modificaron scripts ni configuraciones de build.
La advertencia de deprecación de Starlette/httpx en pytest es preexistente.

## D. Estado del repositorio

Rama `consolidation/product-1.2`; base de implementación indicada al inicio.
Tras la autorización posterior del usuario, se creó el commit de implementación
`4935817` y se preparó un segundo commit con este informe para actualizar el PR.
Ambos incluyen `[skip ci]`: el workflow existente publica automáticamente una
prerelease en pushes a `consolidation/*`, y la prohibición de publicar/desplegar
sigue vigente. Por ello este push omite también los checks automáticos; los
resultados de C corresponden a las pruebas locales realmente ejecutadas.
Sin merge, cierre del PR, publicación ni despliegue.

Archivos de producción modificados (todos dentro del alcance):

- Android: `LearningBackup.kt`, `LearningData.kt`, `LearningScreens.kt`,
  `MainActivity.kt`, `StudyCoordinator.kt`.
- Backend: `learning.py`, `learning_models.py`, `learning_workflow.py`.

Pruebas existentes ampliadas: `LearningRepositoryTest.kt` y
`backend/integration/test_postgres.py`. Ninguna prueba previa fue eliminada.

Archivos nuevos de este trabajo:

- `android/app/src/main/java/com/calenflex/app/LearningIntegrity.kt`.
- `android/app/src/test/java/com/calenflex/app/LearningIntegrityTest.kt`.
- `android/app/src/test/java/com/calenflex/app/StudyOccurrenceTest.kt`.
- `android/app/src/androidTest/java/com/calenflex/app/LearningIntegrityPersistenceTest.kt`.
- `backend/tests/test_learning_integrity.py`.
- Este informe.

`git status` al finalizar la implementación contenía esos cambios sin stage y
los nuevos archivos sin seguimiento. La autorización posterior permite incluirlos
en los dos commits; el `docs/FINAL_AUDIT.md` previo permanece fuera de ellos. Se revisaron los diffs de archivos existentes
y el contenido completo de los archivos nuevos. `git diff --stat` no cuenta los
archivos sin seguimiento; deben incluirse al revisar el alcance.

## E. Riesgos pendientes fuera del alcance

La auditoría sigue vigente para los restantes hallazgos. En particular:

- **A-02:** duplicación de actividades únicas durante fusión; puede representar
  trabajo único más de una vez. Debe corregirse con sus propias regresiones.
- **A-04:** escritura concurrente con vinculación; puede quedar en el owner
  anterior. Requiere resolver concurrencia de identidad en una etapa separada.
- **M-02 a M-09 y hallazgos bajos:** mantienen el estado documentado en
  `FINAL_AUDIT.md`. Incluyen fechas/restricciones, seguridad de despliegue,
  revalidación JWKS, fusión de bloqueos, worker/permisos, privilegios PostgreSQL,
  retención y bootstrap de pruebas. No se intervinieron esos módulos.

La validación offline de Android debe mantenerse alineada con los contratos del
backend cuando cambien; la regresión de fingerprint y los casos de agregado
cubren el contrato actual. No se modificaron dependencias para introducir un
validador o sistema de idempotencia adicional.

## F. Evaluación final

1. **A-01: sí, corregido**, con matrices JVM de importación/reanudación, comprobación
   del estado completo y protección transaccional. Falta ejecutar el test de fallo
   SQLite en un dispositivo.
2. **A-03: sí, corregido**, con matrices backend/JVM y regresión PostgreSQL de
   identidad y rollback. Falta ejecutar su escenario Room en dispositivo.
3. **M-01: sí, corregido**, con idempotencia/conflictos, payload original intacto,
   retries tras commit/pull y coincidencia con la huella del backend.
4. Las pruebas que demuestran cada corrección están identificadas en A y C;
   compilar las instrumentadas no se presenta como haberlas ejecutado.
5. **Es razonable avanzar a la siguiente etapa de correcciones.** Esto no autoriza
   merge ni publicación: A-02/A-04 y la validación en dispositivo siguen pendientes.
6. Antes de continuar manualmente: ejecutar las instrumentadas; importar/exportar
   ambas copias y resolver un conflicto visible; recuperar una sesión antigua
   ambigua; finalizar una sesión después de medianoche en una zona no UTC;
   verificar backend compatible antes de instalar el cliente actualizado.
