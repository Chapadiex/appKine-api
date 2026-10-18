# Prueba de carga contra los SLO (G-10)

Cómo correr la carga con k6 y la medición de LCP del frontend, qué se midió el 09–10/10/2026 y
qué no cumple. Los SLO son los de [ADR-0016](adr/0016-versiones-tecnicas-y-slo.md):

| SLO | Valor |
|---|---|
| Latencia de la API interactiva | **p95 ≤ 300 ms** y **p99 ≤ 800 ms** (sin exportaciones ni proveedores externos) |
| Carga | **500 usuarios concurrentes** con **tasa de error técnico < 1 %** |
| Frontend | **LCP ≤ 2,5 s en p75** |

> **Resumen.** Las lecturas cumplen holgadas con 500 usuarios (p95 43 ms). **Con escrituras el
> backend no cumple y, con la configuración que se despliega, se cae:** reservas y cobros abren una
> transacción anidada (`REQUIRES_NEW`) mientras retienen su conexión, y con el pool de 10 de Hikari
> **diez pedidos así a la vez traban el pool entero** — 90 % de errores, respuestas de 56 s, y la
> caída arrastra también a las lecturas. Con el pool en 50 no hay errores, pero el cobro serializa
> en el numerador de comprobantes de la sede y nada cumple el p95. El LCP cumple en escritorio y
> **no cumple con el perfil móvil 4G** de Lighthouse (2,8–4,1 s). Ningún hallazgo se corrige en este
> PR: están en §6.

## 1. Qué hay

| Archivo | Qué hace |
|---|---|
| `perf/stack.sh` | Levanta y borra el stack aislado, siembra, corre la carga y junta métricas |
| `perf/k6/sembrado.js` | Siembra el centro **por la API** (nada por SQL) y escribe `perf/salida/sembrado.json` |
| `perf/k6/por-pedido.js` | Un endpoint por vez, en serie: lo usa `perf/stack.sh por-pedido` para contar sentencias y transacciones por pedido (§9) |
| `perf/k6/carga.js` | Los escenarios y los umbrales; escribe `perf/salida/carga-resumen.{md,json}` |
| `perf/k6/lib.js` | Login con contexto, reintento por 429, fechas en la zona de la sede |
| `perf/prometheus-latencia.mjs` | p50/p95/p99 **del lado del servidor** restando dos fotos de `/actuator/prometheus` |
| `perf/seccion-critica.mjs` | Qué corre bajo el lock del numerador y el de `agenda_sede`, desde las fotos de `perf/stack.sh historia` (§10) |
| `perf/lcp/lcp.mjs` | LCP de siete pantallas con Playwright y la Performance API |
| `.github/workflows/carga.yml` | Workflow **manual** (`workflow_dispatch`), solo backend. No corre en push ni en PR |

`perf/salida/` está en `.gitignore`: los números dependen de la máquina y se reportan acá.

## 2. El stack aislado

Todo lleva el prefijo `g10-` y puertos propios (`AKINE_CARGA_PREFIJO` y `AKINE_CARGA_PUERTO_*` los cambian, para correr un segundo stack sin pisar el primero; ver §9). **No toca `akine-mysql`, `akine-mailpit`, el 8080
ni el 4200** de desarrollo.

| Contenedor | Imagen | Puerto del host |
|---|---|---|
| `g10-mysql` | `mysql:8.4`, base `akine_carga`, slow log a 100 ms | `127.0.0.1:3324` |
| `g10-mailpit` | `axllent/mailpit:v1.21.8` | `127.0.0.1:8040` (web) · `127.0.0.1:1040` (SMTP) |
| `g10-api` | `g10-akine-api:carga`, construida con el `Dockerfile` de este repo | `127.0.0.1:8094` |
| `g10-web` | `g10-akine-web:carga`, construida con el `Dockerfile` de `appKine-web` | `127.0.0.1:4294` |

El backend corre con **4 CPU y 2 GB** (`AKINE_CARGA_API_CPUS`, `AKINE_CARGA_API_MEMORIA`) para que
dos corridas sean comparables, con perfil `local` (scrape de métricas abierto, SMTP a Mailpit) pero
**`LOGGING_LEVEL_COM_AKINE=INFO`**: el DEBUG de `application-local.yml` falsearía la latencia.
`AKINE_BOOTSTRAP_ADMIN_EMAIL` está definido porque en una base nueva el catálogo global está vacío
y sólo el admin de plataforma puede dar de alta el servicio del que cuelga la oferta (DP-14).

k6 corre **por su imagen oficial** (`grafana/k6:2.3.0`) dentro de la red `g10-net`: no hace falta
instalarlo.

## 3. Cómo se corre

```bash
perf/stack.sh build            # imágenes (el frontend se busca en ../appKine-web; AKINE_WEB_DIR lo cambia)
perf/stack.sh up               # ~9 min la primera vez: aplica las 72 migraciones
perf/stack.sh sembrar          # ~4 min, casi todo espera del rate limit del alta
perf/stack.sh carga            # ~7 min; acepta -e VAR=valor para k6
perf/stack.sh metricas         # digest de consultas del performance_schema
cd perf/lcp && npm ci && node lcp.mjs   # LCP, con el stack arriba y sin carga
perf/stack.sh down --imagenes  # borra contenedores, volúmenes, red e imágenes
```

Variables de `carga.js` (todas con `-e` después de `carga`):

| Variable | Default | Qué cambia |
|---|---|---|
| `AKINE_CARGA_VUS` | `500` | Usuarios del escenario `operacion` en la meseta alta (la primera meseta es de 100) |
| `AKINE_CARGA_RESERVAS_RPS` | `10` | Reservas por segundo del escenario `reservas` |
| `AKINE_CARGA_SOLO_LECTURA` | — | `1` saca reservas y cobros |
| `AKINE_CARGA_PENSAR_MIN` / `_MAX` | `1` / `5` | Segundos de lectura entre pedidos de un usuario |
| `AKINE_CARGA_SLOTS_CALIENTES` | `40` | Horarios que se disputan a la vez |

Y de `stack.sh`: **`AKINE_CARGA_POOL`** fija el pool de Hikari (vacío = el default de la
aplicación, 10); `perf/stack.sh api` recrea sólo el backend con ese valor, sobre la misma base.

> **Después de una corrida que trabó el backend, reiniciarlo** (`perf/stack.sh api`) antes de la
> siguiente. La corrida 2 de §5 se hizo sin reiniciar y dio p95 de 5,4 s en lecturas que, repetidas
> con el backend limpio, dieron 43 ms.

### El sembrado

`sembrado.js` hace lo mismo que `appKine-web/e2e/support/sembrado.ts`: alta self-service de
administradora, profesional, 3 operadoras y 3 cuentas sólo de login (activadas con el enlace que
llega a Mailpit), memberships, disponibilidad de 00:00 a 23:45 los siete días, una oferta de 30
minutos, **150 personas con perfil de paciente**, caja abierta, la mitad de los horarios de los
próximos 7 días reservados (~120 turnos) y 30 anticipos. El plan `BASICO` admite **5 miembros
activos**: por eso son 3 operadoras y no más.

### Los escenarios

| Escenario | Ejecutor | Qué hace |
|---|---|---|
| `operacion` | `ramping-vus` 0→100 (30 s), 100 (1m30), →500 (1 m), 500 (3 m), →0 | Por iteración, uno al azar: agenda del día 25 %, búsqueda de slots 20 %, recepción del día 20 %, Paciente 360 20 %, cobro (anticipo) 10 %, reporte 5 %; después 1–5 s de lectura |
| `reservas` | `constant-arrival-rate` 10/s, 6 min | Reserva sobre un grupo de **40 horarios** que avanza cada 30 s: muchos pedidos chocan por el mismo slot. **El 409 es la respuesta correcta** y no cuenta como error |
| `login` | `constant-arrival-rate` 1 cada 3 s, desde el segundo 65 | `POST /auth/login` con las cuentas de login, en ronda |

**El login respeta el límite de 30/min/IP**: los escenarios de lectura y escritura reutilizan los
tokens que el `setup()` obtiene una vez por cuenta (4 logins), y sólo el escenario `login` ingresa,
a 20 por minuto. Nada apaga el rate limit. El access token dura 10 minutos y no se refresca: la
corrida entera cabe en 6m30s.

### Los umbrales

```js
http_req_failed:                       rate < 0.01   // 409 de la reserva excluido (expectedStatuses)
errores_tecnicos:                      rate < 0.01   // todo lo que no es el status esperado
'http_req_duration{tipo:interactivo}': p(95) < 300, p(99) < 800
'http_req_duration{endpoint:<cada uno>}': p(95) < 300, p(99) < 800
```

Si un umbral se cruza, k6 termina con código distinto de cero y lo nombra.

## 4. Cómo se mira la causa

- `perf/salida/latencia-servidor.md`: la latencia **dentro** del backend, del histograma
  `http_server_requests_seconds` de G-4. Si coincide con la de k6, la demora es de la aplicación;
  si k6 da mucho más, es cola antes de Tomcat o red. El bucket más alto del histograma es 10 s:
  arriba de eso el p95 del servidor se lee "10000".
- `perf/salida/saturacion.txt`: cada 5 s, conexiones activas y **pendientes** de Hikari, hilos vivos
  de la JVM y CPU del proceso.
- `perf/salida/digest.tsv`: las 40 consultas que más tiempo sumaron, con filas examinadas, uso sin
  índice y tiempo de lock (`performance_schema`, que `stack.sh carga` vacía antes de cada corrida).
- Un volcado de hilos: `docker exec g10-api sh -c 'kill -3 1'` y `docker logs g10-api`.

## 5. Resultados del 09–10/10/2026

Máquina: Windows 11, Docker Desktop 29.2.0, 12 CPU; backend limitado a 4 CPU / 2 GB. Imagen del
backend de `main` `2240971` (PR #76) y del frontend de `main` `502a094` (web #40). k6 2.3.0.

### Corrida 1 — la mezcla completa, con la configuración que se despliega (pool 10)

**No cumple nada. El backend se traba a los 20 s, con menos de 100 usuarios.**

| Endpoint | Pedidos | p50 ms | p95 ms | p99 ms | SLO |
|---|---:|---:|---:|---:|---|
| login | 29 | 55 788 | 56 860 | 57 144 | **NO** |
| agenda del día | 509 | 56 050 | 57 766 | 57 770 | **NO** |
| búsqueda de slots | 402 | 55 947 | 57 766 | 57 767 | **NO** |
| recepción del día | 403 | 56 276 | 57 766 | 57 767 | **NO** |
| Paciente 360 | 441 | 55 947 | 57 439 | 57 768 | **NO** |
| cobro | 212 | 56 336 | 57 766 | 57 767 | **NO** |
| reporte | 104 | 56 368 | 57 612 | 57 767 | **NO** |
| reserva | 1 289 | 55 947 | 57 761 | 57 767 | **NO** |

8,8 req/s · **errores técnicos 89,8 %** · reservas 201: 4 · 409: 154 · **401: 293**.
`saturacion.txt`: a los 20 s, Hikari 10 activas y 135 pendientes; desde ahí 10 activas, ~198
pendientes, 218 hilos y **CPU del 2 al 15 %**: no hay trabajo, hay espera. Causa en §6.1.

### Corrida 2b — sólo lecturas, 500 usuarios, pool 10

**Cumple todo.**

| Endpoint | Pedidos | req/s | p50 ms | p95 ms | p99 ms | SLO |
|---|---:|---:|---:|---:|---:|---|
| login | 105 | 0,3 | 104 | 177 | 244 | sí |
| agenda del día | 10 531 | 28,1 | 31 | 49 | 65 | sí |
| búsqueda de slots | 8 327 | 22,2 | 30 | 46 | 64 | sí |
| recepción del día | 8 256 | 22,0 | 21 | 34 | 46 | sí |
| Paciente 360 | 12 592 | 33,6 | 19 | 32 | 48 | sí |
| reporte | 2 079 | 5,5 | 22 | 38 | 55 | sí |
| **todo lo interactivo** | 41 886 | 111,8 | 24 | 43 | 62 | sí |

0 % de errores. CPU del proceso ~55 % en la meseta de 500, pool con 2–7 conexiones activas y nunca
pendientes. La latencia del servidor coincide con la de k6 (p95 31–48 ms). Con 100 usuarios
(corrida 3) los números son los mismos: p95 total 49 ms, 32 req/s, CPU ~13 %.

> **Corrida 2 (descartada):** la misma carga, corrida inmediatamente después de la 1 sin reiniciar
> el backend, dio p95 5,4 s con 0 errores, 65 req/s y la CPU al 97 %. Repetida con el backend limpio
> es la 2b. Que un backend que se trabó quede degradado minutos después es en sí un hallazgo (§6.6).

### Corrida 4 — la mezcla completa, 500 usuarios, **pool 50** (qué pasaría sin el traba)

Sólo cambia `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=50`. Sirve para ver qué hay detrás del
traba; **no es la configuración desplegada**.

| Endpoint | Pedidos | req/s | p50 ms | p95 ms | p99 ms | SLO |
|---|---:|---:|---:|---:|---:|---|
| login | 104 | 0,3 | 122 | 858 | 1 630 | **NO** |
| agenda del día | 9 762 | 26,0 | 38 | 1 045 | 1 968 | **NO** |
| búsqueda de slots | 7 782 | 20,7 | 38 | 1 050 | 2 010 | **NO** |
| recepción del día | 7 739 | 20,6 | 31 | 1 056 | 1 901 | **NO** |
| Paciente 360 | 7 682 | 20,4 | 24 | 999 | 1 880 | **NO** |
| cobro | 3 840 | 10,2 | 355 | 3 233 | 4 040 | **NO** |
| reporte | 1 896 | 5,0 | 33 | 1 009 | 1 720 | **NO** |
| reserva | 3 601 | 9,6 | 52 | 1 453 | 2 506 | **NO** |
| **todo lo interactivo** | 42 402 | 112,9 | 39 | 1 450 | 2 789 | **NO** |

**0 % de errores** · reservas 201: 365 · 409: 3 236 (`slot-completo` / `recurso-ocupado`, lo
esperado). El p50 sigue bajo: la cola está en ráfagas. `saturacion.txt` muestra el pool de 50
**agotado una y otra vez** (50 activas, hasta 166 pendientes) con la CPU al ~70 %. El servidor ve
lo mismo que k6: p95 del cobro 3,2 s, del resto ~1 s.

**Integridad bajo concurrencia, verificada contra la base al terminar:** 649 turnos, **0 solapados**
para el mismo profesional; 3 928 cobros con comprobantes **1 a 3 928, sin huecos ni repetidos**.
Los locks hacen lo que tienen que hacer; lo que no cumple es cuánto esperan.

### LCP del frontend

Siete pantallas, 5 cargas duras cada una, **caché HTTP apagada** (primera visita), con la cuenta
administradora y el backend sin carga. Perfiles de Lighthouse: *escritorio* (RTT 40 ms, 10 Mbps,
CPU 1×) y *móvil 4G* ("slow 4G": RTT 150 ms, 1,6 Mbps, CPU 4×).

| Pantalla | Escritorio p75 | Móvil 4G p75 | FCP móvil p75 | SLO (≤ 2,5 s) |
|---|---:|---:|---:|---|
| `/auth/ingresar` | 1 252 ms | 2 848 ms | 2 024 ms | escritorio sí · **móvil NO** |
| `/agenda` | 1 864 ms | 3 320 ms | 2 312 ms | escritorio sí · **móvil NO** |
| `/agenda/recepcion` | 1 484 ms | 4 100 ms | 2 788 ms | escritorio sí · **móvil NO** |
| `/pacientes` | 2 168 ms | 3 812 ms | 2 536 ms | escritorio sí · **móvil NO** |
| `/pacientes/{id}` (360) | 2 256 ms | 4 008 ms | 2 416 ms | escritorio sí · **móvil NO** |
| `/caja` | 1 432 ms | 3 320 ms | 2 436 ms | escritorio sí · **móvil NO** |
| `/reportes` | 1 368 ms | 3 540 ms | 2 396 ms | escritorio sí · **móvil NO** |

El elemento LCP es siempre un párrafo de texto (`p.descripcion`, `p.ayuda`), no una imagen. Ojo con
la lectura: ADR-0016 habla de LCP en p75 **de usuarios reales**; esto es laboratorio, y el perfil
móvil es deliberadamente pesimista. Pero incluso el login, que no llama a la API para pintar, pasa
los 2,5 s en móvil.

## 6. Hallazgos

Ninguno se corrige en este PR. Están ordenados por gravedad. **Lo que se corrigió después, y con qué
números, está en §8 y §9.**

### 6.1 Crítico — el pool de conexiones se traba con transacciones anidadas

`TurnoService.reservar` (línea 147) y `CobroService.registrar` (línea 195) llaman a un "iniciador"
con `@Transactional(propagation = REQUIRES_NEW)` —`AgendaSedeIniciador.asegurar` y
`ComprobanteIniciador.asegurar`— **dentro** de su propia transacción. La externa ya tiene una
conexión del pool; la interna pide **otra**. Con el pool de 10 de Hikari (el default: nadie lo
configura), diez reservas o cobros simultáneos toman las diez conexiones y quedan esperando la
segunda que nadie va a devolver. Treinta segundos después Hikari corta, entran las diez siguientes y
se vuelven a trabar.

El volcado de hilos de la corrida 1 lo muestra exacto: **10 hilos con transacción anidada** (9
reservas, 1 cobro) esperando en `HikariPool.getConnection`, y otros 190 detrás —147 de ellos en los
filtros de autenticación, que también necesitan la base—. MySQL, mientras tanto, sin una sola
consulta activa ni un lock esperando.

Es la regla "toda fila-lock se crea en una transacción aparte" (CLAUDE.md, ya pagada cuatro veces
por deadlock de InnoDB) pagada ahora en el pool. Lo tienen también otros iniciadores llamados desde
una transacción: `encounter.NumeradorIniciador`, los numeradores de `clinical`,
`ConvenioLockIniciador`, `AutorizacionLockIniciador`, `CoberturaLockIniciador`, y los auditores en
`REQUIRES_NEW` (`PermissionDenialAuditor` y similares). No se midieron todos; reservas y cobros
alcanzan para tirar el servicio. Caminos posibles para quien lo arregle: asegurar la fila **antes**
de abrir la transacción externa (en una fachada sin `@Transactional`), recordar en memoria qué
filas ya existen, o dimensionar el pool por encima de la concurrencia de escrituras — la corrida 4
muestra que esto último solo no alcanza.

### 6.2 Alto — el cobro serializa en el numerador de la sede

Con el pool en 50, el cobro tiene p50 355 ms y p95 3,2 s. En el digest de la corrida 4, el
`INSERT ... ON DUPLICATE KEY UPDATE` de `comprobante_numerador` (el `asegurar`) promedia **342 ms**
y el `UPDATE ... ultimo_numero + 1` **313 ms**, con **2 660 s de lock sumados en 376 s**: en
promedio siete cobros esperando la misma fila. El `ON DUPLICATE KEY UPDATE` sobre una fila que ya
existe toma lock exclusivo, así que el `asegurar` —que en el 99,9 % de los casos no tiene nada que
crear— espera al cobro en curso **con una conexión retenida afuera**, que es lo que convierte la
serialización en el traba de §6.1. El número de comprobante tiene que ser correlativo por sede, así
que serializar el `+1` es correcto; lo evitable es retenerlo durante el resto de la transacción y
hacer esperar al `asegurar`.

**Contexto:** la carga pone 500 usuarios y 10 cobros por segundo **en una sola sede**, que es el
peor caso para todo lock por sede (numerador, `agenda_sede`, caja). Un centro real cobra mucho
menos por segundo, y 500 usuarios del SaaS se reparten entre muchas sedes. El traba de §6.1 no
depende de eso: depende de cuántas escrituras simultáneas hay en **todo** el backend.

### 6.3 Medio — un fallo de base en la autenticación responde 401

Durante el traba, 427 respuestas fueron **401** con `uri="UNKNOWN"`: `JwtAuthenticationFilter`
revalida el rol de plataforma contra la base en cada pedido (ADR-0020), la conexión no llega, la
excepción sale del filtro y el pedido termina como no autenticado. Para el frontend un 401 significa
"tu sesión no vale": el interceptor borra el token y **una caída de la base desloguea a todos los
usuarios**. Debería ser un 503 o un 500. La causa exacta del 401 (despacho a `/error` sin
autenticar, probablemente) no se confirmó.

### 6.4 Medio — N+1 en la agenda del día

`GET /consultorios/{c}/agenda?fecha=` hace **una consulta a `oferta_servicio_consultorio` por cada
turno**: un día de 24 turnos son 49 sentencias, 24 de ellas iguales. La recepción del mismo día hace
30, porque cachea la oferta por id dentro de la llamada (`RecepcionService`, línea 176);
`AgendaUnificadaService` no. Hoy cumple (p95 49 ms con 24 turnos), pero crece lineal con el día: una
sede con varios profesionales y cien turnos son cien consultas. En la corrida 2b esa consulta fue la
más ejecutada de todo el backend (209 112 veces en 41 899 pedidos).

### 6.5 Bajo — tres transacciones y lecturas repetidas por pedido autenticado

Cada pedido abre **tres transacciones** (rol de plataforma en `JwtAuthenticationFilter`, membership
en `TenantContextFilter`, y la del controlador): en la corrida 2b, 125 528 `COMMIT` para 41 899
pedidos. Cada una cuesta `SET autocommit`, `SET SESSION TRANSACTION READ ONLY/WRITE` y `COMMIT`:
cuatro idas y vueltas que no leen nada. Además `membership` y `platform_role` se consultan **dos
veces** por pedido. Revalidar contra la base en cada pedido es decisión (ADR-0020, T-7); hacerlo dos
veces y en tres transacciones no. Hoy no rompe el SLO, pero multiplica por tres la presión sobre el
pool que §6.1 agota.

### 6.6 Bajo — el backend no se recupera solo después de trabarse

La corrida 2 (§5) arrancó un minuto después de que la 1 terminara, con el pool ya libre, y dio CPU
al 97 % con 65 req/s y 218 hilos vivos, contra 55 % con 112 req/s en la misma carga después de un
reinicio. La hipótesis más probable es la cola de pedidos que k6 ya había abandonado y Tomcat seguía
procesando, pero no se confirmó.

### 6.7 Bajo — el primer arranque tarda más que el `start-period` del healthcheck

Contra una base vacía, Flyway aplicó las 72 migraciones en **6 min 25 s** y la aplicación arrancó a
los **8 min 23 s**. El `HEALTHCHECK` del `Dockerfile` tiene `start-period` de 120 s (AGENT.md decía
≈2,5 min, medido el 06/10): el contenedor queda `unhealthy` durante el primer arranque, y un
orquestador que reinicie por healthcheck lo mataría a mitad de las migraciones.

### 6.8 Frontend — LCP móvil

Con el perfil móvil 4G ninguna pantalla baja de 2,5 s, y el FCP solo ya está en 2,0–2,8 s. El bundle
no es grande (el `main` pesa 4 KB comprimido, el chunk mayor 172 KB sin comprimir): lo que pesa es la
cadena de idas y vueltas antes de pintar —`index.html`, el `main`, los chunks perezosos y, en las
pantallas con sesión, el refresh y la lectura de datos— a 150 ms de RTT cada una, más el arranque de
Angular con CPU 4× más lenta. Es una hipótesis: no se perfiló.

## 7. Qué no cubre

- **Un solo backend y una sola sede.** No mide varias instancias ni la distribución real de un SaaS
  multi-tenant, que reparte los locks por sede.
- **No cubre** cierre de sesión clínica, historia clínica, presentaciones, series ni exportaciones
  (estas últimas las excluye ADR-0016).
- **El LCP es de laboratorio**, sin carga en el backend y en una sola máquina. ADR-0016 habla del p75
  de usuarios reales: eso necesita medición en campo (RUM), que no existe.
- **No hay job de CI obligatorio**: `carga.yml` es manual y sus números en un runner de GitHub no
  son comparables con los de esta máquina.

## 8. Lo que se corrigió el 11/10/2026 (rama `akine-fix-pool-y-carga`)

Mismo stack aislado, mismos scripts y la misma máquina, con el prefijo `pool-` y puertos propios
(MySQL 3327, Mailpit 8042/1042, backend 8097). Backend con 4 CPU y 2 GB y **el pool por defecto
(10)**. Cada corrida: base nueva, sembrado, reinicio del backend y la mezcla completa a 500
usuarios. "Antes" es `main` `2240971` medido el mismo día, que reproduce la corrida 1 de §5.

### Estado de cada hallazgo

| § | Hallazgo | Estado |
|---|---|---|
| 6.1 | El pool se traba con transacciones anidadas | **Corregido.** Las once filas-lock se aseguran antes de abrir la transacción de negocio (`FilaLockPrevia`); ningún pedido retiene dos conexiones. `PoolDeConexionesIT` lo reproduce con pool 2: sobre `main` las 16 escrituras mueren por `CannotCreateTransactionException`, con el arreglo ninguna |
| 6.2 | El cobro serializa en el numerador de la sede | **Mejorado, no eliminado.** El `asegurar` ya no toca la fila existente: el `INSERT ... ON DUPLICATE KEY UPDATE` desapareció del digest. El `+1` sigue serializando, y debe: ver abajo |
| 6.3 | Un fallo de base en la autenticación responde 401 | **Corregido.** 503 `service-unavailable` (500 si no es la base), nunca 401. 401 en la carga: 120 → **0** |
| 6.4 | N+1 en la agenda del día | **Corregido.** `OfertaDirectory.findTodas`: una consulta por día, no una por turno |
| 6.5 | Tres transacciones y lecturas repetidas por pedido | **Sin cambios en esta rama.** Corregido después: ver §9 |
| 6.6 | El backend no se recupera solo | **Desaparece con 6.1**: ya no se traba |
| 6.7 | El primer arranque supera el `start-period` | **Corregido.** `start-period` de 600 s |

### La mezcla completa, 500 usuarios, pool 10 — antes y después

| Endpoint | Antes p95 / p99 ms | Después p95 / p99 ms | Después, segunda corrida p95 / p99 ms |
|---|---:|---:|---:|
| login | 55 964 / 55 969 | 3 230 / 13 848 | 3 099 / 3 430 |
| agenda del día | 55 958 / 55 965 | 3 071 / 11 797 | 3 126 / 4 554 |
| búsqueda de slots | 55 964 / 55 966 | 3 387 / 13 752 | 3 142 / 4 567 |
| recepción del día | 55 964 / 55 965 | 3 255 / 15 192 | 3 045 / 4 481 |
| Paciente 360 | 55 964 / 55 966 | 3 276 / 10 398 | 3 105 / 4 430 |
| cobro | 55 964 / 55 967 | 4 988 / 17 841 | 5 110 / 7 001 |
| reporte | 55 960 / 55 966 | 3 230 / 10 925 | 3 189 / 4 573 |
| reserva | 55 965 / 55 967 | 5 988 / 16 973 | 4 850 / 6 691 |
| **todo lo interactivo** | 55 964 / 55 966 (p50 91) | 3 566 / 15 331 (p50 61) | 3 345 / 5 280 (p50 66) |
| pedidos · req/s | 7 812 · 20,5 | 37 492 · 100,2 | 39 514 · 105,9 |
| errores técnicos | **30,34 %** | **0,00 %** | 0,02 % |
| 401 | 120 | **0** | 0 |
| reservas 201 / 409 | 190 / 1 132 | 469 / 3 052 | 469 / 3 096 |

La segunda corrida es la misma con cuatro volcados de hilos durante la meseta (`kill -3`), que son los
que se citan abajo.

**El traba desapareció; el SLO a 500 usuarios todavía no se cumple.** Antes, el pool quedaba con 10
conexiones activas y ~199 pendientes desde los 20 s hasta el final, con la CPU del 2 al 15 %: nadie
trabajaba. Después, el pool se satura **en ráfagas** de 10 a 40 s (23 de las 61 muestras con
pendientes) y se vacía solo, con la CPU entre 70 y 100 % de los 4 núcleos: el backend trabaja, y lo
que queda es capacidad. La mediana de todo lo interactivo es 61–66 ms.

Integridad verificada contra la base al terminar: **3 438 cobros con comprobantes 1 a 3 438, sin
huecos ni repetidos**, y 594 turnos.

### 6.2 — el numerador del comprobante, medido

| | Antes (G-10, pool 50) | Después (pool 10) |
|---|---|---|
| `INSERT ... ON DUPLICATE KEY UPDATE` de `comprobante_numerador` | 342 ms promedio | **no aparece**: la fila existe y el iniciador no la toca |
| `UPDATE ... ultimo_numero + 1` | 313 ms promedio | **106 ms** y 70 ms en las dos corridas |
| lock sumado | 2 660 s en 376 s | 409 s en 374 s y 264 s en 372 s |

Que el `+1` serialice los cobros de una sede es el requisito: el comprobante es correlativo y sin
huecos. Su sección crítica ya es corta —del `UPDATE` al `COMMIT` van dos `INSERT`, la suma a la caja
si es efectivo y el commit—, y el número no se puede pedir más tarde sin cambiar el esquema: la
columna es `NOT NULL`, única por sede y no actualizable. Los volcados de hilos durante la meseta
muestran por qué todavía duele: **en el pico, cuatro de las diez conexiones son cobros esperando ese
lock**, y con ellas tomadas las lecturas hacen cola.

Se probó y **se descartó** una compuerta en memoria por sede que hiciera esperar a los cobros antes
de pedir la conexión: las lecturas mejoraron (p95 ~1,7 s) pero el cobro empeoró a p95 **14,9 s**,
porque el que pasaba la compuerta tenía que esperar una conexión con la compuerta tomada y la cola
se mudaba a la JVM. El escenario es además el peor caso para cualquier lock por sede —diez cobros por
segundo en un solo centro—; un centro real cobra mucho menos, y 500 usuarios de un SaaS se reparten
entre sedes.

### Lo que queda abierto

- **6.5 sin cambios** (corregido después, en §9). Las dos lecturas por pedido de `membership` (filtro y evaluación de permisos)
  y las tres transacciones siguen. Cachearlas por pedido toca la evaluación de permisos de
  `organization` (ADR-0020, T-7) y no se hizo a ciegas. En el digest de la corrida: `SET autocommit`
  252 307 veces y `SET SESSION TRANSACTION READ ONLY/WRITE` 117 000 cada una, para 37 492 pedidos.
- ~~**Quedan `REQUIRES_NEW` llamados desde adentro de una transacción**~~ (auditores de soporte y
  de permiso denegado, escritura aparte de la historia clínica y de los adjuntos,
  `ObligacionOcupadaConsulta`): **corregidos en `akine-pool-anidamientos`**, ver
  [abajo](#los-anidamientos-que-no-eran-filas-lock-rama-akine-pool-anidamientos).
- ~~`ImputacionDePrepago` en el `afterCommit` del cierre~~ **corregido en esta misma rama**: corría
  con la conexión del cierre todavía tomada (`afterCommit` y `afterCompletion` van antes de la limpieza
  de la transacción). Ahora se registra en `DespuesDeLaTransaccion` y corre cuando la plantilla de
  negocio ya devolvió la conexión. `PrepagoDeRecepcionIT` cierra doce sesiones con prepago a la vez
  con el pool de diez: antes se trababa, ahora las doce imputan.
- El 503 de los filtros quedó para fallas de **conexión** con la base; las de persistencia que delatan
  un bug —dos memberships vigentes, por ejemplo— responden 500.
- **El primer arranque, remedido:** 190 a 244 s desde que el contenedor se crea, con la aplicación
  lista a los 144–176 s y Flyway en 1 min 22 s a 2 min 12 s. Los 8 min 23 s de G-10 no se
  repitieron, pero el `start-period` cubre ese caso.

`perf/stack.sh` tiene dos arreglos que salieron de correrlo: espera a que MySQL acepte conexiones por
TCP antes de arrancar el backend (el ping por socket contesta antes, y el backend moría con
"Connection refused"), y en Git Bash usa `pwd -W` para que `docker build` reciba una ruta de Windows.

### Los anidamientos que no eran filas-lock (rama `akine-pool-anidamientos`)

`FilaLockPrevia` resolvió las filas-lock moviéndolas antes de la transacción de negocio. Quedaban
otros diez `REQUIRES_NEW` llamados desde adentro de una transacción abierta, cada uno con dos
conexiones por pedido mientras duraba. Ninguno corre en un pedido normal, así que la carga de §5 no
los ejercita, pero muchos 403, muchas primeras aperturas de historia clínica o muchas subidas de
adjuntos a la vez traban el pool igual. `grep REQUIRES_NEW src/main` después del arreglo deja sólo
transacciones que **no anidan**: `OutboxDispatchService` (el despachador corre sin transacción),
`DirectMembershipService` (sin transacción de afuera, documentado), `ImputacionDePrepago` (corre en
`DespuesDeLaTransaccion`) y la plantilla de `AuditoriaAparteImpl` (abajo).

| Camino | Qué protegía el `REQUIRES_NEW` | Cómo quedó |
|---|---|---|
| Auditores de rechazo y de soporte: `PermissionDenialAuditor`, `PlanLimitRejectionAuditor`, `SupportAccessReadAuditor` y los `*SupportAccessAuditor` de `clinical`, `person`, `resource` y `reporting` | Que la fila sobreviva al rollback del 403/404 y que se escriba aunque la lectura sea `readOnly` | `platform.spi.audit.AuditoriaAparte`: la entrada se arma en el momento y se escribe en una transacción propia **cuando la del pedido ya terminó y devolvió su conexión**. Para eso el transaction manager de la aplicación (`TransaccionesConfig`) avisa a `AlTerminarLaTransaccion` al empezar cada transacción física y al terminar de limpiarla. `afterCompletion` no servía: corre con la conexión todavía tomada |
| `AdjuntoClinicoService.contenido` → `marcarNoDisponible` | Que la marca `NO_DISPONIBLE` sobreviva al 409 | La misma: `AlTerminarLaTransaccion.ejecutar` |
| `HistoriaClinicaService.asegurar` → `HistoriaClinicaEscrituraAparte` | Que el choque contra el unique no deje la transacción `rollbackOnly`, y releer a la ganadora fuera de la foto de `REPEATABLE READ` | **Ya no hay escritura aparte.** `INSERT IGNORE` en la transacción de negocio —el choque queda en advertencia, no en excepción— y relectura con `FOR SHARE`, que ve lo último commiteado. De yapa, la historia y su `HISTORIA_CLINICA_OPENED` se confirman juntas |
| `AdjuntoClinicoService.subir` y `person.AdjuntoService.subir` → `*EscrituraAparte` | Lo mismo para `uk_adjunto_*_contenido_vigente` | La transacción de afuera desapareció: autorizar y buscar por checksum, el INSERT (su transacción), el binario (sin conexión tomada) y la auditoría van en cuatro pasos de una conexión por vez. La relectura del perdedor es una transacción nueva, así que ya no necesita `READ_COMMITTED`. Las escrituras aparte fallan si alguien las llama con una transacción abierta |
| `PresentacionService.crear` / `agregarItem` → `ObligacionOcupadaConsulta` | Leer el lote ganador con la sesión JPA del perdedor rota | El perdedor lanza una excepción interna, su transacción revierte y devuelve la conexión, y recién ahí se lee el lote para el 409 `obligacion-ya-presentada` |

**Qué garantía tiene cada auditoría diferida** (corregido tras la revisión del PR #97, que
encontró que la primera versión dejaba todo al mejor esfuerzo):

- **Uso de acceso de soporte — obligatoria, regla 01.01.** En una transacción de escritura (toda
  mutación por soporte: alta, edición o baja de persona, abrir o cerrar un caso, subir un
  adjunto…) se escribe **adentro**, por `AuditTrail`: si falla, la mutación revierte; si la mutación
  revierte por otra causa, el intento se escribe aparte al terminar. En una lectura `readOnly` se
  escribe aparte y, si la transacción commiteó y la escritura falla, la falla sale del `commit`
  —corre en `cleanupAfterCompletion`, antes de que el resultado deje el proxy— y **la lectura no se
  entrega**. Si la lectura revirtió, su propia excepción no se tapa y la falla de la auditoría se
  loguea.
- **Rechazos (`PERMISSION_DENIED`, `PLAN_LIMIT_REJECTED`) y la marca `NO_DISPONIBLE` — al mejor
  esfuerzo.** El pedido ya falla por su lado; si además falla la escritura, se loguea en ERROR y no
  se convierte el 403 en un 500. Con el `REQUIRES_NEW` de antes una falla de esa escritura sí daba
  500: es la única garantía que se relajó, y a propósito.

La apertura concurrente de la **misma** historia ahora espera a la primera (la fila nueva queda
bloqueada hasta su commit); si la primera revierte con dos o más esperando —realista:
`SesionService.iniciar` revierte si el caso pedido no es asignable a la historia recién creada—,
InnoDB elige una víctima. Esa `CannotAcquireLockException` (y cualquier
`PessimisticLockingFailureException`: deadlock o lock que no llega a tiempo) responde **409
`concurrent-modification`** desde `GlobalExceptionHandler`, como la versión vieja (DP-21), y no
500: el reintento encuentra la historia abierta o la abre.

El `afterCommit` alternativo de `PrepagoAlCierreDeSesion` (cierres fuera de la plantilla de
negocio) también corre ahora la imputación por `AlTerminarLaTransaccion`, sin la conexión del
cierre tomada.

**Verificado con pool 2.** `PoolDeConexionesIT` suma cinco escenarios de ocho pedidos simultáneos
(primera apertura de historia, dos por paciente; subida de adjuntos clínicos y administrativos, dos
por contenido; descarga de adjuntos sin binario; 403 por permiso) y `PresentacionPoolDeConexionesIT`
arma a mano la carrera de presentaciones con un tercer hilo que retiene la conexión liberada. Sobre
`main` `5b4ef93`, **los seis fallan**: en los cinco primeros los ocho pedidos mueren por
`CannotCreateTransactionException`, y en presentaciones muere el perdedor. Con el arreglo pasan los
seis, con la idempotencia intacta (una historia y un evento por paciente, una fila por contenido) y
los ocho `PERMISSION_DENIED` y `NO_DISPONIBLE` escritos aunque los pedidos fallen.

La espera por conexión de esos ITs pasó de 3 a 10 s: sobre `main`, la segunda ronda de cobros del IT
de #91 falló una vez con 3 s sin ningún anidamiento —ocho cobros serializados en el numerador con
dos conexiones pueden esperar más que eso en esta máquina—.

## 9. CPU y latencia por pedido (12–13/10/2026, rama `akine-perf-por-request`)

Después de §8 el pool ya no se trababa, pero a 500 usuarios la CPU seguía entre 70 y 100 % de los
4 núcleos y el p95 en 3,3–3,6 s. Esta rama ataca **lo que cuesta cada pedido**, sin tocar la
semántica de permisos: ADR-0020 y T-7 siguen igual —membership y rol de plataforma se revalidan
contra la base **en cada pedido**—, lo que desaparece es leerlos varias veces **dentro** del mismo.

Mismo método que §8: stack aislado con prefijo `perf2-` y puertos propios (MySQL 3329, Mailpit
8046/1046, backend 8099), backend con 4 CPU, 2 GB y el pool por defecto (10). Cada corrida: base
nueva, sembrado, conteo por pedido, reinicio del backend y la mezcla completa a 500 usuarios.

### Cómo se midió

- **Perfil.** JFR (`settings=profile`) durante la carga completa sobre `main` `5b4ef93`, con
  `AKINE_CARGA_JAVA_OPTS="-XX:StartFlightRecording=settings=profile,dumponexit=true,filename=/app/var/carga.jfr"`
  y `docker stop` para volcarlo. Se miraron las muestras de la meseta de 500 usuarios. **Ojo:** JFR
  corta las pilas en 64 cuadros por defecto y las de Hibernate son más largas; para atribuir por
  controller hace falta `-XX:FlightRecorderOptions=stackdepth=256`.
- **Sentencias y transacciones por pedido.** `perf/stack.sh por-pedido`: un usuario, cien pedidos
  en serie por endpoint, y la diferencia de los contadores de MySQL (`Questions`, `Com_*`) y de las
  conexiones pedidas a Hikari, dividida por cien. Escribe `perf/salida/por-pedido.md`.

### Dónde se iba la CPU (JFR, meseta de 500 usuarios, `main`)

| Qué | % de las muestras de CPU |
|---|---:|
| Connector/J **analizando el texto de cada sentencia** en cada `prepareStatement` (`QueryInfo`), por no tener cache de sentencias | ~10 (13 todo `prepareStatement`) |
| Lecturas de autorización: membership en el filtro de contexto (12), evaluación de permisos (8,5) y rol de plataforma (3); las dos primeras leen **las mismas filas** | ~24 |
| `ReservaProbeSobreTurnos`: la búsqueda de slots cargaba **las entidades `Turno` completas** de cada día de la ventana sólo para contar cuántas hay por hora | 8,5 |
| Observaciones de Micrometer de **cada filtro** de Spring Security (timer y span por filtro, ~15 por pedido) | ~5 |
| Connector/J abriendo un **span de OpenTelemetry por sentencia** (PREFERRED por defecto con la API en el classpath), que nadie exporta | 4,8 |
| Abrir y cerrar transacciones (`doBegin`, `commit`, limpieza) | ~7 |
| Argon2 del login (1 cada 3 s) | 3,5 |

Además, el digest de MySQL mostró una consulta **sin índice** en la recepción y la agenda: el
prepago de cada turno se lee con `cobro WHERE organization_id = ? AND turno_id IN (...)`, y ningún
índice empieza por `(organization_id, turno_id)`. Recorría todos los cobros de la organización en
cada pedido —1 400 filas por consulta, 5,9 millones en una corrida— y crece con cada cobro.

### Qué se cambió

| Cambio | Dónde | Semántica |
|---|---|---|
| **Memoria del pedido**: membership, rol de plataforma y grants se leen una vez por pedido; el filtro lee y la evaluación de permisos del mismo pedido reutiliza la lectura | `platform.spi.pedido.MemoriaDelPedido`, `AlcanceDelPedidoFilter`, `organization.application.LecturasDeAcceso` | Sin cambios. No cruza pedidos (el filtro la abre y la descarta en un `finally`), no existe fuera de uno (jobs, outbox, tests) y **no se usa dentro de una transacción de escritura**: ahí se lee la base y la memoria se descarta, así que la mutación que evalúa después de tomar el bloqueo del tenant sigue viendo la base de ese momento. Se memorizan filas, no decisiones: vigencia y alcance se evalúan con el instante de cada llamada |
| El rol de plataforma del filtro JWT se lee **sin transacción propia** (`SUPPORTS`): una sola sentencia en autocommit | `PlatformRoleRepository`, `OrganizationPlatformRoleDirectory` | Sin cambios: una sentencia no necesita transacción para ser consistente. Dentro de una transacción se une a ella como antes |
| Cache de sentencias de Connector/J (`cachePrepStmts`, 500 sentencias, 4 096 caracteres) | `application.yml`, `spring.datasource.hikari.data-source-properties` | Cachea el análisis del texto, por conexión; no cachea resultados |
| `openTelemetry=DISABLED` en Connector/J | ídem | Se pierden los spans por sentencia SQL, que no se exportaban (muestreo 0,1 y sin collector por defecto). El span del pedido sigue |
| `management.observations.enable.spring.security: false` | `application.yml` | Se pierden los timers y spans por filtro de seguridad; `http.server.requests` y la traza del pedido siguen |
| La sonda de reservas lee una **proyección** (inicio, profesional, espacio) en vez del `Turno` entero | `OcupacionDeTurno`, `TurnoRepositoryPort.findOcupacionDeLaOfertaEnVentana` | Sin cambios: misma ventana y mismo filtro |
| Índice `ix_cobro_turno (organization_id, turno_id)` | `V94` | Sólo agrega un índice |

**El límite de la memoria, dicho entero.** La lectura fresca cubre sólo lo que se evalúa
**dentro** de una transacción de escritura, y al commitear cualquier escritura la memoria del pedido
se descarta (`TransaccionesConfig`). Las mutaciones que desde #91 evalúan el permiso **antes** de
abrir su transacción de negocio —p. ej. `CobroService.registrar`, que llama a `exigirGestion` antes
del `execute`— deciden con lo que leyó el filtro de contexto del mismo pedido: una ventana de
milisegundos, apenas mayor que antes, que nunca cruza al pedido siguiente.

**Lo que no se hizo:** unir las dos transacciones de los filtros (la de membership sigue: son cuatro
lecturas que conviene ver en una sola foto) ni `readOnlyPropagatesToServer=false` (quitaría los
`SET SESSION TRANSACTION READ ONLY/WRITE`, pero también que MySQL rechace una escritura en una
transacción de lectura, que es lo que destapó el defecto #14 del 11/10). Los
`REQUIRES_NEW` que quedaban los resolvió #97 (sección anterior); esta rama se midió sobre él.

### Sentencias y transacciones por pedido

Medido el 13/10 sobre `main` `36eee22` (ya con #97) y sobre esta rama mergeada con él, cada uno
con base recién sembrada:

| Endpoint | Sentencias antes → después | SELECT | SET | COMMIT |
|---|---:|---:|---:|---:|
| agenda del día | 26 → **19** | 11 → 9 | 12 → 8 | 3 → 2 |
| búsqueda de slots | 39 → **32** | 24 → 22 | 12 → 8 | 3 → 2 |
| recepción del día | 30 → **23** | 15 → 13 | 12 → 8 | 3 → 2 |
| Paciente 360 | 31 → **22** | 16 → 12 | 12 → 8 | 3 → 2 |
| cobro | 44 → **37** | 14 → 12 | 18 → 14 | 5 → 4 |
| reporte | 38 → **33** | 25 → 25 | 10 → 6 | 3 → 2 |
| reserva | 54 → **47** | 27 → 25 | 20 → 16 | 5 → 4 |
| login | 8,2 → 8,6 | 1,1 → 1,1 | 2 → 2,2 | 1 → 1,1 |

Cada pedido autenticado hace **una transacción menos** (tres → dos en las lecturas) y deja de
releer membership y rol. Las conexiones que pide al pool siguen siendo tres: la lectura del rol
sin transacción también toma una, sólo que la devuelve después de una sentencia. El índice `V94`
no cambia cuántas sentencias hay: cambia cuántas filas recorre la del prepago (la recepción del
día cuenta más o menos SELECT según cuántos turnos le queden al día cuando se mide).

### La mezcla completa, 500 usuarios, pool 10 — antes y después

**La máquina no estuvo quieta durante casi toda la medición.** Docker Desktop corría a la vez los
stacks y los tests de otros agentes (hasta 36 contenedores), y la misma imagen dio 68 req/s en una
corrida y 91 en otra. Por eso las comparaciones van **de a pares corridos uno detrás del otro**. El
par que se reporta es el último: el 13/10, sobre `main` con #97, con la máquina casi quieta (7 a 9
contenedores en total).

**Par 3 (13/10, 10:50–11:20), `main` `36eee22` contra esta rama mergeada con él:**

| Endpoint | Antes p50 / p95 / p99 ms | Después p50 / p95 / p99 ms |
|---|---:|---:|
| login | 891 / 2 206 / 2 578 | **149 / 387 / 694** |
| agenda del día | 1 457 / 3 649 / 4 502 | **28 / 440 / 948** |
| búsqueda de slots | 1 529 / 3 689 / 4 524 | **49 / 474 / 909** |
| recepción del día | 1 491 / 3 644 / 4 584 | **38 / 470 / 899** |
| Paciente 360 | 1 459 / 3 620 / 4 549 | **31 / 438 / 966** |
| cobro | 2 740 / 5 766 / 7 158 | **140 / 971 / 1 750** |
| reporte | 1 470 / 3 617 / 4 482 | **49 / 433 / 846** |
| reserva | 2 067 / 5 335 / 6 502 | **64 / 911 / 2 007** |
| **todo lo interactivo** | 1 585 / 4 233 / 5 660 | **44 / 535 / 1 183** |
| pedidos · req/s | 30 447 · 78,3 | 44 320 · **114,4** (+46 %) |
| CPU del proceso, media | 75 % | **62 %** |
| muestras de 5 s con pedidos esperando conexión | 41 de 60 | **20 de 68** |
| pedidos ≤ 300 ms (servidor), lecturas | 20–22 % | **91–92 %** |
| pedidos ≤ 800 ms (servidor), lecturas | 34–36 % | **98,4–98,6 %** |
| errores técnicos | 0,00 % | 0,00 % |
| reservas 201 / 409 | 480 / 3 099 | 478 / 3 123 |

Después de este par la rama se mergeó otra vez con `main` (entraron hasta #109, entre ellos #107
con cambios en reserva y agenda) y la migración pasó de `V87` a `V94` porque `main` tomó `V87`.
Esa versión final **no se volvió a medir con k6**: pasaron los unitarios tocados y los ITs
`AgendaDescuentaReservasIT`, `EsquemaMultiTenantIT`, `PoolDeConexionesIT` y `OpenApiContractIT`.

Integridad verificada contra la base al terminar: comprobantes **1 a 2 722** (antes) y
**1 a 4 187** (después), sin huecos ni repetidos, y **0 turnos solapados** del mismo profesional.

Los dos pares anteriores, con la máquina cargada, van en la misma dirección y dicen menos:

- **Par 2** (12/10, sobre `5b4ef93`, sin #97): 90,7 → 99,0 req/s, p50 786 → 66 ms, CPU media
  58 → 37 %, pedidos ≤ 300 ms del 28 al 70 %; el p95 no bajó (3,8 → 4,3 s en k6), porque el
  pool seguía en ráfagas de 10 activas y 190 pendientes con la CPU al 30–50 %.
- **Par 1** (11/10, sin `V94`): 68,2 → 75,3 req/s, CPU media de ~80 % a ~40 %, p95 ~7 s en los
  dos.
- Una corrida con JFR de la versión nueva se descarta: coincidió con la máquina más cargada del día
  (37 req/s, 1,14 % de timeouts en k6) y no dice nada del cambio.

### Lectura

**El p95 de todo lo interactivo bajó de 4,2 s a 0,54 s y el p99 de 5,7 s a 1,2 s, con 46 % más
pedidos.** Todavía no cumple el SLO de ADR-0016 (300 / 800 ms), pero las lecturas quedan a un
paso: nueve de cada diez dentro de los 300 ms y el 98,5 % dentro de los 800.

Lo que queda tiene dos nombres, y ninguno es CPU:

1. **El numerador del comprobante (§6.2).** El cobro es el endpoint más lento (p95 971 ms) y el
   `UPDATE comprobante_numerador ... + 1` sigue siendo la sentencia con más espera de lock del
   digest: 339 s sumados en 387 s de corrida, 80 ms de promedio, contra 510 s y 191 ms en el
   "antes" del mismo par. El escenario pone 10 cobros por segundo **en una sola sede**, y el comprobante
   correlativo sin huecos serializa por sede por definición. Mientras esas conexiones esperan, las
   lecturas hacen cola por una libre: casi un tercio de las muestras todavía tiene pedidos esperando
   conexión. Bajarlo exige cambiar cuándo se pide el número o cuánto se retiene la conexión
   esperando el lock, y eso toca la regla de numeración de 06.05 y la columna `NOT NULL` única del
   comprobante: no es un cambio acotado y no se hizo. Con la carga repartida entre sedes ese lock
   no se comparte.
2. **El lock de `agenda_sede` de las reservas** (89 s de espera sumados, 286 s antes), por la misma
   razón: 10 reservas por segundo sobre 40 horarios de una sola sede.

La CPU media del 62 % con 114 req/s es ~22 ms de CPU por pedido sobre 4 núcleos, contra ~38 ms en
el "antes" del mismo par.

## 10. Lo que corre bajo los locks de escritura (14/10/2026, rama `akine-perf-escrituras`)

Después de §9 las lecturas quedaron cerca del SLO y las escrituras no: el cobro serializa en el
numerador de comprobantes de la sede y la reserva en `agenda_sede`. Esta rama **achica lo que corre
mientras esos dos locks están tomados, sin cambiar ninguna regla**: la numeración sigue correlativa
y sin huecos por sede (06.05), la agenda sigue serializada por sede (05.02), y no cambian el orden
de los locks, las aislaciones, el contrato ni el esquema.

Mismo método que §8 y §9: stack aislado con prefijo `perf3-` y puertos propios (MySQL 3331,
Mailpit 8048/1048, backend 8101), backend con 4 CPU, 2 GB y el pool por defecto (10). Cada corrida:
base nueva, sembrado, reinicio del backend y la mezcla completa a 500 usuarios, más el digest, la
integridad contra la base y —nuevo— **una foto de las últimas sentencias de cada conexión** a los
250 s (`perf/stack.sh historia`), que `perf/seccion-critica.mjs` convierte en lo que pasa entre que
una transacción obtiene el lock y lo suelta en el `COMMIT` o el `ROLLBACK`.

### `main` medido (`c827c72`, con #91, #97 y #112)

Tres corridas de la mezcla completa, cada una con base nueva, intercaladas con las de la rama
(abajo). Una cuarta, la primera del día, dio p95 2 896 ms y p99 11 954 ms con dos stacks de otros
agentes y un `docker build` corriendo a la vez: se descarta.

| Endpoint | Corrida 1 p95 / p99 ms | Corrida 2 p95 / p99 ms | Corrida 3 p95 / p99 ms |
|---|---:|---:|---:|
| login | 375 / 789 | 550 / 1 360 | 268 / 566 |
| agenda del día | 331 / 1 223 | 937 / 2 319 | 235 / 893 |
| búsqueda de slots | 339 / 1 564 | 988 / 2 288 | 274 / 1 026 |
| recepción del día | 341 / 1 496 | 944 / 2 367 | 219 / 942 |
| Paciente 360 | 331 / 1 339 | 995 / 2 361 | 267 / 940 |
| cobro | 965 / 2 605 | 2 056 / 4 136 | 526 / 1 636 |
| reporte | 331 / 1 137 | 1 093 / 2 500 | 309 / 921 |
| reserva | 1 233 / 6 269 | 1 450 / 3 632 | 326 / 1 400 |
| **todo lo interactivo** (p50) | **464 / 1 760** (31) | **1 160 / 2 579** (33) | **288 / 1 062** (28) |
| pedidos · req/s | 44 343 · 115,3 | 43 209 · 112,7 | 44 808 · 116,8 |
| errores técnicos · 401 | 0,00 % · 0 | 0,00 % · 0 | 0,00 % · 0 |
| reservas 201 / 409 | 478 / 3 089 | 480 / 3 112 | 480 / 3 121 |
| comprobantes (30 son del sembrado) | 1 a 4 111, sin huecos ni repetidos | 1 a 3 968, ídem | 1 a 4 184, ídem |
| turnos vivos · solapados | 635 · 0 | 637 · 0 | 636 · 0 |

La misma imagen da p95 entre 288 y 1 160 ms según la corrida: **la variación entre corridas es más grande
que cualquier cambio de esta rama**, y por eso abajo se compara lo que pasa bajo el lock, que se
mide por transacción y casi no varía.

### Qué corría bajo cada lock en `main`

**Cobro, 8,6 sentencias** desde el `UPDATE` del numerador hasta el `COMMIT`:

```
SELECT ultimo_numero · INSERT cobro · INSERT cobro_medio · SELECT jornada_caja ·
UPDATE cobro_medio SET cobro_id · UPDATE jornada_caja (efectivo) · INSERT movimiento_caja ·
INSERT audit_event · COMMIT
```

Tres no necesitan el lock: el `SELECT` que relee el número que el `UPDATE` acaba de asignar, la
lectura de la jornada de caja —es de la misma foto `REPEATABLE READ`— y el `UPDATE cobro_medio`, que
Hibernate emite por cada hijo de un `@OneToMany` unidireccional aunque el `INSERT` ya llevó el
`cobro_id`. **Y el `COMMIT` es el 80–90 % del tiempo retenido**: 22 a 52 ms de promedio según la
corrida (el `fsync` del redo log y del binlog en el volumen de Docker Desktop), contra 4–5 ms de
todo lo demás junto. El `COMMIT` no puede salir: el lock se suelta ahí, y que un cobro que revierte
devuelva su número es lo que hace que no haya huecos.

**Reserva, 14–15 sentencias** desde el `SELECT ... FOR UPDATE` de `agenda_sede` hasta el cierre
(casi siempre `ROLLBACK`: el escenario hace chocar 10 reservas por segundo sobre 40 horarios, y 87 %
termina en 409): la sede con `FOR SHARE`, la idempotencia, la oferta, la persona y su perfil de
paciente, las habilitaciones, el calendario de la sede, los feriados, la membership, los bloques y
las excepciones de disponibilidad, el horario general, y recién ahí el cupo y el solapamiento.
**Diez de esas sentencias no leen turnos** y nadie que las cambie toma ese lock.

### Qué se cambió

| Cambio | Dónde | Por qué no cambia ninguna regla |
|---|---|---|
| El numerador asigna y devuelve el número **en una sentencia**: `SET ultimo_numero = LAST_INSERT_ID(ultimo_numero + 1)`, y el número llega en la respuesta del `UPDATE` (`getGeneratedKeys`) | `ComprobanteNumeradorPort.siguiente`, `ComprobanteNumeradorSiguienteImpl` | El mismo `UPDATE ... + 1` sobre la misma fila y con el mismo lock hasta el commit; si el cobro revierte, el incremento también. Sale el `SELECT` de relectura |
| La jornada de caja se lee **antes** del numerador | `CajaDeCobro.destino` | Es una lectura de la foto que ya fijó la primera lectura de la transacción (`REPEATABLE READ`): devuelve lo mismo antes o después. El rechazo de efectivo sin caja (`caja-no-abierta`) se sigue lanzando en el mismo punto, y lo que decide si la jornada sigue abierta es la suma condicional de siempre |
| `updatable = false` en el `@JoinColumn` de `Cobro.medios` y `Cobro.imputaciones` | `Cobro` | Hibernate deja de reescribir el `cobro_id` que el `INSERT` ya puso: una sentencia menos por medio y por imputación. Un hijo nunca cambia de cobro. Es lo que ya hace `SesionVersion` |
| Los logs de "Cobro registrado" y "Turno reservado" se escriben **después del commit** | `CobroService`, `TurnoService` | Escribían en la consola con el lock tomado. El reintento idempotente sigue sin loguear |
| **Paso 0 de la reserva**: oferta y su vigencia, persona y perfil de paciente, cobertura elegida, habilitación y franja del profesional (u horario de la sede) y el contacto del aviso se leen **antes** de tomar `agenda_sede`, en la misma transacción `READ_COMMITTED` | `TurnoService.preparar`, `RevalidadorDeSlot.prevalidar`, `AvisosDeTurno.contactoDe` | `agenda_sede` lo toman reservas, reprogramaciones, series y clases: **nadie que cambie una oferta, una persona, una cobertura, una habilitación o una disponibilidad lo toma**, así que ninguna de esas lecturas estaba protegida hasta el commit; en `READ_COMMITTED` ven lo último commiteado al leer, bajo el lock o antes. Sus rechazos **se guardan y se lanzan bajo el lock, después de la idempotencia y en el orden de siempre**: un reintento de un turno ya creado sigue devolviéndolo aunque la persona haya dejado de ser paciente. Bajo el lock quedan la sede con `FOR SHARE`, la idempotencia, el horario pasado, el cupo, el solapamiento con turnos y con clases, el espacio y las escrituras |

`reprogramar` y las series siguen usando `RevalidadorDeSlot.revalidar` entero, bajo el lock: no
están en la carga y no se tocaron.

**Después de la revisión del PR #117**, dos ajustes que no cambian lo medido arriba: el paso 0 de la
reserva guarda sólo los rechazos de negocio (oferta no agendable, persona inaccesible o sin perfil,
cobertura, slot no disponible) y deja que un error técnico se propague en el acto; y un medio que no
es efectivo relee la jornada con `FOR UPDATE` antes de asentarse en ella
(`JornadaCajaRepositoryPort.abiertaBajoLock`): antes, un cobro con tarjeta o transferencia que perdía
la carrera contra el cierre quedaba asentado en una jornada ya cerrada. Es una sentencia más bajo el
numerador para esos cobros, y `CierreDeCajaConcurrenteIT` suma la carrera (falla sin el arreglo).

**Índices de lo que corre bajo los locks.** Después del cambio, todo lo que queda adentro va por
`PRIMARY KEY`, un `UNIQUE` o un índice que empieza por las columnas de igualdad: `agenda_sede` y
`comprobante_numerador` por `(organization_id, consultorio_id)`, la idempotencia por
`(organization_id, idempotency_key)`, el cupo por `ix_turno_oferta_inicio`, el solapamiento por
`ix_turno_profesional_intervalo` y el de clases por `ix_clase_profesional_intervalo`. Ninguna
sentencia del digest con `sin_indice` corre bajo un lock. Dos observaciones que no se tocaron: el
solapamiento filtra `deleted_at IS NULL` y el índice lleva `deleted_key`, así que el rango usa sólo
`(organization_id, profesional_membership_id)` y el resto se evalúa sobre el índice (0,7 ms acá,
crece con la historia del profesional); y las consultas que a veces van sin índice son las sumas del
reporte (`cobro`, `cobro_medio` y `movimiento_caja`, hasta 2 millones de filas examinadas en una
corrida) y la del prepago por turno de la recepción, todas fuera de los locks.

**Tests.** `CobroConcurrenteIT`, `TurnoConcurrenteIT` y `PoolDeConexionesIT` en verde (14 de 14,
`./mvnw verify -Dit.test=...`), y los 3 811 unitarios. `CobroServiceTest` verifica el orden nuevo
(jornada antes del numerador) y `TurnoServiceTest` suma tres casos: lo que no lee turnos va antes del
lock, el rechazo previo no tapa el reintento idempotente y sin reintento se lanza bajo el lock.

### Antes y después, de a pares

Cuatro pares, cada corrida con base nueva y una detrás de la otra; el par 3 en orden inverso
(rama primero) para no confundir el efecto con una deriva de la máquina. El par 4 corrió con
**durabilidad relajada en MySQL** (`innodb_flush_log_at_trx_commit = 2`, `sync_binlog = 0`) para
aislar el `fsync` del commit: **es una medición, no una propuesta** (ver la opción 5).

Lo que pasa bajo el lock, de `perf/seccion-critica.mjs` (promedios en ms; la "foto" son tres vuelcos de
`events_statements_history_long` a los 250, 270 y 290 s, con 90–120 cobros y 60–80 reservas por
corrida):

| | Par 1 antes → después | Par 2 | Par 3 | Par 4 (durabilidad relajada) |
|---|---|---|---|---|
| **cobro**: sentencias bajo el lock, con el commit | 8,5 → **5,7** | 8,7 → **5,5** | 8,6 → **5,6** | 8,7 → **5,7** |
| cobro: bajo el lock sin el commit | 4,7 → 5,8 | 4,3 → **2,4** | 4,1 → **2,2** | 6,5 → **2,3** |
| cobro: el commit | 34,4 → 51,7 | 41,0 → 25,0 | 22,0 → 26,2 | 1,3 → 0,6 |
| cobro: espera del lock, en la foto | 54 → 173 | 153 → **18** | 49 → 76 | 6,8 → **0,9** |
| cobro: espera del lock, toda la corrida (digest) | 103 → 130 | 110 → **42** | 32 → 32 | 5 → 3 |
| **reserva**: sentencias bajo el lock, con el cierre | 14,0 → **4,4** | 14,9 → **5,4** | 15,1 → **4,9** | 14,4 → **4,8** |
| reserva: bajo el lock sin el cierre | 7,9 → **3,6** | 7,1 → **1,8** | 5,5 → **2,2** | 8,0 → **1,6** |
| reserva: espera del lock, en la foto | 2,0 → 6,2 | 17,6 → **0,4** | 1,6 → 1,3 | 1,8 → **0,3** |
| reserva: espera del lock, toda la corrida (digest) | 54,0 → **10,8** | 16,7 → fuera de las 40 primeras | 2,5 → 12,4 | 31,9 → **8,7** |

Y la carga entera, de k6:

| | Par 1 antes → después | Par 2 | Par 3 | Par 4 (durabilidad relajada) |
|---|---|---|---|---|
| todo lo interactivo p95 / p99 ms | 464 / 1 760 → 2 702 / 4 311 | 1 160 / 2 579 → **430 / 1 297** | 288 / 1 062 → 526 / 1 558 | 959 / 2 316 → 1 064 / 2 269 |
| cobro p95 / p99 ms | 965 / 2 605 → 4 160 / 5 607 | 2 056 / 4 136 → **753 / 1 674** | 526 / 1 636 → 825 / 1 967 | 1 286 / 2 605 → 1 344 / 2 697 |
| reserva p95 / p99 ms | 1 233 / 6 269 → 3 804 / 5 220 | 1 450 / 3 632 → **477 / 1 478** | 326 / 1 400 → 1 361 / 2 669 | 2 186 / 3 453 → 1 832 / 3 213 |
| req/s | 115,3 → 98,3 | 112,7 → 116,2 | 116,8 → 116,9 | 114,9 → 114,1 |
| CPU media del backend · muestras con pedidos esperando conexión | 51 % · 13/65 → 65 % · 30/64 | 51 % · 15/61 → 49 % · 11/68 | 49 % · 10/68 → 56 % · 8/67 | 56 % · 15/67 → 56 % · 14/67 |
| errores técnicos | 0,00 % en las ocho | | | |
| comprobantes · turnos solapados | sin huecos ni repetidos · 0 en las ocho | | | |

### Lectura

**Las secciones críticas se achicaron y el SLO no se alcanzó.**

1. **Bajo el lock de agenda, de 14–15 sentencias a 4–5**, y el tiempo sin el cierre de 5,5–8,0 ms a
   1,6–3,6 ms. Bajo el numerador, de 8,6 sentencias a 5,6, y en tres de los cuatro pares el tiempo
   sin el commit bajó a la mitad (de 4,1–6,5 ms a 2,2–2,4). Es lo que se puede sacar sin tocar la
   regla: lo que queda adentro necesita el número, el id del cobro o leer turnos.
2. **En k6 no se distingue.** Con durabilidad normal, la rama ganó un par (1 160 → 430 ms de p95) y
   perdió dos (464 → 2 702 y 288 → 526); la misma imagen va de 288 a 1 160 ms entre corridas. El par
   1 "después" es el más lento de todos, con la CPU más alta y el commit más lento (51,7 ms), sin
   ninguna diferencia en lo que corre bajo los locks: es la máquina. Ninguno de los números de k6 de
   esta sección sirve para afirmar que la rama mejora o empeora la latencia de punta a punta.
3. **El cobro espera al commit, no a las sentencias.** Con durabilidad normal, la espera del
   numerador sigue al tiempo del commit (22–52 ms de promedio, 50–100 ms de p95), y el commit no puede
   salir del lock sin aceptar huecos. Por eso sacar tres sentencias de 1 ms no mueve la espera.
4. **Con el commit barato, los locks dejan de importar y el p95 sigue arriba.** En el par 4 la espera
   del numerador cae de 6,8 a 0,9 ms y la de la agenda de 1,8 a 0,3 ms, y aun así el p95 es de ~1 s: en la meseta
   el backend llega al 100 % de sus 4 CPU en ráfagas de 10 a 15 s con hasta 187 pedidos esperando
   conexión, mientras la mediana sigue en 28 ms. **El límite siguiente es la capacidad de CPU frente
   a 500 usuarios que llegan en ráfagas**, no un lock.

### Lo que requiere decisión del usuario

No se implementó ninguna. Las tres primeras tocan la regla de numeración de 06.05; la cuarta, la
serialización de la agenda.

1. **Numeración por punto de venta** (un talonario por caja u operador, cada uno correlativo y sin
   huecos). *Costo:* una etapa — tabla de puntos de venta, el numerador pasa a
   `(organization_id, consultorio_id, punto_de_venta)`, el unique del comprobante suma la columna,
   `puntoDeVenta` en el contrato del cobro (aditivo) y en la pantalla, y asignar punto a cada
   operadora o caja. *Beneficio:* la contención se divide por la cantidad de puntos (con las 3
   operadoras de la carga, un tercio). *Riesgo:* el comprobante deja de ser correlativo por sede, y
   los reportes y el arqueo que lo asuman hay que revisarlos. Es el modelo de la facturación
   argentina (punto de venta de AFIP), así que encaja con una facturación electrónica futura.
2. **Numerar después del commit, en lote.** El cobro se confirma sin número y un paso serializado
   numera en orden los pendientes de la sede, varios por commit (un `fsync` para muchos). *Costo:*
   migración (`comprobante_numero` nullable y actualizable, mismo unique), contrato (el `POST`
   devuelve el cobro sin número o espera a que se lo asignen), y reimpresión y anulación tienen que
   tolerar un cobro todavía sin número. *Riesgo:* el orden de los números pasa a ser el de los
   commits; si el paso de numeración falla queda un cobro sin número que alguien tiene que reintentar
   (job o bandeja). Sin huecos se mantiene.
3. **Numerar al cerrar la caja** (número provisorio durante la jornada, definitivo al cierre).
   *Costo:* alto — el paciente se va sin número definitivo, "comprobante recuperable" cambia de
   significado y el cierre de caja pasa a ser un proceso pesado que puede fallar a mitad. *Riesgo:*
   el más alto de los tres. Sólo si un requisito fiscal lo pide.
4. **Lock por profesional en vez de por sede** para la agenda. *Costo:* filas-lock por profesional y
   por espacio (los boxes se comparten entre profesionales), un orden fijo profesional → espacio
   para no deadlockear, el cupo grupal por `(oferta, inicio)`, y migrar las clases (`AgendaDeSede`) y
   las series al mismo esquema. *Riesgo:* es la parte del sistema donde la concurrencia decide la
   correctitud (05.02); un orden de locks mal elegido da deadlocks o turnos solapados sin error.
   *Beneficio en esta carga: ninguno* —el escenario tiene un solo profesional y la espera de la
   agenda ya quedó en 0,3–12 ms—. En una sede con varios profesionales, sí.
5. **No es código: medir en el entorno de destino.** El commit durable cuesta acá 22–52 ms porque es
   Docker Desktop sobre Windows; en un MySQL sobre Linux con disco SSD o administrado debería ser
   mucho menos (no se midió: la corrida con durabilidad relajada, 0,6–1,3 ms, es sólo la cota
   inferior). Antes de elegir entre 1 y 3 conviene repetir esta carga en una máquina parecida a la de
   producción. **Relajar la durabilidad no es una opción para la numeración**: una caída del sistema
   operativo puede perder cobros ya confirmados y con el comprobante en la mano del paciente, que es
   un hueco.
6. **Capacidad.** Con los locks fuera del camino (par 4) el p95 lo marca la CPU en ráfagas: más CPU
   o más instancias del backend (hay que revisar lo que vive en memoria por instancia, como el rate
   limit), o bajar más el costo por pedido (~22 ms de CPU, §9). Un pool más grande no ayuda sin CPU:
   ver la corrida 4 de §5.

**Lo que conviene primero:** la opción 5 —medir dónde va a correr— y, si el commit sigue arriba de
~10 ms, la 1, que es la más barata, no toca la garantía de huecos y tiene precedente fiscal.

**Decidido (DP-32, 17/10/2026):** primero la opción 5, como paso del deploy; si en el entorno de
destino el commit sigue arriba de ~10 ms, se implementa la opción 1.

## 11. Los cuatro sobrantes de §6 y del #82, verificados (15/10/2026, rama `akine-g10-sobrantes`)

Cuatro problemas seguían anotados como abiertos en `CLAUDE.md` §7. Se revisó cada uno contra
`origin/main` `a707f3d` antes de tocar código.

| Problema | Estado en `main` | Evidencia |
|---|---|---|
| Un fallo de base en la autenticación responde 401 (§6.3) | **Ya estaba resuelto** por #91 (`f6e6494`) | `FallaEnFiltro`: `JwtAuthenticationFilter` y `TenantContextFilter` responden 503 `service-unavailable` ante una falla de conexión y 500 ante cualquier otra, nunca 401; `JwtAuthenticationFilterTest` y `TenantContextFilterTest` lo cubren. 401 en la carga de §8: 120 → 0 |
| `start-period` del healthcheck más corto que el primer arranque (§6.7) | **Ya estaba resuelto** por #91 (`fb52718`) | `Dockerfile`: `--start-period=600s`. `compose.yaml` no tiene servicio del backend; `perf/stack.sh` y `ops/simulacro-restore.sh` esperan hasta 15 min la readiness |
| `AuthSessionController` guarda `getRemoteAddr()` como IP de la sesión (#82) | **Ya estaba resuelto** por #93 (`5fd504b`) | El helper compartido existe: `platform.spi.security.IpDeCliente`, implementado por `ResolutorDeIpDeCliente`, el mismo bean que usa `RateLimitFilter`, configurado por `AKINE_TRUSTED_PROXIES`. Ningún otro sitio de `src/main` lee `getRemoteAddr()` |
| N+1 en la agenda del día (§6.4) | **La agenda unificada ya estaba resuelta** por #91 (`c76aa41`); **la recepción del día, no** | Ver abajo |

### La recepción del día: dos consultas por oferta distinta

#91 corrigió `AgendaUnificadaService` con `OfertaDirectory.findTodas`. `RecepcionService.componer`
—que arma `TurnoDelDia` para la recepción del día y para `GET .../turnos/{t}`— seguía cacheando la
oferta por id dentro de la llamada, y `PrepagoDeRecepcion` hacía lo mismo con el precio: **dos
sentencias por oferta distinta del día**. Con una oferta por profesional crece con los
profesionales de la sede. En la carga no se veía porque el sembrado tiene una sola oferta.

`AgendaDelDiaSentenciasIT` lo mide: envuelve el `DataSource` y cuenta los
`prepareStatement`/`createStatement` **del hilo del test** (el outbox usa la misma base), el mismo
día con una oferta y con seis, un turno por oferta.

| Lectura | Antes: 1 oferta → 6 ofertas | Después: 1 oferta → 6 ofertas |
|---|---:|---:|
| recepción del día (`RecepcionService.delDia`) | 10 → **20** | **10 → 10** |
| agenda unificada (`AgendaUnificadaService.delDia`) | 6 → 6 | 6 → 6 |

El arreglo: `RecepcionService` pide las ofertas del lote con `findTodas` y `PrepagoDeRecepcion`
pide los precios con `OfertaDirectory.preciosDe`, nuevo, en una consulta para todas. Sin cambio de
contrato ni migración.

Medido en el CI de #134 (la suite completa: 3.949 unitarias y 853 de integración, 0 fallos). Con una
sola oferta la cuenta no cambia —una consulta por lista reemplaza a una por oferta—; con seis, la
recepción hace las mismas diez sentencias que con una.

## 12. F9: carreras, N+1 y carga de clases y pases (16/10/2026, rama `akine-09.03-concurrencia-f9`)

### Carreras con IT

Las que ya tenían IT antes de 09.03: última vacante con espera (`UltimaVacanteConcurrenteIT`),
promoción doble (`PromocionDesdeEsperaConcurrenteIT`), invariante del cupo
(`CupoOcupadoInvarianteIT`), último crédito (`ConsumoDePaseConcurrenteIT`), cancelación contra
asistencias y ajuste contra consumo (`DevolucionYAjusteConcurrenteIT`) y dos renovaciones a la vez
(`AbonoRenovacionYAvisosIT`). Las que faltaban:

| Carrera | IT | Resultado |
|---|---|---|
| Inscribir contra cancelar la clase | `InscripcionContraCancelacionIT` | **Defecto, corregido** (abajo) |
| Venta de pase con la misma `idempotencyKey` | `CarrerasDePasesIT` | **Defecto, corregido** (abajo) |
| Consumo contra anulación del pase | `CarrerasDePasesIT` | Correcto: o se anula sin uso y la asistencia queda `SIN_PASE_ELEGIBLE`, o se consume y la anulación es 409 |
| Ajuste contra anulación | `CarrerasDePasesIT` | Correcto: gana uno, el otro 409 por versión |
| Vencimiento contra consumo de una clase del último día | `CarrerasDePasesIT` | Correcto: un solo `VENCIMIENTO` y el ledger en cero |
| Renovación automática contra anulación manual | `CarrerasDePasesIT` | Correcto: o se anula y no se renueva, o se renueva y la anulación es 409; una deuda por período |

`CarreraDirigida` (test) abre la ventana a propósito en vez de sortearla: una conexión propia
retiene la fila con `FOR UPDATE` y cada tarea se larga recién cuando su sentencia aparece esperando
en `information_schema.PROCESSLIST`. Los dos defectos se reprodujeron **siempre** antes del
arreglo; las ráfagas de `enParalelo` quedan además como control del invariante.

1. **Inscribir mientras se cancela la clase dejaba a la persona en la cola de una clase
   `CANCELADA`.** `inscribir` valida la clase sin lock y después pide el lugar con el `UPDATE`
   condicional, que filtra `estado = 'PROGRAMADA'`. Si la cancelación commitea en el medio, el
   `UPDATE` da cero filas y el servicio lo leía como "no hay lugar": con espera aceptada creaba una
   `LISTA_ESPERA` viva que nadie avisa ni resuelve; sin espera respondía `clase-completa`. Ahora la
   rama de cero filas relee la clase **bloqueada** y responde `clase-transicion-no-permitida`.
2. **Dos ventas simultáneas con la misma `Idempotency-Key`: la segunda respondía 409 `conflict`**
   en vez de la venta existente. Las dos pasaban la búsqueda de la clave antes de que la primera
   commiteara; el lock del producto las ponía en fila, pero la segunda no volvía a mirar y chocaba
   con `uk_pase_idempotency`. Ahora la clave se vuelve a buscar con el producto ya bloqueado
   (`READ_COMMITTED`). Sin cambio de contrato.

### N+1

`ListadosDeActividadSentenciasIT` cuenta las sentencias del hilo del test (`ContadorDeSentencias`,
que pasó a `testsupport` y comparte con `AgendaDelDiaSentenciasIT`) con una fila y con seis:

| Listado | 1 fila | 6 filas |
|---|---:|---:|
| clases de la sede (`ClaseService.listar`) | 5 | 5 |
| participantes (`InscripcionService.participantes`) | 7 | 7 |
| detalle operativo (`AsistenciaService.detalleOperativo`) | 13 | 13 |
| pases de la persona (`VentaDePaseService.deLaPersona`) | 9 | 9 |
| ledger paginado (`VentaDePaseService.movimientos`) | 5 | 5 |
| productos de la sede (`ProductoService.listar`) | 4 | 4 |

**Ninguno crece**: `ProyeccionDeClase` y `LecturaDePases` ya resolvían en lote. No hubo nada que
corregir. La asistencia en lote, que no es un listado sino N escrituras independientes por diseño
(RF-M13-008), cuesta **18 sentencias por participante** (1 participante = 21, 6 = 111). De esas 18,
tres se repiten igual en cada ítem —la sede, su zona horaria y el esquema de cobro de la oferta—;
el resto es trabajo propio del ítem (lock de la clase, asistencia, evento, auditoría y el consumo
del crédito con su movimiento).

### Carga

`perf/k6/sembrado-actividad.js` agrega al centro de G-10 una oferta grupal `POR_PACK`, un pack, un
pase por persona (150), 60 clases futuras y 20 clases iniciadas con 25 inscriptos cada una.
`perf/k6/actividad.js` corre, durante 5 minutos: **inscribir** a 10/s (persona y clase al azar,
espera aceptada; el 409 de inscripción duplicada es esperado), **asistencia en lote** a 2/s (los 25
inscriptos de una clase, en ronda, presente o ausente al azar: casi todo ítem es una corrección que
consume o devuelve crédito), **venta de pase** a 5/s del mismo producto, y **200 usuarios** que
leen la grilla de la semana, los participantes y los pases de una persona. Cualquier escenario de
tasa fija se apaga con su variable en 0 (p. ej. `-e AKINE_ACTIVIDAD_LOTES_RPS=0`).

```bash
perf/stack.sh up && perf/stack.sh sembrar && perf/stack.sh sembrar-actividad
perf/stack.sh actividad            # acepta -e VAR=valor, como `carga`
```

**Medido el 16/10/2026**, stack aislado con prefijo `f1003` (MySQL 3334, backend 8095), imagen del
jar de esta rama, backend con 4 CPU / 2 GB y pool de 10, misma máquina que §5. Latencias de k6 en
ms; la del servidor (`prometheus-latencia.mjs`) coincide en lo grueso.

| Endpoint | Corrida 1: todo · p50 / p95 / p99 | Corrida 2: sin el lote · p50 / p95 / p99 |
|---|---|---|
| inscribir (8,3–8,5/s) | 127 / 3.210 / 9.405 | 59 / 358 / 1.057 |
| asistencia en lote, 25 ítems (1,6/s) | 1.515 / 19.044 / 24.968 | — |
| vender pase (4,2/s) | 150 / 3.665 / 9.656 | 77 / 723 / 2.028 |
| clases de la semana | 25 / 2.181 / 5.891 | 21 / 92 / 861 |
| participantes | 27 / 2.286 / 6.474 | 23 / 97 / 774 |
| pases de la persona | 29 / 2.232 / 5.360 | 26 / 104 / 755 |
| **todo lo interactivo** | 34 / 2.683 / 8.337 | 25 / 189 / 944 |

Errores técnicos **0 %** en las dos corridas, y **0 %** de ítems de lote con error: ninguna carrera
de las de arriba apareció bajo carga. **El SLO no se cumple con el lote adentro**, y el motivo es
uno solo: cada lote de 25 son 25 transacciones de ~18 sentencias cada una, que retienen conexiones
del pool de 10 durante 1,5 s de mediana; a 2 lotes por segundo el pool se satura y arrastra a
todo lo demás (las lecturas pasan de p95 ~95 ms a ~2,2 s). Sin el lote, las lecturas cumplen
holgadas y quedan fuera dos escrituras:

- **vender pase**, p95 723 ms: toda venta toma el producto con `FOR UPDATE` (08.08, para que dos
  períodos de un abono no se superpongan y para leer una baja recién commiteada). Con un solo
  producto, todas las ventas de la sede se serializan. Para un pack el lock no protege ninguna
  superposición; aflojarlo es una decisión del usuario (**U-48**): bloquear sólo los abonos, o
  aceptar la cola. *(17/10/2026: decidido e implementado, ver "U-48" al final de esta sección.)*
- **inscribir**, p95 358 ms: las altas de una misma clase se serializan en la fila de la clase
  (el `UPDATE` condicional del cupo, que es la correctitud y no se toca). Con 60 clases y 10 altas
  por segundo, rozando el SLO.

**Lo que queda abierto, sin corregir en esta rama:** el costo del lote. Las opciones, de menor a
mayor cambio: sacar del ítem lo que se repite igual en todos (sede, zona y esquema de cobro, 3 de
18 sentencias); agrupar los ítems de a varios por transacción manteniendo el resultado parcial por
ítem; o limitar la concurrencia de lotes por sede. Ninguna cambia el contrato; las dos últimas
cambian la semántica de "transacción independiente" de RF-M13-008 y son decisión del usuario
(**U-49**).
Los números de una sola máquina de desarrollo no son los del entorno de destino (§7).

### 09.05: el costo del lote sin cambiar ninguna regla (17/10/2026, rama `akine-asistencia-lote-costo`)

Lo que U-49 llama opción (a), hecho: **misma transacción por ítem, mismos locks en el mismo orden
(clase en compartido → inscripción → asistencia → pase), mismas auditorías**, mismo resultado
observable y sin cambio de contrato ni migración.

**Por qué eran 18.** `ContadorDeSentencias.capturar` (nuevo) devuelve el texto de cada sentencia.
Un ítem de alta en una oferta `POR_PACK` hacía: sede, rol de plataforma y memberships (contexto y
permiso, **dentro** de la transacción del ítem), lock de la clase, inscripción, asistencia previa,
UPDATE de la inscripción, INSERT de asistencia, evento y auditoría, neto del ledger de esa
asistencia, oferta (esquema de cobro), **sede otra vez** (zona horaria), lock de pases elegibles,
UPDATE del pase, relectura del saldo, movimiento y auditoría. Cinco de esas lecturas eran
idénticas en todos los ítems y dos eran innecesarias.

| Qué se sacó del ítem | Por qué no cambia una regla |
|---|---|
| Sede, rol de plataforma y memberships: **una vez por pedido**, antes del primer ítem | Ninguna toma lock. Fallaban en el primer ítem con la misma excepción, que el lote no traduce a resultado parcial. El permiso se decide con lo leído en el pedido, como las mutaciones de #91; la revocación sigue sin cruzar al pedido siguiente. El `SUPPORT_ACCESS_USED` se sigue escribiendo una vez por ítem |
| Zona de la sede: del snapshot que ya se leyó para validar el tenant | Es la misma fila |
| Esquema de cobro de la oferta: una vez por pedido (`LecturasDeConsumo`) | Era una lectura consistente sin lock |
| Neto del ledger de una asistencia **recién insertada** | Su id es nuevo: ningún movimiento puede tenerlo |
| Relectura del saldo después de consumir | `lockElegibles` trae el saldo bajo su `FOR UPDATE`, que la transacción retiene: el UPDATE que tocó la fila restó exactamente uno |

Sentencias por participante (`ListadosDeActividadSentenciasIT`, lote de 1 contra lote de 6; antes
medido sobre `origin/main` con el mismo IT):

| Pasada | Antes | Después |
|---|---:|---:|
| alta, presente (consume) | 18 | **11** |
| corrección a ausente (devuelve) | 15 | **12** |
| corrección a presente (vuelve a consumir) | 18 | **12** |

El IT afirma esos tres techos: si algo vuelve a leer por ítem lo que es igual para todo el lote, se
rompe. Lo que queda en el ítem es trabajo propio: lock de la clase, inscripción, asistencia, sus
escrituras, evento, auditoría y el movimiento del crédito con su auditoría.

**Carga.** Seis corridas completas de `actividad.js` (5 min, misma configuración que arriba, stack
aislado `lote` en 3341/8101, imagen de `origin/main` contra imagen de esta rama, intercaladas):

| Corrida | lote p50 / p95 / p99 | interactivo p95 / p99 | COMMIT promedio |
|---|---|---|---:|
| antes 1 | 868 / 1.458 / 2.858 | 133 / 1.030 | — |
| antes 2 | 601 / 895 / 1.753 | 84 / 707 | 4,3 ms |
| antes 3 | 663 / 985 / 1.277 | 84 / 682 | 4,2 ms |
| después 1 | 873 / 12.055 / 19.517 | 1.875 / 3.799 | — |
| después 2 | 821 / 1.600 / 2.971 | 124 / 1.052 | 7,6 ms |
| después 3 | 773 / 1.256 / 2.092 | 106 / 805 | 6,9 ms |

**La carga no distingue la mejora del ruido de esta máquina, y no hay que leerla como empeoramiento.**
Las corridas "después" 2 y 3 coincidieron con tres a cuatro MySQL de Testcontainers de otros
agentes corriendo en el mismo disco (registro de contenedores de la sesión), y el COMMIT promedio
pasó de 4,2 a 7,6 ms sin que el código toque el commit. Ocho corridas cortas más (2 min, misma
base, backend alternado) dieron el p95 del lote mejor con la rama en tres de cuatro pares y una
dispersión de 3 a 28 s entre corridas iguales. El digest de MySQL sí confirma el efecto: ~15.000
lecturas de memberships y ~18.000 de sede menos por corrida, con la misma cantidad de COMMIT.

**Lo que el número dice para U-49.** Por ítem quedan 11–12 sentencias y **un COMMIT durable**, y en
esta máquina el COMMIT (4–11 ms según la carga del disco) cuesta tanto como todas las sentencias
juntas (~0,4 ms cada una). La opción (a) saca un tercio de las sentencias —estimado ~3 ms de
conexión por ítem, ~70 ms por lote de 25— pero no toca los 25 commits del lote, que son lo que
satura el pool. Bajar más exige (b) agrupar ítems por transacción o (c) limitar lotes concurrentes:
las dos siguen siendo decisión del usuario.

### U-48: el lock del producto, sólo en la venta de un abono (17/10/2026, rama `akine-u48-lock-solo-abonos`)

Decisión del usuario del 17/10 (U-48, la recomendada). `VentaDePaseService.vender` lee el **tipo**
del producto sin lock (`ProductoServicioRepositoryPort.tipoDe`, una columna inmutable) y:

- **abono:** toma el producto con `FOR UPDATE` como antes —es lo que serializa los periodos de la
  misma persona, que ningún unique puede ver— y vuelve a mirar la clave con el lock tomado (09.03);
- **pack:** lo lee sin lock, en `READ_COMMITTED`. Dos ventas con la misma `Idempotency-Key` llegan
  las dos al INSERT; la segunda choca con `uk_pase_idempotency` cuando la primera commitea, y
  `vender` relee la clave **después del rollback, en una transacción nueva `READ_COMMITTED`**, como
  el cobro de #131: misma huella devuelve la venta existente (`creada = false`), otra huella es
  `idempotency-key-conflict`. Sin venta que lo explique, el choque sigue su camino. Una baja del
  producto que commitea después de la lectura se cuela: es una venta del último instante, anulable.

La transacción pasó de `@Transactional` a un `TransactionTemplate` para poder releer fuera de la
que choca. Sin cambio de contrato ni migración (`OpenApiContractIT` en verde).

**Carreras.** `CarrerasDePasesIT`: la dirigida de 09.03 quedó sobre un **abono** (las dos ventas
esperan el `FOR UPDATE` del producto) y se sumaron dos dirigidas de **pack sin lock**. Para dejar
las dos ventas en el INSERT, la fila retenida es la del producto y lo que espera es la FK de
`pase_servicio`, que pide un lock compartido sobre el padre; el test afirma además que ninguna
pidió el producto con `FOR UPDATE`. Con la misma clave: un pase, una deuda, una `COMPRA`, una
auditoría y las dos devuelven el mismo pase. Con la misma clave y otro titular: una venta y un
409 de clave reusada. La ráfaga de seis ventas con la misma clave (pack) pasa por el unique en vez
del lock. **Verificado por mutación:** sin la relectura, las tres de pack fallan con
`Duplicate entry ... uk_pase_idempotency`.

**Carga.** Stack aislado `u48` (MySQL 3351, backend 8111), imagen de `origin/main` (`77284eb`)
contra imagen de esta rama, misma base sembrada, backend con 4 CPU / 2 GB y pool de 10. Dos
escenarios: el de §12 sin el lote (5 min) y **sólo ventas a 30/s** del mismo producto (2 min,
`-e AKINE_ACTIVIDAD_LOTES_RPS=0 -e AKINE_ACTIVIDAD_INSCRIPCIONES_RPS=0 -e AKINE_ACTIVIDAD_VENTAS_RPS=30
-e AKINE_ACTIVIDAD_VUS=1 -e AKINE_ACTIVIDAD_DURACION=2m`), que es lo que muestra la cola. En este orden:
antes sin el lote, antes 30/s, después 30/s, después sin el lote, antes 30/s, después 30/s.

| Corrida | ventas hechas | p50 / p95 / p99 (ms) |
|---|---|---|
| sin el lote, antes (4,4/s) | 1.501 | 61 / 144 / 295 |
| sin el lote, después (4,4/s) | 1.501 | 69 / 130 / 181 |
| sólo ventas a 30/s, antes 1 | 3.344 de 3.600 | 2.624 / 4.359 / 5.490 |
| sólo ventas a 30/s, antes 2 | 2.888 de 3.600 | 3.636 / 5.774 / 6.374 |
| sólo ventas a 30/s, después 1 | 3.599 de 3.600 | 56 / 120 / 232 |
| sólo ventas a 30/s, después 2 | 3.598 de 3.600 | 59 / 110 / 201 |

Errores técnicos **0 %** en las seis. En la corrida sin el lote, el resto (inscribir, lecturas,
todo lo interactivo p95 74–81 ms) no cambia.

**Lectura.** A 4,4 ventas/s la cola del lock no se forma en esta máquina: el p95 de 723 ms de §12
no se repitió con `origin/main` (144 ms), así que aquel número tenía también ruido del disco.
Donde se ve es al subir la tasa: **antes**, todas las ventas del producto van en fila detrás del
`FOR UPDATE` y el techo es ~20 ventas/s —a 30/s pedidas, k6 dejó de largar entre 256 y 712 y las
que entraron esperaron 2,6–3,6 s de mediana—; **después**, las 3.600 entran y el p95 queda en
110–120 ms, dentro del SLO. Los abonos siguen en fila por producto, que es su correctitud.
Los números de una sola máquina de desarrollo no son los del entorno de destino (§7).

### U-49 c: el límite de lotes por sede, preparado y apagado (17/10/2026, rama `akine-u49c-limite-de-lotes`)

DP-30: la opción (c) se activa **sólo si el entorno de destino lo exige**. Quedó lista para
encender por configuración y **apagada por defecto**: con la propiedad ausente o en `0` no se crea
ningún semáforo y el lote corre exactamente como antes (`LimiteDeLotesApagadoIT`).

| Propiedad | Default | Variable de entorno |
|---|---|---|
| `akine.activity.asistencia.lotes-concurrentes-por-sede` | `0` = sin límite | `AKINE_ACTIVITY_ASISTENCIA_LOTES_CONCURRENTES_POR_SEDE` |
| `akine.activity.asistencia.lotes-espera-maxima` | `2s` | `AKINE_ACTIVITY_ASISTENCIA_LOTES_ESPERA_MAXIMA` |

**Qué hace con N > 0.** `LimiteDeLotesPorSede` guarda un semáforo justo por sede, **en memoria y
por instancia**: con varias instancias el techo efectivo es N × instancias, que es lo que se quiere,
porque lo que se protege es el pool de cada instancia. El lote lo toma **después** de autorizar y
**antes** del primer ítem, sin conexión abierta (el lote no es transaccional y `open-in-view` está
apagado), y lo suelta después del último. El que no consigue lugar espera hasta
`lotes-espera-maxima`; si no, **503 `service-unavailable` con `Retry-After`** y ningún ítem escrito.
El lote de un solo ítem y el registro individual no pasan por el límite. **No cambia
transacciones, locks ni el resultado por ítem**: el semáforo envuelve el bucle, no lo que hay
adentro (`ListadosDeActividadSentenciasIT` sigue en 11/12 sentencias por participante).

**503 y no 429.** 429 dice que el *cliente* mandó demasiados pedidos (RFC 6585) y es lo que viaja
como `rate-limited` en el login, con un mensaje de "demasiados intentos". Acá quien recibe el
rechazo mandó uno: el límite es la capacidad de la sede, compartida entre todos los que toman lista
ahí. Es la sobrecarga transitoria de RFC 9110 §15.6.4, y `service-unavailable` ya es el tipo que
viaja cuando el pool no entrega conexión, que es justo lo que el límite evita. Sin problemType
nuevo; el contrato pasa a **0.127.0** sólo para declarar el 503 y su `Retry-After` en
`registrarLote` (aditivo).

**Métricas** (`docs/observabilidad.md` §8): `akine_asistencia_lotes_total{resultado=inmediato|tras-espera|rechazado}`
y el gauge `akine_asistencia_lotes_en_espera`, registrados en cero aunque esté apagado.

**Cuándo encenderlo.** Cuando, en el entorno de destino, tomar lista en lote haga subir
`hikaricp_connections_pending` y el p95 de lo interactivo (el síntoma de la corrida 1 de §12). Con
pool de 10, un lote ocupa **una** conexión a la vez, así que N = 1 o 2 por sede deja holgura aun con
varias sedes tomando lista; se sube de a uno mirando `rechazado`. Runbook: R8 de
`docs/operaciones/runbooks.md`. **No se midió con k6**: se enciende recién con números del entorno
de destino (§7), y esta máquina no los da.

**Tests.** `LimiteDeLotesPorSedeTest` (unitario: apagado, N lugares, por sede, espera que entra,
rechazo con `Retry-After` redondeado hacia arriba y nunca 0, doble `close`, interrupción) y, contra
MySQL, `LimiteDeLotesPorSedeIT` con N=1 y 2 s: un lote A retenido en el `FOR SHARE` de su clase
(`CarreraDirigida`) y un lote B de **otra** clase de la misma sede, que (1) espera los 2 s y recibe
el rechazo sin escribir nada mientras A termina bien, (2) espera y entra cuando A termina, y (3) con
un ítem o por el registro individual no espera. `ActivityControllersTest` cubre el 503 por HTTP.

## 13. CPU por pedido sin cambiar reglas (17–18/10/2026, rama `akine-cpu-por-pedido`)

Opción 6 de §10: con los locks fuera del camino, el p95 de escrituras lo marca la CPU en ráfagas.
Esta rama perfila el backend bajo la mezcla de `perf/` y **saca dos costos que se pagaban en cada
pedido sin decidir nada**. No cambian el contrato, las transacciones de negocio, el orden de los
locks, las aislaciones ni el esquema (no hay migración: `V108` no se usó).

Stack aislado con prefijo `cpu-` (MySQL 3361, Mailpit 8061/1061, backend 8121) y, para las
comparaciones simultáneas, un segundo con prefijo `cpub-` (3362, 8062/1062, 8122). Backend con 4
CPU, 2 GB y el pool por defecto (10). Las imágenes se armaron con el jar de cada commit sobre un
JDK 21 (las capas del `Dockerfile`, pero con `jcmd` para volcar JFR).

### Cómo se midió

- **Perfil.** JFR `settings=profile` con `-XX:FlightRecorderOptions=stackdepth=256` durante la
  mezcla completa, volcado con `jcmd 1 JFR.dump`, y las muestras de la meseta de 500 usuarios
  (180 s) agrupadas por endpoint (el `*Controller` más cercano a la raíz), por fase y por método
  inclusivo. Para atribuir lo que pasa **fuera** del controller hacen falta las pilas largas.
- **CPU por pedido.** `process_cpu_time_ns_total` del backend antes y después de la corrida,
  dividido por los pedidos de `http_server_requests_seconds_count`. Nuevo en `perf/stack.sh`:
  `cpu-por-pedido`, lo mismo por endpoint con un usuario en serie (descuenta lo que el proceso gasta
  quieto); `perf/k6/por-pedido.js` acepta `AKINE_POR_PEDIDO_DESDE_RESERVA` para repetir la reserva
  sobre la misma base.
- **La máquina tiene más ruido que el efecto.** La misma imagen dio entre 13,6 y 24,2 ms de CPU por
  pedido según la hora (otros agentes con Testcontainers y stacks propios en el mismo Docker
  Desktop), y el serial por endpoint varía ±20 % entre dos pasadas seguidas. Por eso la comparación
  que vale es la **simultánea**: dos stacks idénticos con base nueva y el mismo sembrado, uno con
  `main` y otro con la rama, corriendo la mezcla completa **a la vez**, cuatro rondas con los stacks
  intercambiados. El ruido de la máquina cae igual sobre los dos.

### Dónde se iba la CPU en `main` (`61e5859`, meseta de 500 usuarios, 11 422 muestras)

| Fase (marcador más cercano a la hoja) | % de las muestras |
|---|---:|
| Dentro del controller (consultas, Hibernate, lógica) | 47,5 |
| **Lecturas de acceso**: membership, rol de plataforma y grants | **20,4** |
| Tomcat (lectura y escritura del socket) | 6,7 |
| **Elegir el handler de Spring MVC** (`DispatcherServlet.getHandler`) | **6,3** |
| Abrir y cerrar transacciones | 5,2 |
| Micrometer (`http.server.requests`, observación del pedido) | 5,0 |
| Serializar la respuesta (Jackson) | 3,5 |
| Logging (chequeos de nivel de Hibernate y Spring) | 3,1 |
| JWT | 1,6 |

Por endpoint: búsqueda de slots 15,5 %, recepción del día 10,2 %, cobro 6,9 %, agenda del día 6,0 %,
Paciente 360 5,8 %, reserva 5,6 %, login 3,0 %, reporte 2,7 %, y **42,3 % en los filtros sin llegar
al controller** (acceso, JWT, mapeo, serialización). Por biblioteca: Connector/J 25,6 %, Hibernate
21,2 %, Spring 11,5 %; Argon2 del login 2,8 %, TLS hacia MySQL 2,3 %. Asignación: ~950 KB por
pedido, pero el GC es chico (1,5 s de pausas en toda la corrida): el costo es del hilo del pedido.

Dos cosas de esa tabla no deciden nada y se pagaban en cada pedido:

1. **`OrganizationMembershipDirectory.resolveMembership`, 14,9 % de toda la CPU.** Cada pedido con
   contexto abría una transacción de solo lectura para cuatro lecturas —memberships de la cuenta en
   la organización, sede por (id, organización) activa, organización vigente y suscripción—: 4
   `SELECT` más dos `SET autocommit`, dos `SET SESSION TRANSACTION` y un `COMMIT`, nueve idas y
   vueltas con su paso por Hibernate, el TLS y el transaction manager. La transacción estaba para
   ver las cuatro en una misma foto (§9).
2. **Elegir el handler, 6,3 %.** Casi todas las rutas llevan variables, así que Spring no las halla
   por ruta directa y evalúa en cada pedido las condiciones completas —método, `Accept`,
   `Content-Type`, patrón— de **las ~330 operaciones**, a través de la cadena de envoltorios del
   request.

Lo que no se tocó, y por qué: la relectura de membership y rol en cada pedido es la regla
(ADR-0020, T-7); los `SET SESSION TRANSACTION READ ONLY` los defiende §9 (que MySQL rechace una
escritura en una transacción de lectura); el TLS hacia MySQL es seguridad; las consultas derivadas de
Spring Data ya usan la caché de interpretación de Hibernate (pasarlas a `@Query` no ahorra); el
`Calendar` UTC con que Hibernate lee y escribe los `Instant` (~3 % de la CPU y 15 % de las
asignaciones) sólo se va cambiando cómo viajan las fechas, que es la regla de `AGENT.md` §5; y apagar
las métricas de logback quita series de `/actuator/prometheus`.

### Qué se cambió

| Cambio | Dónde | Por qué no cambia ninguna regla |
|---|---|---|
| El contexto de trabajo se resuelve con **una sola sentencia**: las memberships activas de la cuenta en la organización, cada una con la sede (`LEFT JOIN` por id, organización y `active`), la organización vigente y el estado de la suscripción. Sin transacción propia: `SUPPORTS`, como el rol de plataforma de §9 | `MembershipRepository.leerContextoDeAcceso`, `OrganizationMembershipDirectory`, `LecturasDeAcceso.contexto`, `LecturaDeContexto` | Las mismas condiciones que las cuatro lecturas, decididas en el mismo orden: vigencia y alcance con `MembershipSelection` (gana la de la sede), sede de **esa** organización y activa, organización vigente, suscripción presente; cualquier falta es el mismo `Optional.empty()` y el mismo 404. Una sentencia es una foto por sí misma: la consistencia que daba la transacción se conserva. Sigue sin caché, se lee en cada pedido. Cada `LEFT JOIN` trae a lo sumo una fila (PK y `uk_subscription_organization`): una fila por membership. Las memberships quedan en la memoria del pedido como antes, para la evaluación de permisos. Dentro de una transacción del llamador se une a ella, igual que antes |
| **Descarte previo** de las rutas que no pueden coincidir: antes de la evaluación completa de Spring, se compara la forma del camino (cantidad de segmentos y literales en su lugar) con la de cada patrón simple. Si no puede coincidir, se devuelve lo que devolvería Spring (`null`) sin evaluar el resto; si puede, decide Spring como siempre | `MapeoDeRutasConDescarte`, `MapeoDeRutasConfig` (`WebMvcRegistrations`) | Es una condición **necesaria** de `PathPattern.matches` para patrones con sólo literales y variables de un segmento, comparada contra el valor decodificado del segmento, el mismo que usa Spring; si el analizador no distingue mayúsculas, no descarta. Comodines, regex, `**` y la raíz van sin descarte. El conjunto de coincidencias es el mismo, y por lo tanto el handler; 404/405/406/415 los decide Spring recorriendo todas las rutas por su cuenta. Las formas se arman una vez al registrar las rutas y se buscan por identidad: `RequestMappingInfo.hashCode` no está cacheado, y buscarlo en un `ConcurrentHashMap` en cada evaluación costaba más de lo que ahorraba (medido en una primera versión) |

### Antes y después

**Perfil** (JFR, meseta de 500 usuarios, misma ventana, corridas separadas):

| | `main` | rama |
|---|---:|---:|
| Muestras de CPU en la meseta | 11 422 (110 req/s) | **8 826** (~114 req/s) |
| `resolveMembership` | 1 702 (14,9 %) | **449 (5,1 %)** |
| `DispatcherServlet.getHandler` | 745 (6,5 %) | **454 (5,1 %)** |

**Sentencias por pedido** (`perf/stack.sh por-pedido`, las dos imágenes a la vez sobre bases
gemelas): **8 menos en todos los endpoints con contexto** —una transacción menos: dos
`SET autocommit`, dos `SET SESSION TRANSACTION`, un `COMMIT` y tres `SELECT`—.

| Endpoint | Sentencias `main` → rama | SET | COMMIT |
|---|---:|---:|---:|
| agenda del día | 19 → **11** | 8 → 4 | 2 → 1 |
| búsqueda de slots | 32 → **24** | 8 → 4 | 2 → 1 |
| recepción del día | 23 → **15** | 8 → 4 | 2 → 1 |
| Paciente 360 | 22 → **14** | 8 → 4 | 2 → 1 |
| cobro | 35 → **27** | 14 → 10 | 4 → 3 |
| reporte | 33 → **25** | 6 → 2 | 2 → 1 |
| reserva | 47 → **39** | 16 → 12 | 4 → 3 |

**CPU por pedido, mezcla completa a 500 usuarios, `main` y la rama corriendo a la vez** (dos stacks,
base nueva cada ronda, cero errores técnicos en las ocho corridas):

| Ronda (stack A / stack B) | `main` ms CPU/pedido | rama ms CPU/pedido | diferencia |
|---|---:|---:|---:|
| 1 (A `main`, B rama) | 17,46 | 15,50 | **−11,2 %** |
| 2 (A rama, B `main`) | 18,62 | 16,41 | **−11,9 %** |
| 3 (A `main`, B rama) | 20,16 | 17,66 | **−12,4 %** |
| 4 (A rama, B `main`) | 25,82 | 23,87 | **−7,6 %** |

**Entre 7,6 % y 12,4 % menos CPU por pedido en las cuatro rondas, con los stacks intercambiados**
(promedio −10,8 %). Corriendo una imagen por vez, intercaladas a lo largo de cinco horas, `main`
promedió 19,4 ms (9 corridas, de 13,6 a 24,2) y la rama 18,2 ms (10 corridas, de 15,9 a 22,2): la
dirección es la misma, pero la deriva de la máquina en esas horas es del doble que el efecto.

**Por endpoint**, en serie (`cpu-por-pedido`, promedio de 4 pasadas por imagen con el JIT caliente,
intercaladas; ±20 % de ruido por pasada), con el primer cambio solo: agenda del día 20,2 → 14,9 ms,
recepción 26,2 → 21,0, Paciente 360 16,5 → 14,0, cobro 34,0 → 29,3, reporte 24,7 → 21,7, reserva
35,2 → 31,2, búsqueda de slots 27,4 → 26,1. El descarte de rutas (~1–2 ms) no se distingue en serie;
se ve en el perfil y en la comparación simultánea. El login (Argon2) no cambia.

**Latencia.** Con dos stacks a la vez la máquina se satura y el p95 de los dos sube a 1–5 s: esas
corridas sirven para la CPU, no para el SLO. Con un solo stack y la máquina quieta, intercalando
`main` y la rama sobre la misma base (17/10, 20:14–21:18; la rama todavía con la primera versión
del descarte):

| Par | `main` p50 / p95 / p99 ms (interactivo) | rama p50 / p95 / p99 ms |
|---|---|---|
| 1 (`main` primero) | 31 / 320 / 1 559 | 24 / **152** / 408 |
| 2 (`main` primero) | 28 / 261 / 675 | 21 / **103** / 494 |
| 3 (rama primero) | 23 / 179 / 600 | 20 / **106** / 536 |
| 4 (rama primero) | 23 / 148 / 691 | 20 / **112** / 624 |

En los cuatro pares el p95 de la rama es menor (103–152 contra 148–320 ms) y las muestras con
pedidos esperando conexión bajan (2–6 contra 3–13 muestras de 5 s). Más tarde, con la máquina cargada por otros
agentes, las dos imágenes dieron p95 de 300 a 1 300 ms y una corrida de `main` sufrió una pausa de
la máquina entera (p99 16 s): esa se descarta. Con la máquina quieta las dos cumplen o rozan el
SLO de ADR-0016; el ruido de la hora pesa más que el cambio, y por eso la evidencia firme de la rama
es la CPU por pedido, no el p95.

### Tests

- `OrganizationMembershipDirectoryTest` reescrito sobre la sentencia única: los mismos casos
  (vencida, futura, de otra sede, sede ajena o dada de baja, organización dada de baja, sin
  suscripción, estados) más "gana la de la sede" y "una sola lectura".
- `ContextoDeAccesoIT` (nuevo, MySQL real): sede de otra organización, sede y organización dadas de
  baja, varias memberships en orden de id sin multiplicarse por las uniones, la dada de baja afuera,
  y sin membership ninguna fila.
- `MapeoDeRutasEquivalenteIT` (nuevo): para cada operación, el pedido que le corresponde y siete
  variantes (otro método, `DELETE`, un segmento de más o de menos, barra final, literal cambiado y
  **literal codificado con `%XX`**), comparando el handler o la excepción contra un
  `RequestMappingHandlerMapping` de Spring sin descarte con la misma configuración: más de mil
  comparaciones. **Verificado por mutación:** comparar contra el valor crudo del segmento en vez del
  decodificado lo hace fallar (`DELETE /api/v1/%66inanciadores/1/planes/1`).
- En verde contra MySQL: `AislamientoDeTenantIT`, `AislamientoPorContextoIT`, `MatrizDePermisosIT`,
  `LoginConcurrenteIT` y `OpenApiContractIT`; la suite completa, con los ITs de concurrencia, en el CI.

Los números de una sola máquina de desarrollo no son los del entorno de destino (§7).
