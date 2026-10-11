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
| `perf/k6/carga.js` | Los escenarios y los umbrales; escribe `perf/salida/carga-resumen.{md,json}` |
| `perf/k6/lib.js` | Login con contexto, reintento por 429, fechas en la zona de la sede |
| `perf/prometheus-latencia.mjs` | p50/p95/p99 **del lado del servidor** restando dos fotos de `/actuator/prometheus` |
| `perf/lcp/lcp.mjs` | LCP de siete pantallas con Playwright y la Performance API |
| `.github/workflows/carga.yml` | Workflow **manual** (`workflow_dispatch`), solo backend. No corre en push ni en PR |

`perf/salida/` está en `.gitignore`: los números dependen de la máquina y se reportan acá.

## 2. El stack aislado

Todo lleva el prefijo `g10-` y puertos propios. **No toca `akine-mysql`, `akine-mailpit`, el 8080
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
números, está en §8.**

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
| 6.5 | Tres transacciones y lecturas repetidas por pedido | **Sin cambios.** Ver abajo |
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

- **6.5 sin cambios.** Las dos lecturas por pedido de `membership` (filtro y evaluación de permisos)
  y las tres transacciones siguen. Cachearlas por pedido toca la evaluación de permisos de
  `organization` (ADR-0020, T-7) y no se hizo a ciegas. En el digest de la corrida: `SET autocommit`
  252 307 veces y `SET SESSION TRANSACTION READ ONLY/WRITE` 117 000 cada una, para 37 492 pedidos.
- **Quedan `REQUIRES_NEW` llamados desde adentro de una transacción**, que no son filas-lock y que
  esta carga no ejercita: los auditores de acceso de soporte y de permiso denegado
  (`PermissionDenialAuditor`, `PlanLimitRejectionAuditor`, `SupportAccessReadAuditor` y los de
  `clinical`, `person`, `resource` y `reporting`), la escritura aparte de la historia clínica y de los
  adjuntos (`HistoriaClinicaEscrituraAparte`, `AdjuntoClinicoEscrituraAparte`,
  `AdjuntoEscrituraAparte`), `ObligacionOcupadaConsulta` en presentaciones e `ImputacionDePrepago`,
  que corre en el `afterCommit` del cierre con la conexión todavía tomada. Cada uno retiene dos
  conexiones mientras dura; ninguno es por pedido normal, pero muchos 403 simultáneos o muchas
  primeras aperturas de historia clínica a la vez pueden agotar el pool del mismo modo.
- **El primer arranque, remedido:** 190 a 244 s desde que el contenedor se crea, con la aplicación
  lista a los 144–176 s y Flyway en 1 min 22 s a 2 min 12 s. Los 8 min 23 s de G-10 no se
  repitieron, pero el `start-period` cubre ese caso.

`perf/stack.sh` tiene dos arreglos que salieron de correrlo: espera a que MySQL acepte conexiones por
TCP antes de arrancar el backend (el ping por socket contesta antes, y el backend moría con
"Connection refused"), y en Git Bash usa `pwd -W` para que `docker build` reciba una ruta de Windows.
