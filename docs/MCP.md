# MCP, ChatGPT y vinculación de identidad

MCP oficial Python/FastMCP, transporte Streamable HTTP en `/mcp`; metadatos de recurso en `/.well-known/oauth-protected-resource/mcp`. Son herramientas compatibles con descriptores de seguridad de Apps SDK, sin widget embebido. No se leen conversaciones privadas ni se usa la suscripción de ChatGPT como clave API.

## Configuración reproducible del proveedor externo

1. Desplegar HTTPS según DEPLOYMENT.md, con base persistente y clave de cifrado respaldada.
2. Crear un recurso/API en un proveedor OAuth/OIDC real que emita **access tokens JWT RS256**. Audiencia exacta: `https://TU_DOMINIO/mcp`. Habilitar scopes memory:read/write, schedule:read/write/approve, preferences:write y consentimiento por usuario. El emisor debe coincidir exactamente con iss; conservar su barra final si la tiene. El servidor deriva identidad de emisor/sub verificados, nunca de un owner enviado por cliente.
3. Configurar OAUTH_ISSUER, OAUTH_JWKS_URL y OAUTH_RESOURCE_URL. El proveedor debe permitir el registro/configuración de clientes que use ChatGPT y Authorization Code con PKCE. No se implementa un servidor de contraseñas propio. Sin estas variables `/mcp` devuelve 503.
4. Para vincular el móvil, crear **otro cliente público SPA**, sin secret, con Authorization Code + PKCE S256. Redirect URI exacto `https://TU_DOMINIO/connect`; web origin/CORS exacto `https://TU_DOMINIO`. Permitir discovery OIDC y POST al token endpoint desde ese origin. Configurar OIDC_LINK_CLIENT_ID. OIDC_AUDIENCE_PARAMETER=resource usa RFC 8707; si el proveedor requiere audience, configurarlo explícitamente. El cliente pide openid y scopes de lectura/escritura de memoria/agenda. No pide refresh tokens ni guarda access tokens en disco.
5. Reiniciar y comprobar `/ready`, discovery y `/api/v1/connections/link/config` (solo configuración pública). `/connect` realiza login, comprueba state/vencimiento, intercambia con PKCE y verifica la identidad mediante REST. No basta con un ID token u otro token de audiencia distinta.

## Vincular Android y ChatGPT de forma segura

En Android Configuración → Crear código de vinculación. Demuestra posesión del espacio móvil con su token autenticado. El código aleatorio se guarda por hash, vence en cinco minutos y es de un solo uso. Abrir «Vinculación segura», ingresar con el proveedor y pegar el código **en /connect, nunca en una conversación**. Confirmar que ambas conexiones son propias. La operación se niega si la identidad OAuth ya tiene datos o está vinculada a otro espacio; no hace fusiones ni copias destructivas.

El servidor asocia la identidad OAuth verificada al owner móvil existente. Mantiene todos los UUID y datos. El móvil conserva su token administrado; este flujo vincula identidades y no es un login nativo OAuth para reemplazar ese token. Otra identidad OAuth sigue aislada.

Después, en una cuenta de ChatGPT que permita apps/conectores MCP de desarrollo, agregar `https://TU_DOMINIO/mcp` y completar el **consentimiento OAuth de ChatGPT** con la misma identidad vinculada. Autorizar solo permisos necesarios; lectura requiere memory:read y schedule:read, escritura añade scopes concretos. La vinculación no crea el consentimiento de ChatGPT ni da acceso automático a conversaciones.

Verificar desde ambas conexiones: listar objetivos, registrar una sesión revisada con requestId estable, consultar progreso y agenda. Volver a ejecutar el mismo requestId no duplica. Con una identidad diferente los datos deben seguir vacíos/no encontrados. Solo entonces declarar conectada esa cuenta real.

Configuración → Ver conexiones vinculadas permite desvincular con confirmación. La identidad OAuth deja de acceder al espacio móvil sin borrar datos. Revocar además el consentimiento en proveedor/ChatGPT. `POST /api/v1/connections/revoke` revoca la credencial actual, no toda una familia de tokens futuros del proveedor.

## Contratos de herramientas

| Herramientas | Scope principal / comportamiento |
| --- | --- |
| create_study_goal, create_milestone, create_study_session | memory:write; UUID estable |
| register_study_progress | memory:write; requestId y goalId revisados, confirmed=true; tiempo desconocido=null; evaluación opcional con resultados/método/confianza declarada |
| create_task, create_recurring_task | schedule:write; no reorganizan otros compromisos |
| add_deadline | schedule:write; revisión y confirmación |
| get_schedule, get_today_plan, get_pending_tasks | schedule:read; contexto acotado sin notas privadas |
| get_study_goals, get_goal_details, get_learning_progress, get_recommended_next_steps, get_behavior_insights | memory:read; evidencia y estimaciones explícitas |
| propose_reorganization | schedule:write; propuesta persistente, no aplica |
| approve_reorganization | schedule:approve; confirmación y revisión actual |
| create_study_plan | schedule:write + memory:read; recurrencia para objetivo confirmado, sin autoasignar horarios |
| update_user_preferences | preferences:write; revisión y confirmación; fully_autonomous inválido |

19 herramientas. Los argumentos y resultados son tipados, rechazan campos extra y no admiten SQL ni owner arbitrario. Los cambios importantes tienen anotaciones destructivas y autorización del servidor; una anotación no reemplaza confirmación humana. El SDK publica securitySchemes y metadatos de invocación. Herramientas/API comparten LearningWorkflow y AgendaService.

## Estado real y pruebas

Tests HTTP MCP: initialize/tools/list/tools/call, esquemas, registro y deduplicación, scopes, usuario ajeno, JWT firmado, emisor/audiencia/vencimiento, vinculación de un sujeto firmado con el espacio móvil y propuestas/aprobación/reversión. Dos tests Node verifican PKCE/state/claim con proveedor simulado. No se ha configurado un proveedor externo ni conectado una cuenta real de ChatGPT. Pasos manuales pendientes: dominio/TLS, registro de ambos clientes, emisión real JWT y consentimiento en la cuenta de ChatGPT. No enviar secretos al chat para hacerlos.
