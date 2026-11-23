# Auditoría de consolidación — base ab48550

Checkout limpio en main, igual a origin/main; se trabaja en consolidation/product-1.2, con PR hacia main. Se inspeccionaron README, documentación, contratos, servicios, planificador/validador, adaptadores IA/MCP, repositorios Room, navegación, formularios, importación, workers y CI. No se modifica la base local ni se publican sus datos.

Base ejecutada: 112 pruebas Python aprobadas, 18 JVM aprobadas; assembleDebug, assembleDebugAndroidTest y lintDebug aprobados. La ejecución previa 45117900855 aprobó los seis tests instrumentados (incluidos los dos antes corregidos); no se confunde ese resultado previo con una nueva ejecución.

| Área | Estado observado y brecha |
| --- | --- |
| Agenda | Motor determinista probado. Validador no verifica días permitidos en únicas y comparte reglas solo parcialmente. Falta control de DST, dependencias y carga. |
| Memoria/IA | Registro idempotente y propuestas estrictas; competencias solo existen en el contrato, sin flujo de evaluación explícita. |
| MCP | 19 herramientas y JWT verificables; identidad móvil depende de configuración administrativa manual. Falta vinculación de dos credenciales con doble prueba de posesión. |
| Sync | CAS impide sobrescrituras silenciosas; conflicto requiere reemplazar una versión completa. Falta fusión de cambios independientes y backup de la versión en conflicto. |
| Android | Room v2 y cola educativa persistente; UI llama repositorios directamente y MainActivity mezcla pantallas. Agenda es una lista, no una grilla de calendario. |
| Producción | Cifrado y Docker presentes. PostgreSQL sin driver/test real, sin proxy TLS reproducible ni readiness. Cuotas son por proceso. |
| Documentación | Mezcla estados históricos contradictorios; README.me describe un stack distinto. Consolidar por tema y mantener evidencia histórica separada. |

Plan: (1) reglas compartidas y regresiones del planificador; (2) fusión versionada y servicios de identidad/evaluación con pruebas; (3) autonomía acotada opt-in y comparación CP-SAT con mismos contratos/criterios; (4) separar calendario y acciones Android, integrar flujos; (5) PostgreSQL/TLS/backup/CI y documentación única. Cada bloque tendrá pruebas y commit. Conservar el motor hasta demostrar equivalencia; no inventar OAuth, hosting, consultas pagas ni pruebas de usabilidad humana. Los pasos externos se documentan con precisión.

Dependencias: autonomía necesita primero validación y revisiones; el calendario puede construirse sobre slots existentes. PostgreSQL debe probarse antes de recomendar migración. La vinculación debe conservar el propietario original, sin copiar datos ni confiar en un owner recibido en JSON.
