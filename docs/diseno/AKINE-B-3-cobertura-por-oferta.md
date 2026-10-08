# AKINE B-3 — Cobertura aplicable por oferta, arancel por oferta y precio particular por vigencia (M08, M16, M27)

> Paquete **B-3** (parte backend) de `docs/fases/01-trabajo-en-paralelo.md` (ola 2, depende de
> A-9). Cierra los faltantes RF-M08-006/007 y RF-M16-008/009 de `docs/fases/F3-personas-y-cobertura.md`
> y el desvío "arancel por práctica y no por oferta" de la cabecera de `V43`.
> Migración: **`V80`**. Contrato: **0.65.0** (aditivo). RF-M16-007 (importación masiva) queda
> **diseñada y fuera** de este paquete: §7.

## 1. Qué se cubre y de dónde sale

| Requisito | Qué dice | Cómo se cubre |
|---|---|---|
| **RF-M08-006** | Resolver si una cobertura del paciente puede usarse para una Oferta concreta | `GET /api/v1/personas/{personaId}/cobertura-aplicable?ofertaId=&fecha=` (§3) |
| RN-M08-005 | La cobertura no implica que toda Oferta sea facturable | Primer corte del algoritmo: la oferta no admite obra social → ninguna aplica |
| RN-M08-006 | La elegibilidad se resuelve por Oferta, convenio, plan, vigencia y documentación | Oferta (A-9 + `admiteObraSocial`), convenio y plan (M16), vigencia (B-2). La documentación sigue en M17 (`consultarElegibilidadAdministrativa`) |
| **RF-M08-007** | Continuar bajo condición particular sin alterar la cobertura general | La respuesta trae `condicionSugerida` y el **precio particular del día**. La **selección** ya la hace la recepción (E-4, RF-M13-005). Ver el hallazgo de §8 |
| **RF-M16-008** | Asociar una Oferta a un convenio y plan con arancel y coseguro | `convenio_arancel.oferta_id`: el arancel de una práctica **dentro de** una oferta (§4) |
| RN-M16-006 | Los convenios aplican a Ofertas concretas, no sólo a prácticas | El arancel de la oferta manda sobre el general al resolver con oferta |
| CA-M16-008-06 | La cobertura se resuelve por Oferta + plan + convenio vigente | El devengo (F-4), la recepción (E-4) y RF-M08-006 resuelven **con la oferta** |
| **RF-M16-009** | Precio particular base o específico por vigencia | `oferta_precio_particular` (§5): manda sobre `precio_base` el día que cubre |
| RN-M16-007 | Una Oferta mantiene precio particular y arancel financiador a la vez | Son dos tablas de dos módulos y nunca se pisan |
| CA-M16-009-06 | Cambiar el precio de Pilates no modifica obligaciones anteriores | La obligación copia el importe al devengar (07.01). El importe del precio no se edita |

## 2. Lo que A-9 y F-4 dejaron puesto

- `offering.spi.PracticasDeOfertaDirectory` (A-9, DP-11): qué prácticas declara una oferta y cuál es
  la principal.
- `person.spi.CoberturasAplicablesDirectory` (B-2): coberturas FINANCIADAS vigentes con arancel
  resuelto para **una práctica**.
- `ObligacionDevengador` (F-4): con la práctica realizada (o la principal), pregunta a B-2 y congela
  con `ArancelDirectory#congelar`. Las dos llamadas eran **por práctica, sin oferta**.

Lo que faltaba es que la oferta participe de la resolución: hasta B-3 un convenio sólo sabía de
prácticas, y "qué cubre la obra social para esta oferta" no tenía respuesta propia.

## 3. RF-M08-006 — cobertura aplicable por oferta

```
oferta de la sede del contexto                       (404 si no)
prácticas de la oferta (A-9): la principal primero, después en orden de alta
por cada cobertura FINANCIADA vigente (B-2, principal primero):
  la oferta no admite obra social    -> no aplica: OFERTA_NO_ADMITE_OBRA_SOCIAL
  la oferta no declara prácticas     -> no aplica: OFERTA_SIN_PRACTICAS
  por cada práctica: arancel CON oferta (el de la oferta manda sobre el general)
  alguna resolvió                    -> aplica, con la PRIMERA que resolvió
  ninguna                            -> no aplica, con el motivo de la principal
condición sugerida = COBERTURA si alguna aplica; si no PARTICULAR con el precio del día
```

- **Una oferta con varias prácticas** no tiene una sola respuesta posible antes de atender: qué se
  factura lo decide la práctica realizada (DP-11). Por eso se elige con el mismo orden que el devengo
  cuando la sesión cierra sin tratamientos —la principal primero— y viaja el **detalle por práctica**,
  que explica "la principal no está en convenio pero Fonoaudiología sí".
- **Sólo lectura, sin auditoría**, como la elegibilidad administrativa (RF-M17-007) y B-2. Se
  autoriza **por pertenencia**, igual que `GET .../coberturas` (no existe `paciente:read`, decisión
  pendiente 5 del `CLAUDE.md`). Exige sede en el contexto: la oferta y el convenio son de la sede
  (RN-M16-001); sin sede sería 403, nunca "particular".
- El número de afiliado no sale.
- Vive en `person` (dueño de las coberturas): `CoberturaParaOfertaService`, con aristas nuevas
  `person → offering.spi` (§6).

## 4. RF-M16-008 — arancel por oferta dentro del convenio

**Decisión: una columna nullable en `convenio_arancel`, no una tabla nueva.**

```
convenio_arancel.oferta_id
  NULL  = arancel GENERAL de la práctica en el convenio (todo lo que existía, intacto)
  valor = arancel de la práctica cuando se presta DENTRO de esa oferta
```

**Resolución** (`ResolutorDeArancel`, compartido por la pantalla y el `spi`):

```
con oferta:  el arancel de ESA oferta que cubre el día; si no hay, el general
sin oferta:  sólo el general
```

No es un desempate entre candidatas iguales: son dos niveles de especificidad, y dentro de cada uno
sigue habiendo como mucho una candidata. La explicación viaja en `ArancelVigente.ofertaId`
(y en `ArancelEfectivoResponse.ofertaId`): `null` = salió el general.

**No-solapamiento (RN-M16-002)** por `(convenio, práctica, oferta)`, con el general como grupo
propio. El general y el de cada oferta conviven en el mismo período —es el punto—; dos de la misma
oferta, o dos generales, no. Lo hace cumplir **el mismo lock de `convenio_lock`** de 03.05, con las
tres condiciones de siempre. Ningún índice, tampoco acá.

**Validaciones al alta** (por `offering.spi`, arista nueva `contracting → offering.spi`): la oferta es
de la misma sede (404), admite obra social (409 `oferta-sin-obra-social`) y declara la práctica
(409 `practica-no-habilitada-en-oferta`).

**Por qué no una tabla `convenio_oferta`:** la obligación del financiador (V77) congela
`snapshot_arancel_id` y `practica_id`, y al financiador se le factura **por práctica** (código de
nomenclador). Un arancel "de la oferta" sin práctica no se podría presentar, y una segunda tabla de
aranceles obligaba a F-4 a congelar desde dos fuentes. Con la columna, F-4 sigue congelando un
`ConvenioArancel` y el snapshot no cambia de forma.

### 4.1 Qué cambia en F-4 (y por qué no rompe `ObligacionDelFinanciadorIT`)

`ArancelDirectory` y `CoberturasAplicablesDirectory` ganan una sobrecarga con `Long ofertaId`; las
firmas viejas quedan como `default` que delegan con `null` (= sólo generales). Los consumidores que
**conocen la oferta** pasan a usarla:

| Consumidor | Cambio |
|---|---|
| `billing.ObligacionDevengador` (F-4) | `aplicables(..., cierre.ofertaId(), dia)` y `congelar(..., cierre.ofertaId(), dia)` |
| `scheduling.CicloDeRecepcionService` (E-4) | `aplicables(..., turno.getOfertaId(), dia)` |
| `person.CoberturasAplicablesService` (B-2) | pasa la oferta a `ArancelDirectory` |

Sin un arancel de oferta cargado, resolver con oferta da **exactamente** lo mismo que sin ella: por
eso los once escenarios de `ObligacionDelFinanciadorIT` no se tocan y siguen verdes. El escenario
nuevo (`CoberturaPorOfertaIT`) prueba que, con uno cargado, el snapshot congela el de la oferta.

Quedan **por práctica, sin oferta**, a propósito: la elegibilidad administrativa (M17, la pide por
cobertura y práctica) y el snapshot de la autorización. Ver §8, límite 2.

## 5. RF-M16-009 — precio particular por vigencia

`oferta_servicio_consultorio.precio_base` sigue siendo el precio de lista **sin** vigencia. La tabla
nueva `oferta_precio_particular` (módulo `offering`) fija precios **con** vigencia:

```
precio particular del día = el activo que cubre el día; si no hay, precio_base
```

- Una oferta sin filas se comporta como antes de V80.
- **El importe no se edita.** Sólo el fin de la vigencia (cerrar el actual el día antes del nuevo, o
  reabrirlo). Corregir una carga es darla de baja y cargarla de nuevo. Baja lógica con motivo.
- **No-solapamiento** por oferta, con el `SELECT ... FOR UPDATE` sobre la fila de la oferta que A-9
  ya usa (`bloquearParaConfigurar`), tomado **antes** de leer el conjunto y en `READ_COMMITTED`. Sin
  versión ni force-increment de la oferta: el precio no edita la oferta, y obligar a la pantalla de
  precios a conocer su versión acoplaría dos formularios que no se pisan.
- Permiso: `consultorio:manage`, el mismo que configura la oferta. Lectura por pertenencia.
- **El devengo cobra el precio del día del cierre**: `OfertaDirectory#precioEn(org, sede, oferta,
  instante)` resuelve el día local de la sede, y `encounter.SesionService` lo usa en lugar de
  `precioDe` al armar `SesionCerrada`. La obligación lo copia y no lo vuelve a leer (CA-M16-009-06).

Endpoints: `GET`/`POST /api/v1/consultorios/{consultorioId}/ofertas/{ofertaId}/precios-particulares`,
`PUT`/`DELETE .../precios-particulares/{precioId}`.

## 6. Design challenge

1. **Ownership.** `convenio_arancel.oferta_id` es de `contracting` (dueño de la tabla desde V43);
   `oferta_precio_particular` es de `offering`. Ninguno toca la tabla del otro: `contracting` valida
   la oferta por `offering.spi`, `person` lee precio y prácticas por `offering.spi`, y `encounter`
   lee el precio del día por `offering.spi`. La migración es una sola porque las dos mitades nacieron
   juntas; la cabecera de `V80` declara un dueño por tabla.
2. **Ciclos.** Aristas nuevas: `contracting → offering.spi` y `person → offering.spi`. `offering`
   depende sólo de `organization`, `platform` y `resource`, y ninguno de esos llega a `person` ni a
   `contracting`. `ModuleArchitectureTest` 5/5 verde con las aristas puestas (ArchUnit detecta ciclos
   de cualquier longitud, que es la lección de 04.05).
3. **Tenant.** La tabla nueva lleva `organization_id NOT NULL` y su índice empieza por él. La
   columna nueva vive en una tabla que ya lo lleva, y su índice `ix_convenio_arancel_oferta` empieza
   por `organization_id`. Toda consulta de oferta lleva organización **y** sede en el `WHERE`
   (`OfertaDirectory`). `EsquemaMultiTenantIT` verde. El IT de tenant prueba los tres caminos:
   persona ajena (404), oferta ajena al asociar (404) y precio sobre oferta ajena (404, sin fila).
4. **Reglas maestras.** "Cobertura del paciente ≠ Convenio del consultorio" (regla 9): la respuesta
   de RF-M08-006 separa las dos —una cobertura vigente puede no aplicar— y nada escribe en la
   cobertura (CA-M08-007-06, verificado contra la base). Obligación ≠ Cobro: el precio particular es
   una lectura viva que el devengo **copia**; no hay cobro ni caja en este paquete. Turno ≠ Sesión:
   la recepción resuelve con la oferta del turno, el devengo con la de la sesión.
5. **Baja lógica.** Ninguna eliminación física. Precio: baja con motivo y el CHECK de coherencia de
   siempre; el importe es `updatable = false`. Arancel por oferta: misma baja que cualquier arancel.
6. **Contrato.** Aditivo: 0.63.0 → **0.65.0** (el número lo fijó la asignación del paquete; 0.64.0 no lo usa B-3). Campos
   nuevos opcionales (`CreateArancelRequest.ofertaId`, `ArancelResponse.ofertaId`,
   `ArancelEfectivoResponse.ofertaId`, query `ofertaId` en `/aranceles/efectivo`), cinco operaciones
   nuevas y cuatro problem types nuevos (`practica-no-habilitada-en-oferta`, `oferta-sin-obra-social`,
   `precio-particular-solapado`, `precio-particular-inactivo`). Sin versión mayor.
7. **Ruta crítica.** Los cimientos están: A-9 (prácticas de la oferta), B-2 (coberturas aplicables),
   F-4 (devengo con snapshot), E-4 (recepción). B-3 es exactamente el paquete que esperaba a A-9.
8. **El caso que rompe el diseño.**
   - *Dos administradores cargan a la vez el arancel de Pilates-OSDE con períodos que se pisan.* El
     lock de `convenio_lock` serializa y el segundo lee la fila del primero (`READ_COMMITTED`): 409
     `arancel-solapado`. Probado con dos hilos contra MySQL.
   - *Hay arancel de la oferta y general para la misma práctica y fecha.* No es ambigüedad: con oferta
     manda el de la oferta, sin oferta el general, y la respuesta dice cuál salió.
   - *El centro quita la práctica de la oferta o apaga la obra social después de cargar el arancel.*
     El arancel queda como dato que no resuelve —el devengo y RF-M08-006 vuelven a mirar la oferta
     al usarlo— y no como dato que cobra mal.
   - *Cambia el precio de Pilates con sesiones ya cerradas.* La obligación guardó su importe; además
     el importe del precio no se edita. Probado reescribiendo el precio en la base después del cierre.
   - *Cierre a las 22 h en Córdoba.* El precio del día se resuelve con la zona de la sede, no en UTC.

## 7. RF-M16-007 — importación masiva con preview: diseñada, fuera de este paquete

> **Implementada por B-7** (`docs/diseno/AKINE-B-7-importacion-aranceles.md`, contrato 0.71.0)
> siguiendo este diseño, salvo el `Idempotency-Key`: el módulo no lo usa y el reintento queda
> cubierto por invariante.

Entró B-3 sin ella porque el paquete ya cambia tres módulos y tres consumidores de F-4/E-4, y la
importación no comparte código con lo anterior más allá de las validaciones del alta. Diseño para la
etapa que la tome:

- **Alcance:** aranceles de **un** convenio (no convenios enteros): es lo que el centro recibe del
  financiador como planilla cada vez que cambia el nomenclador.
- **Contrato:** `POST /api/v1/consultorios/{id}/convenios/{convenioId}/aranceles/importacion` con
  `modo = PREVIEW | CONFIRMAR` y las filas en JSON (`codigoPractica`, `ofertaId?`, los tres importes,
  `vigenciaDesde`, `vigenciaHasta?`). El frontend parsea la planilla; el backend no recibe archivos.
- **Preview:** valida cada fila con las mismas reglas del alta —práctica visible por código, importes
  que cuadran, vigencia contenida, oferta asociable— y el **solapamiento** contra lo vigente **y entre
  las filas del lote**. Devuelve el resultado por fila (`OK` o el problem type que daría el alta). No
  escribe nada ni toma el lock.
- **Confirmar:** todo o nada, bajo **un** lock de `convenio_lock` (asegurar, bloquear, revalidar
  todo, insertar), en `READ_COMMITTED`. Si una fila falla, 409 con el detalle por fila y cero filas
  escritas (CA-M16-007-04).
- **Reintento** (CA-M16-007-05): idempotente por invariante —reintentar un lote ya aplicado choca
  contra sus propias filas como `arancel-solapado` en el preview— y, si se quiere un 200 en vez de un
  409, `Idempotency-Key` con hash del cuerpo, que es el escenario 7b todavía diferido.
- **Auditoría:** un evento por lote con la cantidad y el rango, más el `ARANCEL_CREATED` de cada fila.

## 8. Límites y decisiones a revisar

1. **La decisión "Particular" de la recepción no llega a la obligación** (ya declarado como fuera de alcance en el registro de E-4; B-3 lo confirma contra RF-M08-007). E-4 registra
   `ModalidadRecepcion.PARTICULAR` con motivo (RF-M13-005), pero `SesionCerrada` no lo lleva y
   `ObligacionDevengador` no lo mira: si la cobertura aplica, el cierre devenga la parte del
   financiador aunque el paciente haya elegido atenderse como particular. RF-M08-007 pide que "la
   selección particular se refleje en la obligación económica". Arreglarlo cruza `scheduling.spi`
   (exponer la modalidad de la recepción del turno), `encounter` (copiarla en `SesionCerrada`) y
   `billing` (devengar particular), que son los módulos de los paquetes en vuelo E-6 y del cierre.
   **No se tocó en B-3**; queda declarado para decidir quién lo toma.
   **Cerrado por E-7** (`docs/diseno/AKINE-E-7-particular-en-obligacion.md`), por ese mismo camino.
2. **Elegibilidad y autorizaciones siguen por práctica.** Si un convenio tiene arancel **sólo** para
   una oferta y no general, `consultarElegibilidadAdministrativa` (y la recepción, que la usa) dirá
   "sin arancel → sin requisitos" para esa práctica, aunque el devengo facture el convenio. Los
   requisitos son del convenio, no del arancel, así que la consecuencia es pedir de menos, no cobrar
   mal. Cerrarlo es agregar la oferta a `ElegibilidadAdministrativaDirectory`.
3. **Una cobertura aplica con la primera práctica que resuelve.** Con tratamientos distintos de la
   principal el devengo puede usar otra práctica. Se eligió informar el detalle por práctica en vez de
   inventar un agregado sin RF.
4. **El precio particular no cambia `PrecioDeOferta`.** No dice si salió de un precio por vigencia o
   del de lista; la pantalla lo puede ver en la grilla. Agregarlo es un campo más si hace falta.
5. **Sin pantalla.** B-3 es "api + web"; el frontend regenera el cliente contra 0.65.0 en su rama.
