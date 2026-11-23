# Persistencia

Android: Room/SQLite, tabla workspace descrita en ANDROID.md. Backend: SQLAlchemy/SQLite, tabla documents con owner (clave del espacio personal), revision y payload JSON validado. CALENFLEX_TOKEN autentica el acceso al espacio; su rotación no elimina la copia.

La unidad de sincronización es el documento completo. Se prioriza atomicidad de una copia personal sobre consultas relacionales complejas. El servidor aplica comparación optimista de revisión en UPDATE y unicidad de owner en INSERT. Una actualización concurrente devuelve 409 y no pisa datos. Los UUID son estables entre exportaciones e importaciones.

No hay migraciones históricas porque esta es la primera versión. Para cambios futuros, versionar schemaVersion y agregar migraciones probadas tanto en Python como en Kotlin. No cambiar silenciosamente el esquema de una copia.

# Esquema 1.1

SQL: documents (agenda histórica), learning_documents (agregado educativo con revisión por usuario), credentials (hash de tokens, scopes y estado), revoked_tokens, audit_entries, schema_versions y plan_proposals. Las migraciones adoptan y verifican la estructura anterior, serializan su ejecución y no reemplazan filas de agenda. La revisión 4 transforma payloads a sobres cifrados si se configura CALENFLEX_DATA_KEY; verifica la clave antes de convertir datos. Se prueba apertura con clave incorrecta sin pérdida de contenido.

LearningMemory conserva metas, hitos, temas, competencias con evidencia, sesiones, eventos, fuentes, próximos pasos, preferencias y recibos idempotentes. La coherencia referencial y los ciclos se validan antes de persistir. Los tiempos y conteos de evidencia derivan de registros, no de porcentajes generados por un modelo.

Room versión 2 agrega learning_cache; no altera workspace. La caché conserva el documento educativo completo para no descartar campos que la UI no utilice, y mantiene una cola de comandos estable frente a reintentos de red. Las copias incluyen esa cola. SQLite está validado; PostgreSQL requiere driver y una ejecución real de la misma batería antes de migrar producción.
