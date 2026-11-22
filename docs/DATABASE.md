# Persistencia

Android: Room/SQLite, tabla workspace descrita en ANDROID.md. Backend: SQLAlchemy/SQLite, tabla documents con owner (clave del espacio personal), revision y payload JSON validado. CALENFLEX_TOKEN autentica el acceso al espacio; su rotación no elimina la copia.

La unidad de sincronización es el documento completo. Se prioriza atomicidad de una copia personal sobre consultas relacionales complejas. El servidor aplica comparación optimista de revisión en UPDATE y unicidad de owner en INSERT. Una actualización concurrente devuelve 409 y no pisa datos. Los UUID son estables entre exportaciones e importaciones.

No hay migraciones históricas porque esta es la primera versión. Para cambios futuros, versionar schemaVersion y agregar migraciones probadas tanto en Python como en Kotlin. No cambiar silenciosamente el esquema de una copia.
