# ADR 0001 — Memoria separada y evolución compatible

Estado: aceptado.

La agenda actual y la memoria educativa tienen ciclos de edición distintos. Un cliente Android v1 ignora campos JSON desconocidos y podría borrar una memoria añadida al mismo State al sincronizar. Por eso la memoria usa su propio documento tipado y revisión, persistido en una tabla nueva y aislado por principal autenticado. Los documentos de agenda y Room v1 no se reemplazan ni se reinterpretan.

Las entidades educativas se validan juntas en un agregado acotado: permite verificar referencias, dependencias, evidencia y borrados en una única transacción. Los contadores de tiempo y evidencia se calculan desde registros, no se aceptan de la IA. Los comandos de dominio son compartidos por REST y MCP. Esta primera evolución prioriza integridad de un espacio personal por usuario; una futura normalización relacional requiere otra migración y pruebas de equivalencia.

Las migraciones de esquema son aditivas, versionadas e idempotentes; se prueba la adopción de la base original sin cambiar su contenido ni revisión. PostgreSQL se evalúa como motor de producción usando el mismo contrato SQLAlchemy; no se afirma validado sin ejecutarlo.

La conexión histórica CALENFLEX_TOKEN sigue apuntando a personal. Nuevos tokens y sujetos OAuth no pueden elegir owner en el cuerpo de las solicitudes. Tokens nuevos se almacenan solo por hash y pueden revocarse. No se implementa un servidor de contraseñas ni un OAuth casero: se integra un proveedor OAuth/OIDC externo con verificación de emisor, audiencia, expiración y scopes.
