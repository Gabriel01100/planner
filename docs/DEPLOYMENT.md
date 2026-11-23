# Servidor independiente de la computadora

Para planificar, sincronizar y usar IA desde el teléfono, el backend debe estar en un servidor HTTPS accesible permanentemente (VPS o hosting de contenedores). La aplicación no aloja Python dentro del teléfono. Sin servidor, CRUD, consulta de horarios guardados, estadísticas y copias locales funcionan; generar planes e IA requieren conexión.

1. Copiar `.env.example` como `.env` y generar CALENFLEX_TOKEN con `python -c 'import secrets; print(secrets.token_urlsafe(48))'`.
2. En un host con Docker, ejecutar `docker compose up -d --build`.
3. Publicar el puerto local 8000 detrás de Caddy/nginx con TLS y dominio propio. No exponer HTTP directamente a Internet. Configurar límites de tamaño y solicitudes en el proxy.
4. Verificar `https://tu-dominio/health` y los endpoints protegidos.
5. En Perfil del teléfono, guardar la URL HTTPS y el token. Sincronizar manualmente una vez.
6. Conservar y respaldar el volumen `calenflex-data`. Detener escrituras antes de copiar SQLite o usar la API de backup de SQLite. Probar la restauración en una instancia separada.

El contenedor usa un usuario sin privilegios y un volumen persistente. No hay un hosting contratado ni credenciales externas configuradas por este repositorio. No se ha desplegado automáticamente un servicio público.

SQLite es el almacenamiento predeterminado para un espacio personal. SQLAlchemy permite cambiar DATABASE_URL, pero para PostgreSQL hay que instalar un driver adecuado y comprobar la migración; no se ha validado PostgreSQL en esta entrega. Compose fija SQLite para su volumen; adaptar Compose si se cambia de motor.

## Privacidad

Los datos permanecen en el teléfono hasta sincronizar o consultar IA. El servidor almacena la copia completa y debe protegerse con TLS, token privado y permisos de disco. La IA recibe el contexto completo después de la acción explícita del usuario. Revisar las políticas del proveedor. Los JSON exportados son legibles: guardarlos en un lugar privado. El token da acceso completo al espacio; rotarlo requiere actualizar el teléfono; la copia del servidor conserva su identidad durante la rotación.

# Actualización 1.1

Respaldar primero la base y ambas copias del dispositivo. `python -m app.manage migrate` aplica migraciones aditivas bajo bloqueo de base; el arranque también las ejecuta. Se preserva la tabla documents y sus revisiones. La memoria y las propuestas usan tablas separadas.

Para producción definir CALENFLEX_ENV=production y CALENFLEX_DATA_KEY. Generar la clave localmente con `python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'`, guardarla en el gestor de secretos y respaldarla separadamente de la base. No pegarla en chats ni Git. El arranque cifra filas anteriores en una transacción y rechaza claves incorrectas; no puede recuperar una clave perdida. No rotar la clave simplemente cambiando la variable sobre una base cifrada: restaurar con la clave original y preparar una migración de rotación probada.

Configurar OAuth externo según MCP.md. No hay proveedor OAuth, dominio, servidor HTTPS ni credenciales de OpenAI contratados/configurados por esta entrega. Las pruebas de protocolo/JWT y los dobles de IA no son una conexión real a ChatGPT.

SQLite y migraciones se verifican en CI. PostgreSQL sigue siendo una alternativa recomendada para concurrencia multiusuario: instalar un driver, ejecutar la misma batería con una base PostgreSQL real y validar backup/restauración antes de cambiar DATABASE_URL. No se cambia de motor automáticamente ni se declara PostgreSQL validado.

Compose usa production por defecto en esta versión y rechaza una clave de datos vacía. Configurar CALENFLEX_DATA_KEY en .env antes de `docker compose up -d --build`. El inicio por uvicorn sin CALENFLEX_ENV=production conserva el modo de desarrollo para pruebas locales; no usarlo como configuración de un servicio público.
