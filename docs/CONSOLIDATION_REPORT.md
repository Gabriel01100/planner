# Informe de consolidación 1.2

PR: https://github.com/Gabriel01100/planner/pull/1. Rama consolidation/product-1.2, base ab48550. No se modifica ni fusiona main automáticamente. La auditoría y estado inicial están en CONSOLIDATION_AUDIT.md; el resultado vigente de CI se consulta en los checks del PR. El APK y dos capturas del calendario se publican como prerelease solo tras aprobar todos los jobs.

## Resultado funcional

Agenda cambia de lista a grilla mensual real y columnas horarias de semana/día. Se conserva CRUD, recurrencias, excepciones, detalles, estadísticas, importación, copias y los cuatro destinos. MainActivity deja de alojar todas las pantallas; CalendarScreen, ActivityScreens, SettingsScreen y componentes tienen límites concretos. ViewModel coordina acciones; StudyCoordinator toma el bloqueo compartido antes de la transacción Room. Guardados importantes conservan formularios ante error. Sesiones finalizan en la fecha/zona elegidas y una recurrencia no se completa entera por finalizar un día.

Backend comparte reglas de generador y validador, verifica días permitidos en únicas, DST, dependencias, carga y superposiciones con completadas sin depender del orden. LearningWorkflow coordina progreso y finalización de agenda en una transacción. Competencias se actualizan solo con resultados correctos/total, método y confianza declarada; get_goal_details expone esa evidencia. Reimportaciones deduplicadas conservan también el requestId del reintento.

Sincronización agrega fusión por entidad/campo, recibos persistentes y exportación de base/local/remoto. Cambios contradictorios no se adivinan ni se pierden silenciosamente. Una instalación no puede cambiar a una credencial de otro owner enviándole pendientes. Room migra 1→2→3 sin destrucción. El servidor conserva campos nuevos cuando los omite un cliente antiguo y rechaza dependencias que quedarían huérfanas.

MCP mantiene 19 herramientas y servicios compartidos. /connect implementa Authorization Code/PKCE S256/state y código móvil de un uso, con consentimiento y revocación de vínculo sin borrar datos. JWT firmado se prueba de punta a punta para acceder al mismo espacio desde identidad OAuth vinculada; otro sujeto permanece aislado. No se afirma conexión externa real.

La autonomía inicial está desactivada por defecto. Solo permite pequeños desplazamientos de sesiones flexibles del mismo día, con presupuesto de siete días, consentimiento comprobado y reversión. Cambios mayores requieren revisión. CP-SAT se compara con las mismas entradas, restricciones y métricas en nueve casos, sin sustituir al motor.

PostgreSQL dispone de driver, migraciones/integración y transferencia offline a destino vacío, con rechazo anterior a modificar un destino con registros. Compose de producción incorpora PostgreSQL, Caddy/TLS, readiness, usuario sin privilegios y protección del filesystem. El Dockerfile corrige permisos de COPY y admite CA de build mediante secreto sin desactivar TLS. Logs incluyen requestId/ruta/estado/duración, nunca cuerpos o query OAuth.

## Evidencia ejecutada

- Base reejecutada: 112 backend, 18 JVM, compilación/lint. Seis instrumentados previos aprobados en 45117900855.
- Consolidación local: **129 backend, 22 JVM, cuatro integraciones PostgreSQL 17, dos pruebas Node OAuth simuladas**; una prueba CP-SAT cubre **nueve escenarios**. Compilación, lint y APK instrumentado aprobados.
- Backup: pg_dump/pg_restore real sobre bases de prueba separadas conserva memoria/revisión. Transferencia SQLite→PostgreSQL conserva datos y rechaza destinos no vacíos. Imagen Docker compilada y /ready verificado con cifrado production, PostgreSQL y filesystem de solo lectura; Caddy/Compose validados.
- Primera ejecución de rama: siete instrumentados aprobados; falló después la extracción de capturas de caché. Se corrige con MediaStore.
- Segunda ejecución: siete de ocho instrumentados aprobados, incluida concurrencia/rollback; el test de creación se ajusta para esperar cierre exitoso del formulario asíncrono y evitar ambigüedad de nodos. No se eliminó ninguna prueba. Los ocho se vuelven a ejecutar en los checks finales.
- Se revisaron diferencias, enlaces de documentación y patrones de secretos; no se agregan bases, dumps, tokens, keystores ni APK al árbol Git. Análisis de importaciones Python: sin dependencias circulares locales.

## Decisiones, dependencias y límites

ADR 0002 justifica agregados/revisiones, fusión conservadora, transacciones de aplicación, identidad con doble posesión y autonomía opt-in. Nueva dependencia backend: psycopg[binary] 3.2.6 para PostgreSQL. Caddy 2 y PostgreSQL 17 son servicios opcionales de despliegue. OR-Tools sigue en requirements-optimization, fuera del runtime productivo. Android no añade una biblioteca de calendario; usa Compose existente. Los tests web no añaden librerías frontend.

No se presenta el proyecto como finalizado solo por compilar. Falta configuración real de dominio/hosting/TLS, proveedor OAuth, cliente PKCE, cliente/consentimiento ChatGPT, clave API y llamada real opcional, firma estable y validación en teléfonos reales. Los pasos externos están detallados en MCP.md y DEPLOYMENT.md. No enviar esos secretos a una conversación.

Límites de producto: optimizador voraz sin óptimo global; muestra CP-SAT acotada; autonomía sin cambios de prioridades/duración ni modo total; cuotas por proceso (un worker); token móvil administrado en vez de login OAuth nativo; conflictos incompatibles con decisión explícita; copia de clave indispensable; sin Play Store, drag-and-drop ni estudio de usabilidad con participantes. Conservar ambas copias antes de instalar un APK debug de firma distinta.

## Ejecutar y desplegar

README contiene instalación Python/Android, variables, Docker, pruebas y APK. API.md/MCP.md describen contratos; DATABASE.md/SYNC.md migración/recuperación; DEPLOYMENT.md DNS/TLS, backup/restore y rollback. No basta cambiar DATABASE_URL para migrar SQLite. Revisar PR y CI antes de fusionar; esta entrega deja main intacta.
