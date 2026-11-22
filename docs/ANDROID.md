# Android

Kotlin 2.1.20, AGP 8.9.2, Gradle 8.13, SDK 35 y mínimo API 26. Jetpack Compose Material 3 y Navigation Compose para cinco destinos. AndroidViewModel/StateFlow publica cambios de Room y coordina la capa de datos; Retrofit usa el mismo contrato JSON que FastAPI. DataStore conserva configuración de conexión y token cifrado con una clave local Android Keystore.

Los datos se guardan en SQLite mediante Room en una transacción por documento. El usuario puede cerrar la app sin perder los cambios confirmados. La revisión y el indicador dirty se guardan juntos. Una escritura de plan comprueba que el documento coincide con el que se envió al servidor. Las copias JSON incluyen datos y preferencias, sin credenciales de conexión.

WorkManager sincroniza con conectividad de red cada 15 minutos como mínimo. Los recordatorios se programan por fecha local en la zona elegida. Cambiar actividades, preferencias o planificación recalcula recordatorios. Antes de notificar, el worker consulta de nuevo el registro y descarta actividades completadas/canceladas. Android 13 o posterior necesita permiso POST_NOTIFICATIONS.

El APK de debug funciona sin credenciales para las operaciones locales. Para planificar e IA se configura un servidor HTTPS accesible desde el teléfono, no localhost ni una computadora que deba quedar encendida.

## Pruebas

Tests JVM de contratos, validaciones e importación. Tests instrumentados de reapertura de base Room y navegación con creación de actividad. Ejecutar `./gradlew connectedDebugAndroidTest` con dispositivo API 26+; el workflow usa API 35.

## Modelo de base

Tabla `workspace`: `id`, `payload`, `revision`, `dirty`. Una fila por instalación. El payload State v1 contiene actividades con UUID, recurrencias y excepciones, disponibilidad, bloqueos, horarios y ocurrencias completadas, preferencias, pendientes e historial. Room genera el esquema en `app/schemas`. Una versión futura debe agregar migraciones explícitas, nunca migración destructiva.
