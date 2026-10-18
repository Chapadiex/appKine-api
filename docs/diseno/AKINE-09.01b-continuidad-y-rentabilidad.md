# AKINE-09.01b — Continuidad del circuito e ingresos por servicio (RF-M23-008, RF-M23-009)

> 17/10/2026 · rama `akine-09.01b-continuidad-rentabilidad` · contrato **0.123.0** · migración **`V106`**.
> Sigue a AKINE-09.01 (#145), que dejó estos dos RF afuera "por falta de cimiento". Este documento
> separa, con evidencia en el esquema, **qué parte tenía cimiento y qué parte no**.

## 1. Qué se pidió y qué existe

| RF | Lo que pide | Dato que hace falta | ¿Existe? | Dónde |
|---|---|---|---|---|
| **008** | Tránsito de personas entre rehabilitación, readaptación y actividades preventivas; CA-06: *"cuántas personas pasan de alta clínica a un servicio preventivo"* | (a) el **alta clínica** como hecho tipado | **Sí** | `sesion.proxima_conducta = 'ALTA'` (V35, RF-M14-007) con `cerrada_en` |
| | | (b) a quién pertenece cada alta | **Sí** | `sesion.historia_clinica_id` → `historia_clinica.persona_id` (por `clinical.spi`) |
| | | (c) qué servicio consumió después | **Sí** | sesiones cerradas (`sesion.oferta_id`) y asistencias (`inscripcion_clase.estado = 'ASISTIO'` + `clase_programada.oferta_id`) |
| | | (d) si ese servicio es preventivo | **Sí** | `servicio.naturaleza` ∈ {`CLINICO`, `TERAPEUTICO`, `PREVENTIVO`, `BIENESTAR`} (V24, RN-M06-005) |
| **009** | Consolidar obligaciones, cobros y caja por Oferta/Servicio, diferenciando clases, packs y abonos; separar ingresos anticipados de consumo de créditos | devengado por oferta y origen | **Sí** | `obligacion.oferta_id` (NOT NULL) + `obligacion.origen` (V64) |
| | | cobrado por oferta | **Sí** | `cobro_imputacion` → `obligacion.oferta_id` (lo **imputado**) |
| | | ingresos anticipados | **Sí** | ventas: `origen IN ('VENTA_PASE','VENTA_ABONO')`; anticipos sin imputar: `cobro.saldo_a_favor` |
| | | consumo de créditos por servicio | **Sí** | `movimiento_pase` + `pase_servicio.snapshot_oferta_id` |
| | | clase vs. prestación individual | **Sí** | `oferta_servicio_consultorio.modalidad` |
| | | **caja por servicio** | **No** | `movimiento_caja` es un medio de un cobro; un cobro paga deudas de varias ofertas con varios medios. No hay regla de prorrateo |
| | | **costos por servicio** (rentabilidad) | **No** | `egreso` (V57) no se liga a oferta ni a servicio: beneficiario colaborador/externo, categoría contable |
| | | **honorarios por clase** | **No** | No existe esquema de honorarios (U-41) |

## 2. Qué entra

Dos `ReporteCode` nuevos, aditivos: **`CONTINUIDAD`** y **`SERVICIOS`**. Cuatro contribuyentes, cada uno
en el módulo dueño de su tabla (patrón `ReporteContributor` de 07.06):

| Reporte | Sección | Módulo | Permiso | Clínica | Actividad propia |
|---|---|---|---|---|---|
| `CONTINUIDAD` | `continuidad` | `activity` | `hc:read` | **sí** (se audita) | no (se omite) |
| `SERVICIOS` | `ingresos-por-servicio` | `billing` | `cobro:register` | no | no |
| `SERVICIOS` | `creditos-por-servicio` | `activity` | `pase:read` | no | no |
| `SERVICIOS` | `clases` (la de 09.01, reusada) | `activity` | `clase:read` | no | sí |

### 2.1 Continuidad (RF-M23-008)

- **Alta clínica** = sesión cerrada en la sede con próxima conducta `ALTA` dentro del período. **No**
  el cierre del Caso: su motivo (RF-M10-005) es texto libre y no distingue alta de abandono.
- **Pasar a un servicio** = después de la **primera** alta del período de esa persona, cerrar una
  sesión o **asistir** (`ASISTIO`) a una clase de la sede. Inscribirse o comprar un pase no cuenta:
  es intención, no tránsito.
- **Hasta cuándo**: hasta el momento de la consulta, sin tope. El período recorta las altas; el
  mismo período consultado más tarde puede dar más tránsito, y el criterio lo dice.
- Indicadores: `continuidad-altas`, `continuidad-personas-con-alta`, `continuidad-a-{clinico,
  terapeutico,preventivo,bienestar}` (personas, no se suman entre sí) y
  `continuidad-tasa-preventivo` (CA-M23-008-06). Detalle por naturaleza y servicio, **sin personas**.
- "Rehabilitación" y "readaptación" **no son naturalezas del catálogo**: el reporte usa las cuatro
  que existen y no las renombra.

### 2.2 Ingresos por servicio (RF-M23-009)

- Cuatro conceptos: **prestación individual** (`SESION` + oferta individual), **clase** (`SESION` +
  oferta grupal: la atención grupal de 08.05), **pack** (`VENTA_PASE`), **abono** (`VENTA_ABONO`).
- `servicios-devengado-*` (M18, `devengada_en`, sin `ANULADA` ni baja lógica): la suma de las
  cuatro es el `devengado` del reporte económico. `servicios-cobrado-*` (M19, **`imputada_en`**,
  sin cobros anulados, sede de la deuda). `servicios-anticipos-sin-imputar`: saldo a favor actual
  de los cobros del período, que todavía no es de ningún servicio.
- **Consumir un crédito no es ingreso** (CA-M23-009-06): la venta del pack devenga una vez; los
  créditos consumidos van en `creditos-por-servicio` como **conteo**, y la ocupación en `clases`.
- Advertencia permanente **`rentabilidad-sin-costos`**: el reporte da ingresos, no margen.

## 3. Qué NO entra, y el dato que falta

| Parte del RF | Por qué no | Qué haría falta | Decisión |
|---|---|---|---|
| **Rentabilidad por servicio** (009) | No hay costos imputables a un servicio | Un modelo de costeo: imputar `egreso` a oferta/servicio (directo o por prorrateo) y los honorarios por clase | **U-50** (y U-41) |
| **Caja por servicio** (009) | Un movimiento de caja no es de un servicio | Una regla de prorrateo de medios entre las imputaciones de un cobro. El cobrado imputado ya es esa plata vista por la deuda | Dentro de **U-50** |
| **Recorte a la actividad propia** en continuidad (008) | La matriz no le da al `PROFESIONAL` el tránsito de sus pacientes hacia clases de otros | Que el usuario lo decida; hoy se omite y se declara | Dentro de **U-50** |

Ninguna de las tres se inventa: la primera aparece como advertencia en el reporte, la segunda y la
tercera en los criterios de cada indicador.

## 4. Cambios

- `offering.spi.OfertaDirectory#clasificar` → `ClasificacionDeOferta` (servicio, naturaleza, grupal).
- `clinical.spi.HistoriaClinicaDirectory#personasDe` (ids, en una consulta).
- `encounter.spi.CierresClinicosDirectory` (altas del período y sesiones cerradas posteriores; solo ids e instantes).
- `activity`: `ContinuidadEnElReporte`, `CreditosPorServicioEnElReporte`; `ClasesEnElReporte` también en `SERVICIOS`.
- `billing`: `IngresosPorServicioEnElReporte`.
- **`V106`**: `ix_cobro_imputacion_fecha (organization_id, imputada_en)`. Lo demás llega por índices existentes (ver el comentario de la migración).
- Contrato **0.123.0**: `CONTINUIDAD` y `SERVICIOS` en el enum del reporte. Aditivo.

## 5. Design challenge

1. **Ownership.** Ninguna tabla nueva. Cada consulta vive en el módulo dueño de su tabla: `sesion`
   en `encounter`, `historia_clinica` en `clinical`, `servicio`/`oferta` en `offering`,
   `obligacion`/`cobro_imputacion`/`cobro` en `billing`, `inscripcion_clase`/`clase_programada`/
   `movimiento_pase`/`pase_servicio` en `activity`.
2. **Ciclos.** Aristas nuevas: ninguna. `activity → encounter.spi`, `activity → clinical.spi`,
   `activity → offering.spi` y `billing → offering.spi` ya existían. `ModuleArchitectureTest` en verde.
3. **Tenant.** Toda consulta filtra `organization_id` y sede; el índice nuevo empieza por `organization_id`.
4. **Reglas maestras.** Sesión ≠ Turno (cuenta la sesión **cerrada**); asistencia ≠ sesión clínica
   (RN-M23-005: se cuentan por separado y la persona una vez); obligación ≠ cobro ≠ caja (RN-M23-002/003/006:
   devengado y cobrado imputado son indicadores distintos y la caja no se reparte); HC no se mezclan
   (solo se usa el dueño de cada historia).
5. **Baja lógica.** Solo lecturas.
6. **Contrato.** Aditivo: dos valores de enum. Un cliente viejo no los pide.
7. **Ruta crítica.** Los cimientos de lo que entra existen (§1). Lo que no tiene cimiento no entra (§3).
8. **El caso que rompe el diseño.** Una persona con dos altas en el período y una sesión entre las
   dos: con "después de **cualquier** alta" contaría o no según cuál se mire. Se fija la **primera**
   alta del período como referencia, y el IT lo prueba (p3). Otro: un cobro que paga un pack y una
   sesión con dos medios — por eso el cobrado se mide por imputación y la caja no se reparte.

## 6. Verificación

`ContinuidadYServiciosEnElReporteIT` contra MySQL: dos escenarios con cada indicador comparado
contra un valor escrito a mano, otro tenant que no suma, una deuda anulada que no suma, la sección
omitida sin `hc:read`, la auditoría del acceso clínico, el CSV y la conciliación del devengado por
servicio con el `devengado` del reporte económico.
