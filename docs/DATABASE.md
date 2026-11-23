# Base de datos y migraciones

SQLAlchemy soporta SQLite de desarrollo y PostgreSQL 17 con psycopg 3.2.6. Cada owner autenticado es una clave de espacio; todos los servicios filtran por owner. La memoria educativa está separada de la agenda para que clientes anteriores no la borren.

| Tabla | Clave / responsabilidad |
| --- | --- |
| documents | owner; revision; payload State de agenda |
| learning_documents | owner; revision; payload LearningMemory |
| credentials | SHA-256 de token; owner/scopes/label/revocación |
| revoked_tokens | digest de credencial revocada |
| audit_entries | UUID; owner/acción/fecha/identificador; sin notas ni prompts |
| plan_proposals | UUID; owner/revisión base/resultado/estado anterior/expiración/revisión aplicada |
| schema_versions | versión y fecha; rechaza bases futuras |
| sync_receipts | owner + requestId; fingerprint y respuesta para reintento exacto |
| account_links | identidad OAuth pseudónima → owner existente |
| link_challenges | hash de código aleatorio; owner; vencimiento; consumo único |

Migraciones 1–2 incorporan memoria/credenciales; 3 propuestas; 4 cifrado; 5 recibos de sync y vinculación. Son aditivas, transaccionales e idempotentes. SQLite usa BEGIN IMMEDIATE y PostgreSQL un advisory lock de transacción. Se verifica el esquema histórico y no se cambian revisiones al cifrar. Ejecutar `cd backend && python -m app.manage migrate`; también se ejecuta al arrancar. Respaldar antes de actualizar.

Agenda, memoria, propuestas y recibos de sincronización usan sobres Fernet autenticados cuando se configura CALENFLEX_DATA_KEY; producción exige esa clave. Una clave incorrecta bloquea apertura, no genera una base vacía. La clave no se puede recuperar de la base: respaldarla por separado. No cambiarla directamente para intentar rotarla. Los JSON exportados son texto claro privado.

Room v3: workspace conserva payload/revision/dirty y añade syncBase, syncRequest y conflict; learning_cache conserva memoria completa, revisión, cola, recuperación pendiente y cronómetro. MIGRATION_1_2 y MIGRATION_2_3 preservan filas históricas; nunca se usa migración destructiva. Esquemas exportados en android/app/schemas.

## Transferencia SQLite → PostgreSQL

Detener escrituras, conservar una copia original y su clave. Crear una base PostgreSQL vacía, configurar SOURCE_DATABASE_URL y TARGET_DATABASE_URL en un entorno privado y ejecutar desde backend:

```sh
python -m app.transfer          # valida y muestra solo conteos; prepara esquema destino
python -m app.transfer --apply  # transferencia transaccional a destino vacío
```

La herramienta no modifica el origen, preserva owners/UUID/revisiones, verifica contratos y rechaza destinos con registros. No hace mezcla de bases ni reintentos destructivos. Se probó SQLite→PostgreSQL y rechazo de destino no vacío. Confirmar conteos, consultas por usuario y copias antes de cambiar DATABASE_URL. Conservar origen en solo lectura durante la validación. Rollback: volver a la copia previa y versión compatible; conservar por separado cualquier escritura posterior para reconciliarla, nunca sobreescribirla silenciosamente. Backup y restauración: DEPLOYMENT.md.
