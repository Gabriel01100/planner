# Sincronización y recuperación

Agenda usa fusión de tres versiones: última base sincronizada, cambios locales y estado remoto actual. Android conserva base y requestId en Room antes de enviar. `POST /api/v1/sync/merge` compara entidades por UUID, ocurrencias por actividad/fecha y campos de preferencias/actividad individualmente. Dos cambios independientes se conservan; editar y borrar la misma entidad o cambiar el mismo campo de dos maneras produce 409. Disponibilidad y listas de restricciones se tratan como unidades, sin adivinar intención. El resultado vuelve a validarse, incluidas referencias y conflictos nuevos del calendario.

El servidor aplica CAS de revisión y persiste la respuesta por owner/requestId en la misma transacción. Un reintento tras pérdida de respuesta devuelve el resultado original sin duplicar la escritura. Reutilizar requestId con otro contenido se rechaza. El siguiente sync descarga cambios posteriores; no se retrocede una revisión local. WorkManager y la sincronización manual comparten repositorios y exclusión mutua.

Un 409 deja los datos locales intactos y guarda una copia de conflicto con base/local/remoto y revisión remota. Configuración permite exportarla antes de resolver. Las acciones de reemplazo siguen disponibles con confirmación; pueden descartar cambios de una versión y deben usarse solo después de revisar/exportar. No se afirma que resolver automáticamente dos intenciones contradictorias sea posible.

Instalaciones antiguas migradas sin base conocida usan PUT /api/sync con CAS hasta la primera sincronización exitosa; nunca se inventa una base. El endpoint legacy conserva compatibilidad. Campos nuevos de asociación, dependencias y carga se preservan cuando un cliente antiguo los omite.

Aprendizaje usa comandos persistentes con UUID/requestId estables. Crear objetivos, registrar sesiones y recursos es idempotente. Las correcciones y borrados requieren revisión y una cola vacía. La copia educativa conserva la cola y bloquea autosync tras importar hasta elegir recuperación explícita. Un espacio remoto con datos nunca se reemplaza al restaurar una copia educativa. Agenda y aprendizaje tienen copias separadas; exportar ambas.

La instalación está asociada a un espacio. Guardar otra credencial verifica la identidad en el servidor y rechaza un owner diferente. Cambiar de servidor o cuenta requiere exportar datos y usar una instalación limpia/separada; no se envía automáticamente la cola a otra cuenta. Ante credencial antigua revocada sin identidad local conocida, se requiere recuperarla/verificarla antes de cambiarla.
