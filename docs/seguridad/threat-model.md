# Threat model — AKINE backend

- **Estado:** vigente desde AKINE-G-5 (09/10/2026), sobre `main` con contrato 0.80.0.
- **Método:** STRIDE por superficie. Cada control nombra la clase o el archivo que lo implementa,
  para que se pueda verificar contra el código y no contra este documento.
- **Alcance:** el backend `appKine-api`. El frontend y la infraestructura de despliegue (proxy,
  TLS, backups) se mencionan solo donde el backend depende de ellos.
- **Cómo se mantiene:** toda etapa que agregue una superficie (un endpoint público, un archivo
  servido, un canal de salida) suma su fila acá en el mismo PR. Un riesgo residual que se cierra
  se tacha con el PR que lo cerró.

**Leyenda STRIDE:** S suplantación · T manipulación · R repudio · I divulgación · D denegación de
servicio · E elevación de privilegios.

---

## 1. Activos y actores

| Activo | Por qué importa |
|---|---|
| Historia clínica, sesiones, adjuntos clínicos | Dato de salud (Ley 26.529 / 25.326). El activo más sensible |
| Padrón de personas, coberturas, documentos administrativos | Datos personales y de afiliación |
| Deuda, cobros, caja, presentaciones | Dinero; integridad contable |
| `audit_event` | Prueba de quién accedió a qué. Contiene la justificación clínica declarada |
| Credenciales: hash Argon2id, refresh tokens (SHA-256), tokens de activación/reset (SHA-256), `AKINE_JWT_SECRET` | Llave de todo lo anterior |

| Actor | Confianza |
|---|---|
| Anónimo en internet | Ninguna. Solo alcanza login, registro, activación, reset, refresh, `version` y `health` |
| Cuenta autenticada sin contexto (`pre_context`) | Solo puede listar sus contextos y elegir uno |
| Membership de un tenant (los cinco roles de la matriz) | Acotada a su organización y, salvo `ORG_ADMIN`, a su sede |
| `PLATFORM_ADMIN` | Global para el *contrato* del tenant; para sus *datos*, solo con acceso de soporte auditado (matriz §9.7) |
| Operador con acceso a la base o al host | Fuera del modelo de amenazas de la aplicación; ver riesgos residuales |

---

## 2. Superficies

### 2.1 Autenticación, refresh y sesión (`identity`)

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| S | Fuerza bruta / credential stuffing sobre el login | `RateLimitFilter` (30/min por ruta+IP, 5/min el registro, 10/min el alta directa); Argon2id; política de contraseñas con denylist |
| S | Robo de refresh token | Cookie `akine_rt` `httpOnly`+`Secure`+`SameSite=Strict`, `Path=/api/v1/auth`; rotación en cada uso con **detección de reuso que revoca la familia** (ADR-0017); persistido solo como SHA-256 |
| S | CSRF sobre `/auth/refresh` y `/auth/logout` | `SameSite=Strict` + filtro de `Origin`/`Referer` contra la lista CORS + `Content-Type: application/json` |
| I | Enumeración de cuentas | Respuestas uniformes en login, registro, reset y reenvío (ADR-0018). Excepción declarada: el alta directa de colaborador responde 404 si el email no existe, mitigado con su cupo propio y `MEMBERSHIP_ALTA_RECHAZADA` |
| E | Token `pre_context` usado contra endpoints de negocio | `TenantContextFilter` rechaza todo endpoint de negocio sin `scope=context` (403, nunca 401) |
| E | Rol revocado que sigue en un token vigente | El rol del claim no se usa para decidir: cada request re-resuelve la membership vigente contra la base (`PermissionEvaluatorService`) |
| R | Login/refresh sin rastro | `IdentityAuditEvents`: login, logout, reuso de refresh, bloqueo |
| T | JWT forjado | HS256 con secreto ≥ 32 bytes; la aplicación **no arranca** fuera de un perfil de desarrollo sin `AKINE_JWT_SECRET` (`ArranqueSinSecretoTest`) |

### 2.2 Aislamiento multi-tenant y por contexto

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| I/E | Leer o escribir datos de otro tenant cambiando un id en la URL | Toda consulta lleva `organization_id` del **contexto**, no de la ruta (`findByIdInScope`, `UPDATE ... AND organization_id = :org`); fuera de alcance responde **404**, nunca 403 (`AislamientoDeTenantIT`) |
| I/E | Cuenta con memberships en dos organizaciones que usa el `orgId` de la URL para operar sobre la otra | La organización de la ruta tiene que ser la del contexto activo (#70, `AislamientoPorContextoIT`) |
| T | Un `UPDATE`/`DELETE` nativo sin discriminante de tenant | Todas las escrituras nativas de negocio llevan `organization_id` (verificado por barrido en G-5; las únicas sin él son de `cuenta`, que no es de tenant por ADR-0019). `EsquemaMultiTenantIT` exige `organization_id` en toda tabla de negocio y en sus uniques |
| E | Filtro de tenant salteado con una ruta codificada (`%6cogin`) | `RequestPaths` normaliza antes de comparar en los cuatro filtros (07.07) |

### 2.3 Autorización (matriz de permisos)

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| E | Un endpoint autoriza por pertenencia o por el permiso de otra familia | `MatrizDePermisosIT` (G-5) recorre las familias críticas con los cinco roles por HTTP real; `RolePermissionsTest` fija la tabla base contra la matriz |
| E | Un administrador otorga por grant una celda que la matriz marca "No" (p. ej. `hc:write` al `ORG_ADMIN`, `hc:read` completo al `ADMINISTRATIVO`) | **Cerrado en G-5:** `RolePermissions.otorgableComoGrant(rol, permiso)` en el alta del grant (400) y en el evaluador, que ignora un grant que el rol actual no admite (también tras un cambio de rol) |
| E | El `PACIENTE` lee el padrón | Cerrado por DP-22 (#76): `paciente:read` solo para el personal |
| E | `PLATFORM_ADMIN` lee datos de un tenant sin rastro | Lecturas en alcance `SOPORTE`: exigen `support_access` vigente (4 h, motivo obligatorio) y escriben `SUPPORT_ACCESS_USED` en la auditoría del tenant |

### 2.4 Historia clínica y sesiones (`clinical`, `encounter`)

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| I | Personal sin rol clínico lee contenido clínico | `hc:read`/`sesion:register` solo del `PROFESIONAL` por base; el resto por grant donde la matriz lo admite |
| R | Lectura clínica sin motivo ni rastro | `clinical` exige `X-Justificacion-Acceso` y audita **toda** lectura con la justificación en `reason`. `encounter` audita inicio, lectura y cierre de la sesión (**restituido en G-5**: la integración del 29/09 había perdido el cambio de 07.07) |
| I | La auditoría expone la justificación clínica a quien no es clínico | `AuditQueryService` redacta `reason` y `details` de los eventos del `VocabularioClinicoDeAuditoria` sin `auditoria:read-clinica` |
| I | Oráculo de existencia por 403 vs 404 | Orden pertenencia → permiso → dato; una sesión inalcanzable no deja fila de auditoría |
| T | Corrección silenciosa de una sesión cerrada | Solo por enmienda versionada con motivo obligatorio (06.06); versión 1 inmutable |

### 2.5 Adjuntos (administrativos y clínicos)

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| T/E | Subir un ejecutable o HTML disfrazado | El tipo lo deciden los **bytes** (`TipoDeArchivo`, `TipoDeContenidoClinico`), no la extensión ni el `Content-Type`; lista cerrada de tipos |
| I/E | XSS almacenado al servir el archivo desde el mismo origen | Descarga siempre `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff`; nombre saneado |
| D | Agotar disco o memoria | `max-file-size` 15 MB / `max-request-size` 16 MB |
| I | El nombre del archivo revela el diagnóstico en la auditoría | El nombre no va a `audit_event` (07.07) |

### 2.6 Outbox y correo (`notification`)

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| I | Token de activación/reset legible en la base o en backups | El enlace en claro nunca toca la base: `SecureLinkVault` lo pasa en memoria al worker; la tabla guarda una referencia opaca y el token como SHA-256 |
| I | Secretos o PHI en el payload de una notificación | `SanitizedPayload` acepta solo una lista cerrada de claves (`CLAVES_PERMITIDAS`) |
| S | Correo enviado desde el servicio, fuera de la transacción | Solo por outbox transaccional, con reintentos y lease |
| D | Registro masivo que inunda el buzón de una víctima | Cupo de 5/min del registro |

### 2.7 Observabilidad

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| I | Métricas o actuator expuestos | Solo `health` público; `/actuator/prometheus` cerrado salvo `AKINE_METRICS_SCRAPE_TOKEN` (G-4) |
| I | PHI en logs | Log JSON con ids, nunca contenido clínico ni documentos; `X-Request-Id` para correlación |
| I | Contrato publicado en producción | `/v3/api-docs` y Swagger solo con perfil de desarrollo (`SecurityConfig`) |
| I | Stacktraces en respuestas | `GlobalExceptionHandler` y los advices de cada módulo responden `problem+json` sin internals (verificado en cada IT por `assertProblemaLimpio`) |

### 2.8 Imagen Docker y cadena de suministro

| STRIDE | Amenaza | Controles existentes |
|---|---|---|
| E | Escape desde el proceso | Usuario sin privilegios `akine` (UID 10001), JRE en vez de JDK |
| I | Secretos horneados en la imagen | La imagen no lleva configuración de entorno: todo por variable al arrancar |
| T | Dependencia vulnerable | SBOM CycloneDX embebido y publicado por el CI (G-3). **No hay SCA que lo lea** — ver §3 |

---

## 3. Riesgos residuales, priorizados

| # | Prioridad | Riesgo | Por qué sigue abierto | Destino |
|---|---|---|---|---|
| R1 | **Alta** | **No hay SAST, SCA ni DAST en el CI.** El SBOM existe y nadie lo cruza con CVEs; SonarQube está comentado en `ci.yml` | Activarlos es configuración persistente del repo (workflows, Dependabot, secretos de Sonar) y necesita decisión del dueño | Decisión pendiente G-5 (§4) |
| R2 | **Alta** | **`encounter` no exige justificación declarada** (DP-03): sus filas de auditoría salen con `reason` nulo | Sumar `X-Justificacion-Acceso` es un header obligatorio nuevo en operaciones que el frontend ya consume: cambio de contrato | Decisión pendiente G-5 (§4) |
| R3 | Media | **Rate limit por IP del socket detrás de un proxy**: con un reverse proxy delante, todos los clientes comparten la IP del proxy y 30 logins/min bastan para dejar sin login a todo el despliegue | El backend lee `getRemoteAddr()` a propósito (`X-Forwarded-For` lo escribe el cliente). Depende de cómo se despliegue | Al definir el despliegue: `server.forward-headers-strategy` con la IP del proxy de confianza |
| R4 | Media | **Contador de rate limit y `SecureLinkVault` en memoria**: con más de una instancia el límite se multiplica y los enlaces encolados en una instancia no los ve otra | Declarado en `application.yml` y en `SecureLinkVault` | Antes de escalar horizontalmente |
| R5 | Media | **Sin antivirus sobre adjuntos** | "Cuando exista infraestructura" (04.02) | Infraestructura |
| R6 | Media | **Ventana de 10 min** en la que una cuenta bloqueada conserva el access token | Aceptada en ADR-0017 | — |
| R7 | Media | **El vocabulario clínico de la auditoría es una lista de prefijos**: un evento clínico nuevo con un prefijo no declarado se lee sin redactar | Cerrarlo exige marcar el evento en origen (campo en `AuditEntry`) | Etapa propia |
| R8 | Baja | **No hay purga de `refresh_token`** vencidos (deuda de ADR-0017) | Son hashes sin valor una vez vencidos; crecen sin límite | Etapa de operación |
| R9 | Baja | **Alcance `OWN` sin implementar**: el `PACIENTE` no tiene ningún acceso | Por diseño hasta el autoservicio (DP-22) | Post-MVP |
| R10 | Baja | **`caso:create` no se evalúa**: abrir un caso se autoriza con `hc:write` | Desde G-5 el efecto coincide con la fila de la matriz (solo `PROFESIONAL` por base y `CONSULTORIO_ADMIN` por grant); falta decidir si se cablea o se enmienda | Decisión pendiente G-5 (§4) |
| R11 | Baja | **Un grant que el rol ya no admite sigue listándose** en `GET .../grants` aunque el evaluador lo ignore | No concede nada; limpiarlo es una migración de datos | Etapa de operación |
| R12 | Fuera del modelo | Operador con acceso a la base, backups sin cifrar, TLS | Infraestructura | Gate de release 07.09 |

## 4. Decisiones pendientes que este documento eleva

Se listan con opciones en el registro de G-5 (`docs/producto/AKINE_IMPLEMENTATION_PLAN.md`) y en el
PR. En resumen: R1 (qué herramientas de SAST/SCA/DAST y si bloquean el build), R2 (header
obligatorio en `encounter` o justificación implícita por propiedad de la sesión) y R10 (cablear
`caso:create` o enmendar la matriz).
