# CalenFleX 1.1

Planificación personal y memoria de aprendizaje, con Android nativo y backend propio. Esta evolución conserva la agenda anterior y agrega objetivos, sesiones confirmadas, recursos, próximos pasos e integración MCP compatible con herramientas de ChatGPT.

**[Descargar APK desde Releases](https://github.com/Gabriel01100/planner/releases)** · [Hoja de ruta y evidencia por etapa](docs/EVOLUTION_ROADMAP.md)

## Uso en Android

Requiere Android 8 o posterior. Descargar CalenFleX-debug.apk desde una prerelease cuya ejecución de Actions haya aprobado backend, compilación y dispositivo. Es una compilación de depuración, no una publicación en Play Store.

- **Hoy:** próxima actividad, comenzar y finalizar sesión. El tiempo medido se puede corregir; una actividad general se puede completar sin inventar un objetivo educativo.
- **Agenda:** calendario y CRUD de actividades, con opciones avanzadas desplegables. La reorganización se previsualiza y requiere aceptación.
- **Objetivos:** crear, registrar progreso y recursos, consultar tiempos reales y próximos pasos, corregir y eliminar con confirmación.
- **Asistente:** conversación breve y propuestas editables. El progreso del objetivo se comparte solo si se habilita expresamente.

Configuración y estadísticas están en la cabecera. Sin conexión, agenda, objetivos y registros locales siguen disponibles. Los comandos educativos quedan en Room y se sincronizan con IDs estables. El asistente, análisis de documentos, generación de planes y sincronización necesitan un backend HTTPS configurado.

Compartir desde ChatGPT, NotebookLM, un navegador o un lector compatible abre una revisión de texto, enlaces o documentos. No se leen conversaciones privadas ni se descargan enlaces automáticamente. Analizar no equivale a guardar. [Guía de Android e importación](docs/ANDROID_IMPORT.md).

Antes de desinstalar o cambiar entre APK de depuración con firmas diferentes, exportar **agenda y aprendizaje** desde Configuración. Android puede exigir desinstalar si la firma cambió. Las actualizaciones de distribución necesitan una clave privada estable, que no se incluye en Git.

## Backend local y pruebas

Python 3.12. Para desarrollo:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
export CALENFLEX_TOKEN="tu-token-privado-largo"
cd backend
python -m app.manage migrate
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Consultar `/docs` para contratos y autorización. CALENFLEX_TOKEN conserva el espacio histórico personal. Los tokens nuevos son revocables, tienen scopes y se almacenan por hash. El servidor selecciona el usuario autenticado; ningún JSON puede elegir owner. Las versiones antiguas de Android conservan la API de agenda y no pueden borrar la memoria educativa nueva.

```sh
PYTHONPATH=backend pytest -q backend/tests
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest  # emulador o dispositivo conectado
```

Android usa JDK 17, SDK 35, Kotlin 2.1.20, Compose/Material 3, MVVM, Room, DataStore, Retrofit y WorkManager. El wrapper descarga Gradle 8.13 y verifica su checksum. APK local: android/app/build/outputs/apk/debug/app-debug.apk.

## Servidor, cifrado e integración con ChatGPT

Para usar el teléfono sin una computadora encendida, desplegar el backend en un servidor HTTPS permanente. No se ha contratado ni desplegado ese hosting. Configurar la URL y el token de su espacio en Android.

En producción, definir CALENFLEX_ENV=production y una CALENFLEX_DATA_KEY Fernet válida, guardada en un gestor de secretos y respaldada por separado. Las migraciones cifran agenda, memoria y propuestas sin reemplazar sus registros ni revisiones. Una clave incorrecta detiene la apertura; no produce una base vacía. En desarrollo, sin clave, los datos son texto claro. [Despliegue y recuperación](docs/DEPLOYMENT.md).

MCP Streamable HTTP se habilita en `/mcp` al configurar OAUTH_ISSUER, OAUTH_JWKS_URL y OAUTH_RESOURCE_URL HTTPS de un proveedor OAuth/OIDC real. Verifica firma RS256, emisor, audiencia, expiración y permisos. Hay 19 herramientas, incluidas las 17 solicitadas, para registrar sesiones y consultar progreso/agenda. [Guía de conexión con ChatGPT](docs/MCP.md).

Las pruebas usan credenciales y proveedores simulados. No se declara conectado a una cuenta real de ChatGPT ni publicado en el directorio de apps. La disponibilidad de conectores de desarrollo depende de la cuenta. La suscripción de ChatGPT no proporciona una clave de API ni acceso a conversaciones privadas.

## IA y planificación

Configurar AI_API_KEY, AI_BASE_URL, AI_MODEL y AI_REQUESTS_PER_HOUR en el servidor para propuestas reales con Responses API y Structured Outputs. Sin clave, el trabajo local y el registro manual continúan. El backend no guarda por analizar; las propuestas se validan y revisan antes de pasar a servicios de dominio. No se ejecuta SQL ni instrucciones de documentos.

El motor original sigue en producción. Un envoltorio conserva sesiones válidas durante la reorganización. CP-SAT se evaluó en veinte escenarios simplificados: 83 tareas programadas frente a 71 del motor actual. No es una prueba de superioridad general y no se activó. [Evaluación y límites de autonomía](docs/OPTIMIZATION.md).

Las sugerencias de comportamiento se basan en registros suficientes y muestran evidencia; no castigan omisiones ni alteran prioridades por su cuenta. El modo totalmente autónomo no está disponible. La adaptación automática, dependencias del optimizador completo y reparto multiobjetivo continúan en la hoja de ruta.

## Documentación

- [Arquitectura y ADR](docs/ARCHITECTURE.md) · [Decisión de compatibilidad y cifrado](docs/adr/0001-learning-and-compatibility.md).
- [API de agenda](docs/API.md) · [API educativa y propuestas](docs/LEARNING_API.md) · [MCP](docs/MCP.md).
- [Android](docs/ANDROID.md) · [Nuevo flujo e importación](docs/ANDROID_IMPORT.md) · [Revisión UX](docs/UX_EVOLUTION.md).
- [IA](docs/AI_INTEGRATION.md) · [Base de datos](docs/DATABASE.md) · [Despliegue](docs/DEPLOYMENT.md).
- [Resultados realmente ejecutados y limitaciones](docs/VALIDATION.md) · [Roadmap](docs/EVOLUTION_ROADMAP.md).
