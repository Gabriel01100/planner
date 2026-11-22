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
