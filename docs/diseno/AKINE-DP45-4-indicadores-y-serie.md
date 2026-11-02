# AKINE-DP45-4 — Indicadores de las pantallas económicas y serie temporal de reportes

**Decisión:** DP-45, punto 4 (`docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7).
**Contrato:** 0.147.0. **Migración:** ninguna.
**Rama:** `akine-dp45-4-indicadores-y-serie`.

Todo es aditivo: ninguna operación existente cambia de forma ni de respuesta para un cliente que no
lea lo nuevo. Solo se calcula lo que ya está en las tablas; ninguna tabla nueva, ningún resumen
materializado.

## 1. Qué se agrega

| # | Pantalla | Operación | Cambio | Permiso |
|---|---|---|---|---|
| 1 | Caja diaria | `verJornada` — `GET /consultorios/{c}/caja/jornadas/{j}` | **Extiende** la respuesta: `resumen` (`ResumenDeJornadaCaja`) y, en cada `TotalPorMedioDeCaja`, `ingresos`, `egresos` y `cantidad` | `caja:operate` (el de la pantalla) |
| 2 | Egresos | `resumirEgresos` — `GET /consultorios/{c}/egresos/resumen?desde&hasta` | **Nueva** (`ResumenDeEgresos`) | `caja:operate` (el de la bandeja) |
| 3 | Honorarios | `calcularBorradorDeHonorarios`, `confirmarLiquidacionDeHonorarios`, `verLiquidacionDeHonorarios` | **Extiende** la respuesta: `resumen` (`ResumenDeHonorarios`) | `caja:operate` (sin cambios) |
| 4 | Reportes | `generarSerieDeReporte` — `GET /consultorios/{c}/reportes/{reporte}/serie?desde&hasta&agrupacion=DIA\|SEMANA` | **Nueva** (`SerieDeReporte`) | `reporte:read` + el permiso de cada sección |

Ningún permiso nuevo.

### 1.1 Caja

`resumen` sale de la **misma** consulta que ya armaba `totalesPorMedio` (`movimiento_caja` de la
jornada), extendida con dos `SUM(CASE ...)` y un `COUNT`. Campos: `ingresos`, `egresos`,
`efectivoIngresos`, `efectivoEgresos`, `otrosMediosIngresos`, `otrosMediosEgresos`,
`cantidadMovimientos`. El saldo teórico ya existía (`saldoTeorico`) y no se repite.

- **Las reversiones restan de lo que revierten**: `ingresos = INGRESO − REVERSION_DE_INGRESO`,
  `egresos = EGRESO − REVERSION_DE_EGRESO`. Un ingreso anulado no es un egreso (`TipoMovimiento`).
  Con eso, para el efectivo, `saldoInicial + efectivoIngresos − efectivoEgresos = saldoTeorico`
  (lo verifica el IT contra la columna materializada).
- **Solo en el detalle.** En el listado de jornadas `resumen` es `null`: calcularlo por fila sería
  un N+1. La jornada abierta se encuentra con `estado=ABIERTA` y después se pide su detalle.
- Diferencia con el reporte económico, declarada: `caja-ingresos-efectivo` del reporte suma el tipo
  `INGRESO` **bruto** (no resta reversiones, para que la conciliación con M19 dé cero; ver
  `MovimientoCajaRepository`). La tira de la caja es neta porque tiene que cuadrar con el arqueo.

### 1.2 Egresos

`resumirEgresos`: sin fechas, **el mes en curso en la zona de la sede**; con solo `desde`, hasta el
fin de ese mes; con solo `hasta`, desde el primero de su mes. Mismo recorte por fecha que la
bandeja (`registrado_en` en el semiabierto de instantes del día de la sede). Ventana máxima 366
días; invertido o más ancho → 400 `validation-error`.

Una consulta, agrupada por `moneda, categoria, estado`. Por moneda (un egreso tiene una sola, y
sumar monedas no es un total) y por categoría:

- `registrado`: importe de `CONFIRMADO` + `PAGADO` (el mismo universo que el indicador
  `egresos-confirmados` del reporte).
- `pagado`: `importe_total − saldo_pendiente` de esos egresos, **hoy** (un pago anulado devuelve su
  importe al saldo). No se reconstruye a la fecha de corte.
- `pendiente`: `saldo_pendiente` de esos egresos. Es el pendiente **de los egresos del periodo**,
  no el `egresos-saldo-pendiente` del reporte (que es el de todos los egresos vivos).
- `cantidad`, y aparte `cantidadBorrador`/`importeBorrador` (no son compromiso) y `cantidadAnulados`.

Es egreso (M22), no caja: un pago por transferencia salió sin tocar el cajón.

### 1.3 Liquidación de honorarios

`resumen` en el borrador y en la liquidación: `cantidadClases`, `totalPresentes`, `importeTotal`,
`regla` (`POR_PARTICIPANTE_PRESENTE`, la única de DP-38: tarifa por participante × presentes,
repartida en partes iguales entre instructores, resto al principal). En el borrador además
`cantidadExcluidas`; en la liquidación `importePagado` y `saldoPendiente`, del egreso que la
confirmación creó (su importe es el de la liquidación y no se edita: `egreso-de-liquidacion`). Para
eso `billing.spi.LiquidacionesDeHonorarios.Liquidacion` gana `egresoSaldoPendiente` (interno, la
dirección `activity → billing` no cambia).

### 1.4 Serie temporal de reportes

`generarSerieDeReporte` comparte con `generarReporte` **todo lo previo a calcular**
(`ReporteService.preparar`): tenant del contexto, sede (404 cross-tenant), `reporte:read`, soporte,
recorte a la actividad propia, validación del periodo (366 días), y la selección de secciones con
las **mismas omisiones** (permiso de sección y secciones que no saben recortarse). Una sección
clínica visible audita `REPORTE_CONSULTADO` igual que el reporte, con `serie` en el detalle.

Cada contribuyente declara sus series con `ReporteContributor.seriesDiarias(consulta)` (default:
ninguna). Devuelve valores **por día de la sede**; `reporting` completa ceros y agrupa por semana
ISO (lunes a domingo, recortada al periodo en los extremos). Cada serie lleva la `clave` de su
indicador, su fuente y su criterio de fecha; `total` es la suma de los puntos y **coincide con el
indicador** (lo verifica el IT para las cinco series económicas).

**Tope: 100 puntos.** Con `DIA`, hasta 100 días (más → 400 `rango-de-reporte-invalido` con
`maximoDias: 100` y la sugerencia de `SEMANA`); con `SEMANA`, la ventana máxima de 366 días entra
(54 puntos).

**El día de la sede, sin depender de las tablas de zonas de MySQL.** Las consultas por día de las
columnas instante usan `DATE(CONVERT_TZ(col, '+00:00', :desfase))` con un desfase **numérico**, que
MySQL resuelve siempre; el nombre IANA devuelve `NULL` si las tablas faltan. El periodo se parte en
tramos de desfase constante en cada transición de horario de verano
(`ConsultaDeReporte.tramosHorarios()`), y cada tramo se consulta con su desfase: la asignación al
día es exacta a los dos lados del cambio (IT con `Europe/Madrid`). Las columnas que ya son fecha
local (`movimiento_caja.fecha_negocio`) se agrupan directo.

#### Qué indicadores tienen serie (decisión)

| Sección | Reportes | Con serie | Fuente · criterio |
|---|---|---|---|
| `economia` | ECONOMICO, OPERATIVO | `devengado` | M18 `obligacion` · `devengada_en` |
| | | `cobrado` | M19 `cobro` · `cobrado_en` |
| | | `caja-ingresos-efectivo`, `caja-egresos-efectivo` | M20 `movimiento_caja` · `fecha_negocio` |
| | | `egresos-confirmados` | M22 `egreso` · `registrado_en` |
| `turnos` | TURNOS, OPERATIVO | `turnos-totales`, `turnos-cancelados`, `turnos-ausentes` | M12 `turno` · `inicio` (la **misma** consulta del indicador y el detalle) |
| `sesiones` | CLINICO, OPERATIVO | `sesiones-cerradas` | M14 `sesion` · `cerrada_en` |

**Sin serie, a propósito:** los saldos actuales (`deuda-vigente`, `egresos-saldo-pendiente`), que
no se reconstruyen a una fecha; los porcentajes (`tasa-ausentismo`), que no se suman; la
conciliación, que es una comparación y no un hecho; los estados de hoy de turnos y sesiones que
todavía cambian (reservados, confirmados, en espera, sesiones en borrador); y las secciones de
F9 (clases, pases, continuidad, servicios, financiadores, casos), que el gráfico de la pantalla no
pide hoy. Agregarlas es implementar `seriesDiarias` en su contribuyente, sin tocar `reporting`.

Cada serie es **un** concepto: deuda, cobro, caja y egreso tienen la suya y ninguna los suma.

## 2. Qué no se hace

- Ningún resumen materializado ni job: todo se calcula al leer.
- No se reconstruyen saldos a la fecha de corte (ni la deuda, ni lo pagado de un egreso).
- No hay serie por mes (`agrupacion=MES`): con 366 días de ventana, `SEMANA` alcanza.
- No hay serie de las secciones de F9.
- No se agrega listado de liquidaciones (no existía; la pantalla parte del borrador o del egreso).

## 3. Índices

Ninguno nuevo. Las consultas por día repiten el `WHERE` de su indicador, que ya usa los índices de
sede y fecha (`ix_turno_sede_dia`, `ix_sesion_sede_cierre` de V59, los de `obligacion`, `cobro`,
`egreso` y `movimiento_caja` por sede); el `GROUP BY` es sobre las filas ya recortadas. El resumen
de egresos usa el mismo recorte que `buscar`. Si la carga en destino (DP-32) lo mostrara, el
candidato sería `egreso (organization_id, consultorio_id, registrado_en)`.

## 4. Design challenge

1. **Ownership.** Ninguna tabla nueva. Cada consulta vive en el repositorio del módulo dueño de su
   tabla (`billing`, `scheduling`, `encounter`); `reporting` sigue sin repositorios.
2. **Ciclos.** No aparece ninguna arista nueva: los contribuyentes ya implementaban
   `reporting.spi.ReporteContributor`, y `activity → billing.spi` ya existía. ArchUnit 5/5.
3. **Tenant.** Toda consulta nueva filtra por `organization_id` y `consultorio_id`, resueltos del
   contexto del actor; la sede de otro tenant es 404 en las cuatro operaciones (IT).
4. **Reglas maestras.** Deuda (M18), cobro (M19), caja (M20) y egreso (M22) siguen separados: cada
   serie es un concepto, la tira de caja suma solo `movimiento_caja`, la de egresos solo `egreso`,
   y la de honorarios habla del egreso sin decir nada de la caja. Ningún campo nuevo suma dos.
5. **Baja lógica.** Solo lecturas; las consultas repiten las exclusiones de baja de sus indicadores.
6. **Contrato.** Aditivo: dos operaciones nuevas y campos nuevos en tres respuestas (`resumen`,
   `ingresos`/`egresos`/`cantidad` por medio). Minor 0.147.0.
7. **Ruta crítica.** Todo lo que se resume ya existe y está en `main` (07.03–07.06, 08.09b, M23).
8. **El caso que rompe el diseño.** Una sede con horario de verano: un cobro de las 00:30 del día
   posterior al cambio, agrupado con el desfase de antes, caería en el día anterior, y el gráfico
   contradiría a la tira sin que nada falle. Lo resuelven los tramos de desfase constante (IT
   `la_serie_respeta_el_cambio_de_horario`). El segundo: una serie que no use el mismo recorte que su
   indicador; lo cubre el IT que compara `total` contra el indicador para las cinco económicas.
