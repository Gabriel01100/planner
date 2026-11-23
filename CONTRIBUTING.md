# Contribuir

Crear rama desde main y abrir Pull Request. No subir secretos, .env, bases, dumps, keystores ni APK al árbol Git. Conservar migraciones y datos existentes; no usar migraciones destructivas ni desactivar tests para aprobar CI.

Ejecutar la suite pertinente y git diff --check. Cambios de dominio necesitan regresiones; contratos REST/MCP deben coincidir y proteger owner/scopes. Cambios de persistencia necesitan pruebas de reapertura/migración y rollback documentado. Proveedores reales se configuran fuera de CI; usar dobles en tests.

Describir problema, comportamiento resultante, decisiones y evidencia real. Actualizar la documentación temática, sin duplicar estados históricos en README. Añadir ADR cuando cambie un límite de dominio, identidad, datos o despliegue. La aprobación y fusión hacia main corresponden al mantenedor.
