# CalenFleX

Planificador personal nativo para Android, en español argentino. Kotlin, Jetpack Compose y Material 3; backend Python/FastAPI con un motor determinista nuevo, persistencia SQLAlchemy e integración opcional de IA.

## Uso

1. Instalar el APK de depuración descargado desde **Actions → CalenFleX checks and APK → CalenFleX-debug** cuando la ejecución termine correctamente. Descomprimir el artefacto y abrir `app-debug.apk` en Android 8 o posterior. Permitir la instalación desde esa fuente si Android lo solicita.
2. Abrir la app, crear una actividad con `+` y configurar la disponibilidad en Perfil. Las actividades se guardan en Room, incluso sin Internet.
3. Para generar horarios, sincronizar y consultar IA, desplegar el backend en un servidor HTTPS según [DEPLOYMENT](docs/DEPLOYMENT.md) y guardar su URL y token en Perfil.
4. En Inicio, elegir **Organizar mi semana**. Revisar horarios, pendientes y cambios antes de aceptar. Los datos completados conservan su horario histórico.
5. Habilitar notificaciones de Android y elegir sus categorías en Perfil. WorkManager puede demorar los recordatorios por restricciones de batería.

La app no requiere una computadora para su uso cotidiano una vez instalado el APK y configurado un backend accesible. El repositorio no incluye un hosting ya desplegado. Sin backend se pueden administrar actividades, consultar horarios guardados, ver estadísticas y gestionar copias; la generación de planes y la IA requieren servidor.

## Funcionalidades

- Navegación Inicio, Calendario, Actividades, Estadísticas y Perfil.
- Crear, editar, buscar, filtrar, ordenar, duplicar, cancelar, completar y eliminar actividades.
- Fechas límite, duración, prioridades, días permitidos, franjas, exclusiones, preferencia horaria y horario fijo.
- Recurrencias diarias, semanales, mensuales y por intervalos; fechas omitidas y fin de serie.
- Calendario por día, semana, mes y agenda, presentado como listas de días cómodas para móvil.
- Planificación y reorganización con confirmación, restricciones, pendientes y conservación de ocurrencias completadas.
- Persistencia local, sincronización manual/automática con detección de conflictos, exportación/importación JSON y registro de cambios.
- Recordatorios previos, inicio y atraso; resúmenes periódicos y recordatorios de pendientes configurables.
- Estadísticas calculadas a partir de horarios reales y ocurrencias completadas.
- Temas claro, oscuro y sistema; zona horaria configurable, inicial Buenos Aires.
- Asistente real conectado a un proveedor OpenAI-compatible en el backend. Propuestas revisables y validación de respuestas; no hay respuestas simuladas en producción.

## Ejecutar backend

Requisitos: Python 3.12, conexión para instalar dependencias.

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
export CALENFLEX_TOKEN="tu-token-privado-largo"
cd backend
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Consultar `/docs`. El backend rechaza las rutas protegidas si CALENFLEX_TOKEN está vacío. Para persistencia local se usa `calenflex.db`; `DATABASE_URL` permite elegir otra ubicación. Para Docker, copiar `.env.example` a `.env`, configurar el token y seguir [despliegue](docs/DEPLOYMENT.md).

## Compilar Android

Requisitos: JDK 17, Android SDK con plataforma 35 y Build Tools 35.0.0, Android Studio opcional. El wrapper descarga Gradle 8.13. Definir ANDROID_HOME o `android/local.properties` con `sdk.dir`.

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Es un APK de depuración, no una versión firmada para Play Store. Para distribución definitiva hay que configurar firma privada y aumentar versionCode. No guardar el keystore ni contraseñas en Git.

## Pruebas

```sh
PYTHONPATH=backend pytest -q backend/tests
cd android
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest  # requiere emulador o teléfono conectado
```

GitHub Actions ejecuta backend, compilación, lint, tests JVM, tests instrumentados en emulador y conserva el APK como artefacto. Consultar [VALIDATION](docs/VALIDATION.md) para los resultados realmente ejecutados durante el desarrollo.

## Documentación

- [Arquitectura y política de sincronización](docs/ARCHITECTURE.md)
- [API y contratos](docs/API.md)
- [Android y persistencia](docs/ANDROID.md)
- [IA y configuración](docs/AI_INTEGRATION.md)
- [Despliegue y privacidad](docs/DEPLOYMENT.md)
- [Validación y limitaciones](docs/VALIDATION.md)

## Límites de esta entrega

El algoritmo es voraz, no un optimizador global. Las excepciones se convierten atómicamente en una actividad única y una fecha omitida en la serie desde el detalle de una ocurrencia. La suspensión temporal agrega fechas omitidas al intervalo elegido. Las franjas de disponibilidad delimitan descanso/sueño; no hay formularios separados para cada tipo. Los resúmenes se ejecutan periódicamente sin una hora exacta garantizada. No hay autenticación multiusuario, cuenta comercial de IA, hosting contratado, publicación en Play Store ni garantía de notificaciones en cada fabricante sin pruebas en un teléfono real. El código original no tenía backend ni pruebas; no existían las 54 pruebas mencionadas en el pedido inicial.

## Problemas frecuentes

- **Sin servidor configurado:** Perfil → URL HTTPS y token; el CRUD local sigue disponible.
- **401:** revisar token de la app y CALENFLEX_TOKEN del servidor.
- **409:** exportar copia y elegir versión local o remota en Perfil; no se descartan cambios automáticamente.
- **IA no disponible:** configurar AI_API_KEY y proveedor en el servidor; reiniciarlo.
- **Actividad sin horario:** revisar duración, anticipación, días y fechas límite; ampliar disponibilidad y generar otro plan.
- **No llegan notificaciones:** revisar permiso de Android, configuración de CalenFleX y restricciones de batería; WorkManager no proporciona alarmas exactas.
