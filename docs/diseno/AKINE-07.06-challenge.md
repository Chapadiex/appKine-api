# AKINE-07.06 — Design challenge

Revisión adversarial del diseño de `AKINE-07.06-reportes.md`. **Escrita antes del código**, como
exige §3 de `CLAUDE.md`. Las ocho preguntas, respondidas por escrito.

Regla que gobierna este documento: **una respuesta que no se puede comprobar no es una respuesta.**
El challenge de 04.05 dio por verificada de memoria una arista entre módulos que sí cerraba ciclo,
y lo destapó una clase sonda. Acá la pregunta 2 se responde con sonda y con la salida de ArchUnit
pegada, no con un razonamiento.

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Desde dónde lee cada reporte?

**No hay ninguna tabla nueva.** `reporting` no tiene capa `infrastructure` y `V59` no contiene un
solo `CREATE TABLE`: son dos `CREATE INDEX` sobre tablas cuyos propietarios siguen siendo
`encounter` (`sesion`) y `clinical` (`caso_clinico`).

> Un índice no cambia el ownership. No expone un camino de lectura nuevo, no agrega una fila, no
> puede desincronizarse. Es la misma verdad ordenada para poder leerla.

**Desde dónde lee cada reporte:** ninguno lee nada por su cuenta. `reporting` **no tiene un solo
repositorio**. Cada sección la calcula el módulo propietario de la tabla, con su propio puerto y
sus propias reglas de qué estado suma:

| Sección | La calcula | Lee de | Propietario de esas tablas |
|---|---|---|---|
| `turnos` | `scheduling.infrastructure.TurnosEnElReporte` | `turno` | `scheduling` |
| `sesiones` | `encounter.infrastructure.SesionesEnElReporte` | `sesion` | `encounter` |
| `casos` | `clinical.infrastructure.CasosEnElReporte` | `caso_clinico` | `clinical` |
| `economia` | `billing.infrastructure.EconomiaEnElReporte` | `obligacion`, `cobro`, `cobro_medio`, `movimiento_caja`, `jornada_caja`, `egreso` | `billing` |
| `financiadores` | `billing.infrastructure.FinanciadoresEnElReporte` | `obligacion`, `presentacion`, `financiador_pago` | `billing` |

**Veredicto: satisfactoria.** Ownership intacto y reforzado: el diseño hace estructuralmente
imposible que `reporting` toque una tabla ajena, porque no tiene con qué.

---

## 2. Ciclos — ¿la dependencia entre módulos es unidireccional? ¿Pasa ArchUnit?

**Respondida con sonda, no de memoria.** Se crearon cinco clases desechables que fijan exactamente
las aristas del diseño y se dejó hablar a `ModuleArchitectureTest`.

### Aristas del diseño

```
scheduling ─┐
encounter  ─┤
clinical   ─┼──▶ reporting.spi          (los módulos fuente implementan el contributor)
billing    ─┘

reporting ──▶ organization.spi          (PermissionGuard, PermissionEvaluator, ConsultorioDirectory)
reporting ──▶ platform.spi.audit        (AuditTrail)
```

### Corrida 1 — el diseño tal cual

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- ModuleArchitectureTest
BUILD SUCCESS
```

**Las cinco reglas en verde, `sin_ciclos_entre_modulos` incluida.**

### Corrida 2 — el reflejo, para ver que ArchUnit lo agarra

Se agregó **una sola línea**: un campo `CasoDirectory` en el servicio de `reporting`, que es
exactamente lo que haría cualquiera que implementara el reporte "tirando" de los módulos en vez de
recibiendo de ellos.

```
Cycle detected: Slice clinical ->
                Slice reporting ->
    - Class <com.akine.clinical.infrastructure.SondaReporteDeclinical>
      implements interface <com.akine.reporting.spi.SondaReporteContributor>
  2. Dependencies of Slice reporting
    - Field <com.akine.reporting.application.SondaReporteService.casos>
      has type <com.akine.clinical.spi.CasoDirectory>

Tests run: 5, Failures: 1 -- sin_ciclos_entre_modulos
BUILD FAILURE
```

**El reflejo cierra un ciclo y la regla lo detecta.** No era una preocupación teórica: era la
implementación que sale sola si nadie mira. Con cinco módulos fuente el riesgo era cinco veces el
del Paciente 360, que ya había tomado esta misma decisión por esta misma razón.

La sonda se revirtió y las clases se reemplazan por el código real, que mantiene idéntico el
conjunto de aristas.

> `SlicesRuleDefinition` busca ciclos de **cualquier longitud**; la cabeza encuentra los de dos.
> Por eso se comprueba y no se razona.

**Veredicto: satisfactoria, con evidencia de las dos direcciones.**

---

## 3. Tenant — ¿toda consulta filtra por `organization_id`?

Es la peor falla posible de un producto multi-tenant y el reporte es donde más fácil se cuela,
porque una agregación que se olvida del filtro no falla: **devuelve un número más grande.** Nadie
lo nota.

Cuatro defensas, en capas:

1. **`organizationId` no es un parámetro de la API.** Sale de `OperatingActor`, que lo toma del
   contexto del request. **No hay forma de pedirle a esta API el reporte de otra organización: el
   id no viaja.**
2. **`consultorioId` sí viaja, en la ruta, y se valida** contra `ConsultorioDirectory.find(org, id)`.
   Sede de otro tenant → **404**, nunca 403 (un 403 confirma que existe y bastaría probar ids
   consecutivos para enumerar las sedes del SaaS).
3. **`ConsultaDeReporte` lleva `organizationId` como campo obligatorio** y es lo único que un
   contribuyente recibe. No hay una sobrecarga sin tenant que alguien pueda llamar por error.
4. **Cada consulta de agregación empieza por `organization_id`**, y los dos índices de V59 también.

**Veredicto: satisfactoria.**

> **Lo que queda sin verificar y se declara:** que las consultas realmente filtren sólo se prueba
> con dos tenants y datos en MySQL real. Es el escenario diferido **49**. Docker está caído.

---

## 4. Reglas maestras — los cinco conceptos económicos

La pregunta cara de la etapa, y tiene dos caras.

### Cara A — ¿materializa algo?

**No. Nada.** Ni una tabla, ni una columna derivada, ni una proyección refrescada por job.

La defensa contra el precedente, que es lo que el diseño estaba obligado a escribir:

| Precedente | Qué decidió | ¿Por qué aplica acá? |
|---|---|---|
| 02.04 — disponibilidad efectiva | se calcula al leer | Un reporte es, literalmente, la misma clase de objeto: un derivado de hechos que ya están escritos. |
| 03.02 — Paciente 360 | se calcula al leer, por contribuyentes | Es el mismo problema de agregación multi-módulo, con dos fuentes en vez de cinco. |
| 04.02 — timeline clínico | *"una tabla de resumen es una segunda copia de la verdad"* | Palabra por palabra lo que sería una tabla de KPI diario. |
| 06.03 — comparación de mediciones | se calcula al leer | — |

**El argumento contra materializar no es de pureza, es de falla.** Una tabla de resumen se
desincroniza el día que alguien escribe por otro camino, y en este dominio los caminos que escriben
son muchos y llegan de a poco: el devengado de financiador se va a recablear, 06.04 va a cambiar
cómo avanza el plan, y una anulación retroactiva de un cobro toca un período ya "cerrado". Cada uno
de esos cambios sería, con agregados materializados, **un backfill que alguien tiene que acordarse
de correr**. Cuando no se acuerda, el tablero no se rompe: miente.

**Condición de salida, explícita y revisable.** Se materializa cuando exista **una medición, contra
un dataset representativo y contra MySQL real, que muestre que un reporte del MVP no entra en el
tiempo interactivo con los índices de V59**. Ese día, lo que se materializa no es un KPI: es una
tabla de **hechos** append-only por día ya cerrado, invalidada por el mismo evento que hoy
invalidaría el número, y con el período abierto siempre calculado al leer. Hoy esa medición **no
existe y no se puede tomar**: Docker está caído. Sin medición no hay cache, porque un cache que
nadie midió es sólo una copia más.

### Cara B — ¿suma cosas que no se suman?

Deuda, cobro, caja, presentación y egreso son **cinco**. El diseño:

- publica **nueve indicadores económicos con nueve `fuente` declaradas**;
- **no publica ningún total que los sume**, y lo escribe como prohibición explícita para las etapas
  futuras;
- la única combinación de dos fuentes es la **reconciliación**, que es una **resta que debería dar
  cero** entre `SUM(cobro_medio)` con medio efectivo y `SUM(movimiento_caja)` de origen `COBRO`, y
  que viaja con ese nombre y esa expectativa.

> El error concreto que esto evita: un campo "ingresos totales" que sume `cobrado` +
> `caja-ingresos` cuenta **dos veces** cada cobro en efectivo, porque el movimiento de caja de
> origen `COBRO` *es* ese cobro visto desde el cajón. La relación además no es uno a uno —un cobro
> con dos medios produce dos movimientos, uno con tarjeta produce uno que no afecta el arqueo—, así
> que ni siquiera se puede "dividir por dos" para arreglarlo.

**Otras reglas maestras:** el reporte no confunde Turno con Sesión (`turnos` cuenta reservas,
`sesiones` cuenta atenciones realizadas, en dos secciones con dos fuentes), y RN-M23-004 se cumple
contando `sesiones-con-caso` y `sesiones-sin-caso` por separado en vez de mezclarlas.

**Veredicto: satisfactoria.**

---

## 5. Baja lógica — ¿los reportes cuentan o excluyen lo dado de baja?

Las dos respuestas son defendibles. **No decidirlo no lo es.** Decisión:

> **Lo anulado y lo dado de baja se excluye de los totales y se cuenta en su propio indicador.**

| Tabla | Total | Indicador propio |
|---|---|---|
| `obligacion` | excluye `ANULADA` y `deleted_at IS NOT NULL` | `devengado-anulado` |
| `cobro` | excluye `deleted_at IS NOT NULL` | — (07.02 todavía no anula cobros) |
| `turno` | excluye `deleted_at IS NOT NULL` | `turnos-cancelados` es un **estado**, no una baja: cuenta |
| `egreso` | excluye `ANULADO` | `egresos-confirmados` sólo `CONFIRMADO`/`PAGADO` |
| `presentacion` | excluye `ANULADA` | — |
| `movimiento_caja` | **no excluye nada**: es append-only | las reversiones son `tipo` propio y se cuentan aparte |

El porqué de la forma, en una línea: **lo que desaparece del reporte es inauditable, y lo que suma
es incorrecto.** Un indicador propio es lo único que cumple las dos cosas a la vez, y además
responde el caso de QA "correcciones posteriores": alguien que ve el mes pasado con 3 obligaciones
anuladas entiende por qué el total bajó.

`movimiento_caja` es la excepción correcta y no una inconsistencia: **es un ledger append-only que
se compensa, no se edita.** Excluir una reversión rompería el arqueo, que es su razón de ser.

**Veredicto: satisfactoria.**

---

## 6. Contrato — ¿el cambio de API es aditivo?

**Aditivo puro.** Tres paths nuevos bajo `/api/v1/consultorios/{id}/reportes`, ninguno existente
modificado, ningún schema existente tocado, ningún `operationId` renombrado.

`0.40.0 → 0.41.0`, minor. Se declara en `pom.xml`, `application.yml` y el `info.version` del YAML.

> **Los `paths` del YAML no se editan a mano**: se regeneran con
> `./mvnw verify -Dakine.contract.update=true`, que es un test de integración y **necesita Docker**.
> `OpenApiContractIT` queda en drift. Es esperado, está declarado y **no se tapa editando el
> archivo**: un YAML retocado a mano deja de ser un gate y pasa a ser una opinión.

Un `operationId` que vale la pena mirar: springdoc desambigua solo los duplicados y **el sufijo se
mueve** cuando aparece un método nuevo. Los tres de esta etapa —`catalogoDeReportes`,
`generarReporte`, `exportarReporte`— no colisionan con nada del repositorio.

**Veredicto: satisfactoria.**

---

## 7. Ruta crítica — ¿tiene sus cimientos?

Reportes es el **último eslabón** de la ruta crítica de `AGENT.md` §9. Los cinco módulos fuente
existen, tienen migraciones y tienen API. Esta vez no se está adelantando ningún módulo, que es
justamente el error estructural n.º 1 del plan.

### Lo que se puede construir hoy

RF-M23-001 (dashboard operativo), RF-M23-002 (turnos), RF-M23-003 (clínico), RF-M23-004
(económico), RF-M23-006 (export CSV).

### Lo que depende de algo que todavía no existe — y por eso no se construye

| RF | Falta |
|---|---|
| RF-M23-007 ocupación de clases | módulo `activity` (M28) |
| RF-M23-008 continuidad del circuito | módulo `activity` (M28) |
| RF-M23-009 rentabilidad por servicio | **no hay modelo de costeo.** `egreso` no se liga a oferta ni a servicio: su beneficiario es un colaborador o un externo y su categoría es contable. Imputar costo por servicio sería inventar el dato |
| RF-M23-010 pases y abonos | módulo `activity` (M29) |

### Lo que se construye sabiendo que devuelve cero — y lo dice

**RF-M23-005.** No existe ninguna obligación con `responsable = FINANCIADOR` y nada la produce:
`ObligacionDevengador` devenga una sola obligación a nombre del paciente, y el enchufe que `V36`
reservó y `V56` completó nunca se conectó. Es la decisión pendiente del usuario que 07.04 ya había
declarado, y su consecuencia medible allá fue que la bandeja de prestaciones elegibles devuelve
lista vacía en un despliegue real.

Acá la consecuencia es más ancha, porque arrastra a toda la cadena:

```
sin obligación de financiador
   → sin prestación elegible          (07.04 ya lo declaró)
   → sin presentación con items
   → prestado = presentado = facturado = debitado = pendiente = 0
```

La estructura se construye igual —el día que el devengado se recablee, el reporte da números sin
tocar una línea— y **emite la advertencia `sin-devengado-de-financiador` cuando sus totales son
cero**. No se entrega un tablero que en producción muestra ceros sin explicar por qué.

**Veredicto: satisfactoria, con dos huecos declarados y uno de ellos elevado al usuario.**

---

## 8. El caso que rompe el diseño

Hay tres candidatos y conviene nombrarlos a los tres, porque el que rompe el diseño no es el que
uno espera.

### Candidato descartado — la misma plata contada dos veces

Ya resuelto en la pregunta 4: no existe ningún campo que sume conceptos distintos. Se descarta
porque **el diseño lo hace imposible, no difícil.**

### Candidato descartado — el 21:30 en Ushuaia

Un cobro registrado a las 21:30 hora local cae, con la zona del servidor, en el día siguiente. Es
el mismo error que `CajaAcceso.fechaDeNegocio` ya evita para la caja. El reporte mezcla dos clases
de columna y ahí está la trampa real:

- **instantes** (`cobro.cobrado_en`, `obligacion.devengada_en`, `sesion.cerrada_en`, `turno.inicio`)
  → se comparan contra una ventana `[desdeInstante, hastaInstante)` **ya convertida** con
  `consultorio.timezone`;
- **fechas de negocio** (`movimiento_caja.fecha_negocio`, `jornada_caja.fecha_negocio`) → ya son
  locales y se comparan directo contra `desde`/`hasta`.

`ConsultaDeReporte` lleva **las cuatro cosas** —`desde`, `hasta`, `desdeInstante`,
`hastaInstante`— precisamente para que ningún contribuyente tenga que hacer la conversión por su
cuenta y elija mal. Y cada indicador declara en `criterioDeFecha` cuál de las dos usó.

### **El que rompe el diseño: el reporte que muestra un número que el actor no debería poder deducir**

Es el peor porque **no se ve**. El diseño recorta por sección: quien no tiene `hc:read` no ve la
sección de casos. Pero un `ADMINISTRATIVO` **sí** tiene `cobro:register`, y con eso ve la sección
económica. La matriz de permisos dice, textualmente, que ese rol ve *"solo reportes operativos y de
caja del consultorio, **sin contenido clínico**"*.

Ahora: `obligacion` lleva `persona_id` y `snapshot_nombre`, que es el nombre comercial de la oferta.
Una fila de detalle económico por persona y por oferta permitiría deducir **qué prestación recibió
quién** — que es contenido clínico reconstruido desde la economía, sin haber pasado por `hc:read`
ni haber dejado un acceso auditado.

**Cómo lo resuelve el diseño, en tres reglas que no son negociables:**

1. **Las secciones económicas aportan indicadores agregados y filas agregadas por concepto, nunca
   por persona.** El CSV del reporte económico **no lleva una columna `personaId`**. Quien necesita
   el detalle por paciente entra por la cuenta corriente de M18, con su permiso y su auditoría.
2. **La sección clínica aporta conteos y, a lo sumo, un `caso_id`.** Ni diagnóstico, ni evolución,
   ni nota de cierre, ni nombre.
3. **Todo reporte que incluye una sección clínica se audita**, dentro de la transacción, con
   `AuditTrail`. Es la regla de 04.01 aplicada a una lectura agregada: lo que la regla protege es
   el **acceso**, no el volumen.

**Lo que esto NO resuelve, y se eleva como decisión del usuario:** la matriz dice que un
`PROFESIONAL` ve *"solo reportes de su propia actividad"*. Eso es el alcance **`OWN`**, que **no
está implementado en ninguna parte del repositorio** —es el mismo agujero que hace que una
membership con rol `PACIENTE` lea el padrón entero— y cuya causa raíz es que no hay vínculo entre
cuenta y persona. Filtrar el reporte por `profesional_membership_id` del actor sería *una* versión
de `OWN`, inventada acá y distinta de la que resuelva el problema en serio.

La matriz misma declara el *"catálogo definitivo de restricciones Limitado en reportes"* como hueco
abierto con **etapa destino F8**. Esta etapa **no lo cierra**, lo respeta y lo dice.

**Veredicto: satisfactoria, con una decisión del usuario elevada.**

---

## 9. Hallazgo fuera de alcance — `tipo_origen` perdió `PAGO_FINANCIADOR` en el merge de F7

Encontrado al leer `V56` y `V57` para saber de dónde lee el reporte de financiadores. **No es de
esta etapa y no se arregla acá**, pero callarlo sería peor.

`V56` (07.04) reemplaza el check de `movimiento_caja.tipo_origen` para admitir un valor nuevo:

```sql
CHECK (tipo_origen IN ('COBRO', 'MANUAL', 'REVERSION', 'PAGO_FINANCIADOR'));
```

`V57` (07.05) hace exactamente lo mismo para *su* valor nuevo, y **reescribe el check entero
tomando como base la versión anterior a V56**:

```sql
CHECK (tipo_origen IN ('COBRO', 'MANUAL', 'REVERSION', 'PAGO_EGRESO'));
```

`V57` corre después. **El estado final de la base no acepta `PAGO_FINANCIADOR`**, así que el
movimiento de caja del pago de un financiador —07.04, `FinanciadorPagoService`— **es rechazado por
un CHECK de MySQL**.

Es, otra vez, **el merge limpio que no compila**: dos ramas paralelas, dos `ALTER` "aditivos" sobre
**el mismo constraint**, en archivos distintos. Git no tiene nada que resolver y cada migración es
correcta leída sola.

**Por qué no lo detectó nadie:** las dos etapas declararon su `ALTER` como aditivo y lo era —contra
su propia base de partida—. Y **ninguna migración de F7 se aplicó jamás contra un motor**, porque
Docker está caído: `V54`, `V56` y `V57` nunca corrieron.

**Arreglo, cuando se decida:** un `ALTER` que reponga el check con los cinco valores
(`COBRO`, `MANUAL`, `REVERSION`, `PAGO_FINANCIADOR`, `PAGO_EGRESO`). Cabe en `V59`.

**No se hace acá.** `V59` es la migración de 07.06 y meterle el arreglo de otra etapa la vuelve un
cajón de sastre; además `V58` está reservada por una etapa en vuelo que podría ser la dueña natural
del arreglo. **Es una decisión del usuario.**

> Lección que generaliza, y que vale para todo `ALTER` futuro: **un `ALTER` que reemplaza un
> `CHECK` no es aditivo aunque agregue un valor.** Es un reemplazo total, y dos ramas que lo hagan
> en paralelo se pisan sin conflicto de git. El equivalente de esquema del `test-compile` después
> del merge es **aplicar las migraciones de las dos ramas en orden y mirar el estado final**.

---

## 10. Veredicto general

| # | Pregunta | Veredicto |
|---|---|---|
| 1 | Ownership | Satisfactoria — ninguna tabla nueva; `reporting` no tiene repositorios |
| 2 | Ciclos | Satisfactoria — **comprobada con sonda en las dos direcciones** |
| 3 | Tenant | Satisfactoria — el `organizationId` no viaja en la request. Verificación real diferida (49) |
| 4 | Reglas maestras | Satisfactoria — no materializa nada; cinco conceptos, cinco fuentes, ningún total |
| 5 | Baja lógica | Satisfactoria — se excluye del total y se cuenta aparte |
| 6 | Contrato | Satisfactoria — aditivo puro, 0.41.0. Drift esperado y declarado |
| 7 | Ruta crítica | Satisfactoria — último eslabón; cuatro RF no se construyen y uno se declara en cero |
| 8 | El caso que rompe | Satisfactoria — resuelto en tres reglas; `OWN` elevado al usuario |

**Se avanza a `tasks`.**

### Decisiones elevadas al usuario

1. **El recableado del devengado a `responsable = FINANCIADOR`.** Ya era una decisión pendiente de
   07.04; esta etapa muestra que arrastra el reporte de financiadores entero a cero.
2. **El alcance `OWN` en reportes** (`PROFESIONAL` ve sólo su actividad). Hueco declarado de F8 en
   la matriz de permisos. No se inventa acá.
3. **El `CHECK` de `tipo_origen` que `V57` dejó sin `PAGO_FINANCIADOR`.** Defecto real, ajeno a
   esta etapa, que rompe el pago de financiadores de 07.04 contra MySQL real.
