# Estado real y hoja de ruta

Base y plan: CONSOLIDATION_AUDIT.md. Decisiones: adr/0001 y adr/0002. La rama de consolidación se revisa mediante PR; main no se sobrescribe ni se fusiona automáticamente.

| Área | Entregado en código | Verificación / pendiente externo |
| --- | --- | --- |
| Agenda | reglas compartidas, restricciones, dependencias, carga, estabilidad y propuestas reversibles | regresiones y comparación con validador; motor voraz sin óptimo global |
| Memoria | sesiones, recursos, fuentes, idempotencia, competencias solo con resultados explícitos y confianza declarada | tests de invariantes, aislamiento y coordinación transaccional |
| IA | Responses estricto, contexto mínimo, preguntas y revisión antes de guardar | proveedor simulado; clave y llamada real pendientes del operador |
| MCP | 19 herramientas, JWT/scopes, identidad móvil vinculable, revocación | protocolo/JWT probados; proveedor, clientes OAuth y consentimiento ChatGPT reales pendientes |
| Autonomía | opt-in, movimientos pequeños del mismo día, presupuesto semanal, límites y reversión | pruebas de seguridad/consentimiento; modo totalmente autónomo deshabilitado |
| Sync | fusión de cambios independientes, recibos, conflictos exportables y cola persistente | tests Python/JVM; incompatibilidades requieren decisión explícita |
| Producción | PostgreSQL/driver, migraciones, transferencia offline, TLS/Compose, readiness/logs, backup | cuatro integraciones PostgreSQL y restore real de prueba; DNS/hosting/TLS público pendientes |
| Android | cuatro destinos, calendario mensual y por horas, formularios que conservan errores, Room v3 | JVM/build/lint locales; instrumentados en CI y revisión de capturas |
| CP-SAT | experimento con entradas/reglas/métricas comunes y nueve escenarios | no se habilita en producción; ampliar corpus y medir rendimiento antes de adopción |

Próximos pasos operativos: revisar PR/CI/capturas, configurar dominio/secretos en el servidor, registrar clientes OAuth, probar /connect y consentimiento MCP, realizar una sesión real desde ChatGPT y Android y verificar identidad/deduplicación, configurar firma Android estable y validar en teléfono. No se necesitan ni deben enviarse secretos a una conversación.

Desarrollo posterior: login OAuth nativo si se quiere reemplazar token administrado, cuotas distribuidas para varios workers, políticas de retención operativa, sincronización multicuenta/collaboración a escala, segunda ocurrencia de horas DST ambiguas, grilla con drag-and-drop y corpus extendido del optimizador. Ninguno se presenta como implementado. La autonomía actual no modifica duraciones ni prioridades por aprendizaje sin revisión.
