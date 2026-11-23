# Android

Kotlin 2.1.20, Compose Material 3, Navigation Compose, Room 3 (versión de esquema, no versión de librería), DataStore, Retrofit y WorkManager. JDK 17, Gradle 8.13, AGP 8.9.2, SDK/target 35, mínimo Android 8/API 26. No hay claves de IA en Gradle/Kotlin ni en copias. Compilar:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

APK: app/build/outputs/apk/debug/app-debug.apk. Abrir el directorio android en Android Studio y sincronizar Gradle. En Configuración guardar URL HTTPS y credencial del propio espacio; se verifica identidad antes de aceptar un cambio de credencial. No usar localhost desde un teléfono. El trabajo local no requiere servidor, pero IA/planificación/sync sí.

AppViewModel coordina guardados, errores y carga. LearningWorkflow del servidor y una transacción Room local conservan coherencia entre una sesión finalizada y su agenda. Los errores no cierran formularios importantes. Cancelación de corrutinas se propaga. Pantallas y componentes están separados por responsabilidad; persiste un ViewModel de aplicación compartida, sin capas genéricas artificiales.

Room migra 1→2→3 sin destrucción. Agenda y aprendizaje tienen agregados y revisiones independientes. Cola educativa y comando de sincronización son persistentes. SYNC.md describe conflictos y recuperación. Android asocia la instalación a un espacio; no es un selector multicuenta.

WorkManager sincroniza cada 15 minutos como mínimo, con red. Solo después de sincronizar solicita el ajuste automático al servidor, que comprueba consentimiento y límites. Las notificaciones se recalculan al editar/planificar/sincronizar y el worker verifica de nuevo que la actividad siga vigente. POST_NOTIFICATIONS se exige desde API 33, no en versiones anteriores. El sistema puede demorar tareas por batería; no son alarmas exactas. Validar también en un teléfono real.

Vistas de calendario y accesibilidad: UX.md. Recepción/importación y copias educativas: ANDROID_IMPORT.md. Antes de cambiar entre APK debug con firmas diferentes, exportar agenda y aprendizaje: puede requerirse desinstalación. La firma estable de producción y Play Store no están configuradas.
