# Despliegue reproducible

No hay hosting contratado ni un dominio/OAuth real configurados por este repositorio. Este procedimiento prepara un servidor independiente de la computadora. Requiere Docker Compose, DNS hacia el servidor y puertos 80/443 accesibles. Usar una instancia/worker hasta implementar cuota distribuida.

## Configuración

Copiar .env.example como .env, excluido de Git. Generar localmente CALENFLEX_TOKEN aleatorio y CALENFLEX_DATA_KEY Fernet; guardarlos en un gestor de secretos, con backup separado de la base. Ejemplos de generación local (no pegar resultados en chats/Git):

```sh
python -c 'import secrets; print(secrets.token_urlsafe(48))'
python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'
```

Para producción fijar PUBLIC_DOMAIN (dominio real sin esquema), POSTGRES_PASSWORD aleatoria con caracteres URL-safe, CALENFLEX_ENV=production y clave de cifrado. AI_* son opcionales. OAuth/OIDC_LINK_CLIENT_ID se configuran según MCP.md. CORS_ORIGINS queda vacío salvo cliente web externo autorizado. Compose configura ALLOWED_HOSTS con dominio y nombres internos.

```sh
docker compose -f docker-compose.yml -f docker-compose.production.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.production.yml up -d --build
```

En redes con una CA empresarial, el Dockerfile acepta opcionalmente un secreto BuildKit `build_ca` con el certificado de confianza: `docker build --secret id=build_ca,src=/RUTA/ca-certificates.crt -t calenflex ./backend`. No desactivar verificación TLS ni publicar ese archivo como credencial.

PostgreSQL 17 no expone puerto al host. Caddy obtiene/renueva TLS y limita solicitudes a 5 MB; requiere DNS real. Backend usa usuario sin privilegios, filesystem de solo lectura/tmpfs, sin capabilities y sin access log de query. /ready comprueba base; Caddy y Compose lo usan. Migraciones se ejecutan bajo bloqueo al iniciar. Verificar HTTPS /health, /ready, 401 sin token, lectura autorizada y aislamiento entre dos cuentas antes de cargar datos personales.

Android: guardar URL HTTPS y token del propio espacio en Configuración. Verifica identidad y no cambia de owner sobre datos existentes. Completar vinculación y consentimiento ChatGPT por separado en /connect y MCP.md. La IA necesita una clave API de backend, no una suscripción de ChatGPT.

Desarrollo/SQLite: `docker compose up -d --build` publica solo 127.0.0.1:8000 y conserva volumen calenflex-data; también exige clave de datos por ser configuración production. Para desarrollo sin Docker usar uvicorn de README. No exponer HTTP sin proxy. PostgreSQL alternativo requiere transferencia offline explícita, no cambiar DATABASE_URL esperando encontrar los datos de SQLite: DATABASE.md.

## Backup y restauración PostgreSQL

Detener escrituras para una ventana de recuperación controlada. Guardar dump y clave por canales privados separados. Con Compose de producción activo:

```sh
docker compose -f docker-compose.yml -f docker-compose.production.yml exec -T postgres pg_dump -U calenflex -Fc calenflex > calenflex.dump
```

Restaurar **en otra base vacía**, con la misma clave y versión compatible:

```sh
docker compose -f docker-compose.yml -f docker-compose.production.yml exec -T postgres createdb -U calenflex calenflex_restore
docker compose -f docker-compose.yml -f docker-compose.production.yml exec -T postgres pg_restore -U calenflex --no-owner -d calenflex_restore < calenflex.dump
```

Apuntar una instancia de verificación a la base restaurada, comprobar revisiones, objetivos y aislamiento y solo entonces cambiar producción. No usar --clean ni sobrescribir la base activa. Se ejecutó pg_dump/pg_restore contra un contenedor PostgreSQL 17 de prueba y se verificaron memoria/revisión en la copia. Esto no equivale a restauración del hosting del usuario.

SQLite: detener backend antes de copiar volumen/base o usar sqlite3.Connection.backup; conservar journal/WAL coherentes. Copiar solo el archivo mientras hay escrituras puede ser inconsistente. Probar en una instancia separada con su clave original.

## Actualización y rollback

Antes de actualizar: exportar agenda y aprendizaje del móvil, dump de servidor, clave, commit/imagen anterior y configuración. No modificar main: revisar PR, CI y migraciones. Una base con versión futura se rechaza; para rollback de aplicación usar la copia **anterior a la migración** en otra base y la imagen anterior. Conservar escrituras posteriores por separado para reconciliarlas; restaurar una copia no las recupera mágicamente. No rotar la clave cambiando una variable sobre una base cifrada: requiere una migración de rotación probada.

Logs de aplicación: requestId, plantilla de ruta, estado y duración; sin contenido privado. Mantener /ready monitorizado y alertar por errores 5xx, conflictos persistentes o base no disponible. Retención/backups externos y límites monetarios de IA son responsabilidad del operador. Credenciales reales, DNS, consentimiento OAuth/ChatGPT y firma Android estable son pasos manuales pendientes.
