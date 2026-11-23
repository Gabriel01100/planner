# Pruebas y evidencia

Base auditada ab48550: 112 backend y 18 JVM reejecutadas, compilación/lint aprobados. Los seis instrumentados previos aprobaron en Actions 45117900855, incluidos los dos fallos históricos corregidos. No se confunde esa ejecución con la validación nueva.

## Consolidación local

- Backend: **129 pruebas aprobadas localmente**. Incluye las 112 originales y regresiones de días permitidos, DST, colisiones con completadas, dependencias/carga, merge/reintento/aislamiento, evaluación explícita, vinculación JWT, autorización de preferencias, autonomía opt-in/reversión y coordinación agenda/memoria.
- Android: assembleDebug, testDebugUnitTest, lintDebug y assembleDebugAndroidTest aprobados localmente. 21 JVM: 18 anteriores más calendario mensual/leap year y sincronización con respuesta perdida/conflicto exportable.
- PostgreSQL 17: cuatro integraciones reales aprobadas: migración/cifrado/aislamiento, concurrencia CAS, reintento/reapertura y transferencia SQLite→PostgreSQL a destino vacío.
- OAuth web: dos tests Node aprobados con proveedor simulado: PKCE S256/state/recurso/confirmación y rechazo de estado incorrecto/vencido. No son una conexión externa real.
- CP-SAT: una prueba comparativa cubre nueve escenarios con entradas/reglas/métricas iguales; ambos motores dieron cero conflictos. Resultado reproducible en benchmarks/equivalent-evaluation.json; no demuestra superioridad global.
- Backup: pg_dump/pg_restore ejecutados en bases de prueba separadas PostgreSQL 17; revisión y memoria verificadas. Configuración Compose/Caddy validada con valores ficticios. Imagen Docker compilada y /ready comprobado contra PostgreSQL con cifrado production, filesystem de solo lectura y capabilities eliminadas; sin publicar servicios en Internet. El proxy de este entorno requirió proporcionar su CA al build mediante secreto BuildKit, sin desactivar TLS.

## Comandos

```sh
pip install -r backend/requirements.txt
PYTHONPATH=backend pytest -q backend/tests
node --test backend/webtests/account-link.test.cjs
# Solo una base desechable, nunca una URL de producción:
TEST_POSTGRES_URL=postgresql+psycopg://USUARIO:CLAVE@localhost/BASE_DE_PRUEBA PYTHONPATH=backend pytest -q backend/integration
pip install -r backend/requirements-optimization.txt
PYTHONPATH=backend pytest -q backend/experiments/test_equivalence.py
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Los tests PostgreSQL crean/eliminan exclusivamente esquemas cf_test_UUID de la base de prueba. No ejecutarlos con credenciales de producción. Lint conserva advertencias de dependencias nuevas disponibles; no hay errores. pytest advierte una deprecación del cliente Starlette/httpx; no invalida resultados ni se oculta.

## CI y dispositivo

CI exige backend + OAuth web, PostgreSQL, experimento y Android; el emulador API 35 ejecuta los seis tests existentes más un flujo de grilla mensual → día → evento → semana. Incluye migración Room 1→3 y copia de agenda histórica. No hay tests desactivados. Este entorno no tiene KVM: la ejecución real de dispositivo se realiza en Actions. Los reportes y capturas del calendario están en el artefacto Android-device-reports. El resultado de la ejecución enlazada en el PR es la fuente de verdad; compilar instrumentados no equivale a ejecutarlos.

El APK de prerelease se genera solo si aprueban los cinco jobs. Es debug, no firma estable ni Play Store. Comprobar firma con apksigner y paquete/API con aapt. No se hicieron consultas pagas, conexión real ChatGPT/OAuth, despliegue público ni evaluación con participantes. Notificaciones y accesibilidad requieren también verificación en teléfonos reales de distintos fabricantes.
