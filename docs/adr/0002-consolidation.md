# ADR 0002 — Consolidación sin reescritura

Estado: aceptado.

Conservar agregados versionados evita una migración relacional de memoria sin necesidad demostrada. Para agenda se añade fusión de tres versiones por entidad/campo con recibos idempotentes; modificaciones incompatibles permanecen explícitas y exportables. Clientes legacy usan CAS hasta conocer una base. Una copia de conflicto guarda ambas versiones, no solo una advertencia.

LearningWorkflow es el límite transaccional de aplicación para memoria+agenda. Los adaptadores REST/MCP comparten ese flujo. Se mantienen servicios concretos y se evita crear interfaces genéricas de repositorio sin otro consumidor real. Reglas de planificación se comparten con el validador; CP-SAT se mantiene experimental hasta demostrar equivalencia y mejora con los mismos criterios.

La autonomía requiere un opt-in nuevo (automaticEnabled=false incluso en memorias antiguas), presupuesto móvil de siete días, consentimiento bloqueado y revisión CAS. Nunca altera compromisos fijos, preferencias, fechas límite o prioridades. Una propuesta aplicada automáticamente conserva estado anterior para reversión. No se activa autonomía total.

Identidad: asociar OAuth a un owner móvil requiere demostrar ambos accesos, Authorization Code/PKCE S256/state, código breve de un uso y confirmación. No se copian datos entre espacios con información. Los tokens permanecen separados y revocables. Un login OAuth nativo no es necesario para conservar el token móvil administrado; la página de vinculación resuelve identidad sin inventar un proveedor propio.

Producción añade driver psycopg, integración PostgreSQL real, transferencia offline a destino vacío, TLS Caddy y health checks. OR-Tools sigue como dependencia opcional, no se instala en backend de producción. Tests Node usan runtime ya disponible en CI, sin librerías de frontend. Android no agrega dependencias de calendario: usa Compose existente y pruebas de grilla/horarios.

Room v3 añade base/request/conflicto con migración aditiva. Pantallas de calendario, actividades, configuración y componentes se separan de MainActivity; el ViewModel coordina guardados y éxito. No se divide por tamaño sin límite de responsabilidad.
