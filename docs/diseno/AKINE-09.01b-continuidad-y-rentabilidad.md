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
- Advertencia **`rentabilidad-sin-costos`**: el reporte da ingresos, no margen. *Desde DP-39 (§7) la
  emite la sección de rentabilidad y sólo cuando ningún egreso del período está imputado.*

## 3. Qué NO entra, y el dato que falta

| Parte del RF | Por qué no | Qué haría falta | Decisión |
|---|---|---|---|
| ~~**Rentabilidad por servicio** (009)~~ | Resuelta por **DP-39**, ver §7 | Egreso imputado a una oferta (`V112`) | **DP-39** |
| ~~**Caja por servicio** (009)~~ | Resuelta por **DP-39**, ver §7 | Prorrateo de cada medio en proporción a lo imputado | **DP-39** |
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

## 7. DP-39 — rentabilidad y caja por servicio (18/10/2026)

> Rama `akine-dp39-rentabilidad-y-caja` · contrato **0.132.0** · migración **`V112`**. Resuelve las dos
> primeras filas del §3 con la decisión del usuario del 18/10 (U-50, RF-M23-009). La tercera —el
> recorte a la actividad propia en continuidad— **no** entra: DP-39 no la decide.

### 7.1 Qué decidió el usuario

1. **El egreso gana una oferta opcional.** `egreso.oferta_id` nullable, FK, misma organización y misma
   sede; se fija al cargarlo y se corrige mientras sea `BORRADOR`, como el resto de sus datos.
2. **Rentabilidad por servicio** en `SERVICIOS`: ingresos menos egresos imputados a la oferta en el
   período, sin los anulados. Los egresos sin oferta van a una fila "sin imputar". La advertencia
   `rentabilidad-sin-costos` sale **sólo** si ningún egreso del período está imputado.
3. **Caja por servicio**: un cobro que paga varias deudas se reparte **en proporción a lo imputado a
   cada una**, al centavo y cerrando exacto con el total del cobro. Se calcula al leer.

### 7.2 Diseño

**Egreso.** `V112` agrega `oferta_id` y `fk_egreso_oferta`. La FK simple no expresa "de la misma sede"
(la oferta no tiene un unique `(id, organization_id, consultorio_id)` al que apuntar), así que lo valida
`EgresoService` con `offering.spi.OfertaDirectory#find(org, sede, id)`: otra sede u otro tenant → **404**
(`OfertaDelEgresoNoAccesibleException`, `not-found`). Una oferta dada de baja **se acepta**: un egreso de
septiembre puede ser costo de un servicio que dejó de prestarse en octubre. `RegistrarEgreso`,
`EditarEgreso` y `Egreso` ganan `ofertaId`; en la corrección el valor **reemplaza** (sin `ofertaId`
queda sin imputar), igual que el resto de los campos del `PUT`. La huella de idempotencia suma la
oferta **sólo si viene**, para que un reintento de un pedido anterior a 0.132.0 no se lea como reuso de
clave. Sin índice nuevo: la rentabilidad recorta por `ix_egreso_sede_estado`.

**Rentabilidad** — sección `rentabilidad-por-servicio` (`billing`, `cobro:register`):

| Lado | Fuente | Recorte |
|---|---|---|
| Ingresos | `obligacion` por oferta (la misma consulta de la sección de ingresos, sumada sobre los cuatro conceptos) | `devengada_en`, sin `ANULADA` ni baja lógica = `devengado` del económico |
| Egresos | `egreso` por `oferta_id` | `registrado_en`, `CONFIRMADO` o `PAGADO` = `egresos-confirmados` del económico |

Devengado contra devengado: un egreso confirmado es costo aunque no se haya pagado, así que el ingreso
que se le opone es el devengado y no el cobrado. Indicadores: `rentabilidad-ingresos`,
`rentabilidad-egresos-imputados`, `rentabilidad-egresos-sin-imputar`,
`rentabilidad-margen-de-servicios` (ingresos − imputados) y `rentabilidad-resultado` (margen − sin
imputar). Filas: servicio, naturaleza, oferta, ingresos, egresos imputados, resultado; al final la
fila `(sin imputar)` con ingresos cero. **Lo sin imputar no se reparte entre servicios**: eso sería
una regla de costeo indirecto que DP-39 no da. La advertencia se mudó de `ingresos-por-servicio` a esta
sección y ahora es condicional.

**Caja por servicio** — sección `caja-por-servicio` (`billing`, `cobro:register`):

- Universo: los cobros de la sede con `cobrado_en` en el período, sin anulados = el indicador `cobrado`
  del económico. Dos consultas crudas por cobro (`cobro_medio` agrupado por medio, `cobro_imputacion` →
  `obligacion.oferta_id`); el reparto lo hace Java al leer y **no se persiste**.
- Por cada cobro, los pesos son lo imputado a cada oferta **más lo no imputado** (saldo a favor, o lo
  ya reintegrado) como una parte `sin imputar`. Los pesos suman el total del cobro. **Cada medio** se
  reparte con esos pesos por **mayor resto** (`billing.domain.ProrrateoAlCentavo`), así que cada medio
  cierra exacto, el total de la sección es exactamente lo cobrado y una oferta puede diferir en un
  centavo de su cobrado imputado (el redondeo cierra por medio, no por fila). El desempate sigue el
  orden de los ids, y el mismo reporte da siempre los mismos centavos en las mismas filas.
- **Fecha de corte**: el período recorta por `cobrado_en`; las imputaciones se leen hasta `generadoEn`
  del reporte. Un saldo a favor de septiembre imputado en octubre pasa, en el reporte de septiembre
  generado en octubre, de "sin imputar" al servicio. El criterio de cada indicador lo dice.
- Indicadores: `servicios-caja-cobrado`, `servicios-caja-imputado`, `servicios-caja-sin-imputar`.
  Filas: servicio, naturaleza, oferta, total y una columna por medio de pago.

Ejemplo, que es el del IT: un cobro de 1.200 (100 efectivo, 1.100 transferencia) que imputa 600 a
kinesio, 400 a Pilates y deja 200 a favor. Efectivo: 50,00 · 33,333… · 16,666… → 50,00 · 33,33 ·
**16,67**. Transferencia: 550,00 · 366,666… · 183,333… → 550,00 · **366,67** · 183,33. Por fila: 600,00 ·
400,00 · 200,00.

### 7.3 Design challenge

1. **Ownership.** Ninguna tabla nueva. `egreso` es de `billing` y sólo `billing` la toca; la oferta se
   valida por `offering.spi`. Las dos secciones viven en `billing` porque leen `obligacion`, `cobro`,
   `cobro_medio`, `cobro_imputacion` y `egreso`, todas suyas.
2. **Ciclos.** Ninguna arista nueva: `billing → offering.spi` ya existía (09.01b). `ModuleArchitectureTest`
   en verde.
3. **Tenant.** La columna nueva hereda el `organization_id` de su fila; la FK no garantiza el tenant y
   por eso la validación por `OfertaDirectory#find` filtra organización **y** sede. Las consultas nuevas
   filtran `organization_id` y sede, y los joins de `cobro_imputacion` y `obligacion` repiten el
   `organization_id`. El IT prueba que otro tenant no puede imputar a una oferta ajena (404) y que su
   egreso no suma.
4. **Reglas maestras.** Obligación ≠ cobro ≠ caja: la rentabilidad opone **devengado** a egresos, y la
   caja por servicio reparte **cobros** (M19), no movimientos de caja (M20): no incluye ingresos
   manuales ni arqueos, y lo dice en su javadoc. El anticipo no se le atribuye a ningún servicio: es
   una parte propia, la misma frontera que `servicios-anticipos-sin-imputar`.
5. **Baja lógica.** Sólo un `ALTER ... ADD COLUMN` nullable; ninguna fila existente cambia de sentido
   (todas quedan sin imputar). Nada se borra.
6. **Contrato.** Aditivo: `ofertaId` opcional en `RegistrarEgreso`, `EditarEgreso` y `Egreso`, y un
   404 documentado más en alta y corrección. Las secciones y los indicadores nuevos viajan en el
   esquema genérico del reporte, sin cambio de schema. Un matiz que se declara: un cliente anterior que
   **corrige** un borrador imputado no manda `ofertaId` y lo deja sin imputar, porque el `PUT`
   reemplaza. Hoy no hay borradores imputados (la columna nace en 0.132.0).
7. **Ruta crítica.** Los cimientos existen: egreso (M22, 07.05), oferta (M27), cobro con medios e
   imputaciones (M19, F-3). Los honorarios por clase (DP-38, U-41) **no** son cimiento: cuando generen
   egresos con oferta, entran a la rentabilidad sin tocar este código.
8. **El caso que rompe el diseño.** *Un cobro de dos medios que paga dos ofertas y deja saldo a favor*,
   con cuotas que no son centavos exactos: repartir medio por medio redondeando cada parte pierde o
   inventa un centavo, y repartir sólo entre las deudas le asigna el anticipo a servicios que no lo
   recibieron. Lo resuelven el mayor resto por medio (la suma cierra exacta) y la parte `sin imputar`
   como un peso más; el IT lo compara contra los números escritos a mano de arriba. *Otro:* una
   imputación posterior del saldo a favor cambia el reparto de un período ya pasado; se acepta, se
   declara la fecha de corte (`generadoEn`) y no se persiste ninguna foto que pueda divergir.

### 7.4 Verificación

`ContinuidadYServiciosEnElReporteIT#rentabilidad_y_caja_por_servicio` contra MySQL: el cobro del
ejemplo; egresos de 250 (kinesio) y 150 (Pilates) confirmados, 1.000 sin oferta, 999 imputado y
anulado y 777 en borrador; otro tenant con su egreso y un intento de imputar a una oferta ajena (404).
Compara cada indicador y cada fila contra valores escritos a mano, y concilia contra el económico:
`cobrado` 1.200 = caja, `egresos-confirmados` 1.400 = imputados + sin imputar, `devengado` 1.500 =
ingresos. Unitarios: `ProrrateoAlCentavoTest`, `CajaPorServicioEnElReporteTest` y el `@Nested` de
imputación de `EgresoServiceTest`.
