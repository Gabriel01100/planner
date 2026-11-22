# Validación de la entrega

## Ejecutado localmente

- Backend: `PYTHONPATH=backend pytest -q backend/tests`: **71 pruebas aprobadas**. Incluyen 40 escenarios aleatorios con verificación independiente de ocupación, recurrencias, excepciones, bloqueo fijo, restricciones y validación; API, persistencia tras reiniciar, rotación de credencial, control de revisión y proveedores de IA simulados.
- Android: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest`: compilación aprobada. **13 pruebas JVM aprobadas**, sin errores. Tests de contratos, copias, validación, cálculo de espacios libres, repositorio, conflicto y rechazo de planes desactualizados.
- Firma de APK de depuración: verificada con `apksigner verify`. Metadatos comprobados con `aapt dump badging`: paquete com.calenflex.app, mínimo API 26, target API 35.
- Git: revisión de diferencias y búsqueda de patrones de credenciales; no se agregan .env, bases locales, keystores ni tokens al repositorio.
- Lint advierte sobre versiones más nuevas de dependencias. Se mantienen las versiones estables compatibles que fueron compiladas y probadas.

## Instrumentación y servicios externos

Los tests instrumentados de Room y de navegación/creación están implementados y su APK compila. Esta máquina no tiene /dev/kvm; **no se ejecutaron en un emulador local**. El workflow incluye un job de emulador para ejecutarlos en GitHub Actions. El estado de cada ejecución en Actions es la fuente de verdad; compilar los tests no equivale a ejecutarlos.

No se realizaron consultas pagas a un proveedor de IA ni se desplegó un backend público: requieren credenciales y hosting. Las notificaciones necesitan comprobarse también en un teléfono real, con permisos, restricciones de batería y sistema del fabricante. No se declara validada la experiencia en todos los dispositivos.

## Límites funcionales conocidos

- El motor es voraz y determinista; no garantiza óptimo global. Busca en una rejilla de cinco minutos y limita el horizonte a 93 días y la cantidad de ocurrencias a 10000 por petición.
- El calendario muestra listas de días agrupadas por el período elegido; no hay grilla mensual ni arrastrar y soltar.
- Disponibilidad y bloqueos expresan sueño, descanso y ocupaciones; no hay asistentes separados para esas categorías.
- Los conflictos se muestran al previsualizar y no se permite aplicar un plan inválido. No hay una categoría independiente de notificación push para conflictos.
- Los pendientes se reintentan al generar/reorganizar otra planificación semanal. No hay un comando separado para reintentar únicamente una ocurrencia sin regenerar las demás.
- La IA tiene una interfaz de proveedor y un adaptador OpenAI-compatible; otros protocolos requieren implementar otro adaptador.
- Las copias JSON reemplazan el espacio local con confirmación; no se fusionan automáticamente. El token y la URL del servidor se configuran por separado.
- Los tiempos realizados se infieren de la duración planificada de las ocurrencias completadas. No hay cronómetro ni medición automática del trabajo.
- Los resúmenes usan periodicidad de WorkManager, sin una hora exacta ni alarmas garantizadas.
- Servidor personal con token único y SQLite. No es una plataforma multiusuario ni tiene login social.
- El APK usa firma de depuración. No hay publicación en Play Store ni firma de distribución administrada.

Esta entrega contiene una implementación funcional desde cero, pero las validaciones pendientes y estos límites impiden afirmar que cumple íntegramente todos los criterios del documento original.
