# AKINE E-6 — Prepago de recepción como anticipo (DP-06 / ADR-0013)

**07/10/2026** · rama `akine-E-6-prepago-anticipo` · migración `V81` · contrato `0.66.0` · ficha
[F5](../fases/F5-agenda-y-recepcion.md) · paquete E-6 de
[01-trabajo-en-paralelo](../fases/01-trabajo-en-paralelo.md) (parte backend).

## 1. Qué se cubre y de dónde sale

| Fuente | Qué dice | Cómo se cubre |
|---|---|---|
| **DP-06 / ADR-0013** | El prepago es una política configurable **por Consultorio y por Oferta**, nunca una condición global del dominio clínico. El dinero recibido antes de la obligación es **anticipo/saldo a favor** con movimiento real de caja; cuando nace la obligación, el anticipo **se imputa** a una compatible | Todo este documento |
| ADR-0013 "Qué obliga a hacer" | La política se define en la configuración de la Oferta (M27) y se evalúa en recepción (`scheduling`), no en el dominio clínico. `billing` es dueño del ledger | §2, §3 |
| Plan, etapa 05.04, reglas | "Una política de prepago puede ofrecer cobro administrativo, pero su incumplimiento **no impide iniciar ni cerrar** la atención clínica" | §3: alerta sin bloqueo |
| Plan, etapa 05.04, CA | "La clínica no depende del cobro y cualquier prepago queda **separado como anticipo hasta su imputación**" | §4, §5 |
| RN-M13-003 | Un faltante puede advertir sin bloquear | El prepago pendiente es una advertencia más de la recepción |
| RN-M14-005, 07.01 | El cierre clínico no depende del cobro; la deuda se devenga dentro del cierre | §5: la imputación corre **después** del commit del cierre |
| F-3 (M19) | Anticipo, imputación posterior, anulación, reintegro | Se reusa entero; E-6 sólo ata el anticipo a un turno |
| F-4 (M18) | El cierre devenga PARTICULAR, o FINANCIADOR + COSEGURO | §5: se imputa sólo a la deuda del **paciente** |

No hay un RF que diga "la recepción bloquea sin prepago". Por eso la opción es la conservadora:
**alertar sin bloquear**.

## 2. Dónde vive la política

**Columna `oferta_servicio_consultorio.exige_prepago`** (`offering`, M27), `NOT NULL DEFAULT 0`.

- La Oferta **ya es** "cómo presta ESTE consultorio ESTE servicio" (RN-M27-002). Una columna en la
  oferta es literalmente "configurable por Consultorio y por Oferta": dos sedes que prestan el mismo
  servicio deciden distinto.
- **No** se agrega un default por consultorio en `organization`. Sería sólo una comodidad para
  configurar todas las ofertas de una sede de un saque, tocaría `consultorio` (otro carril) y obliga
  a decidir precedencias (¿la oferta pisa a la sede o al revés?) sin un RF que las defina. Queda como
  decisión a revisar (§9).
- Se cambia con un recurso propio, **`PUT /consultorios/{c}/ofertas/{o}/politica-de-prepago`**
  (`updatePoliticaDePrepagoDeOferta`), con `consultorio:manage` y control optimista, auditado como
  `OFERTA_POLITICA_PREPAGO_CHANGED`. No se sumó al `PUT` de la oferta: es configuración del
  mostrador, no de la prestación, y así no se toca el comando de edición que otros paquetes (B-3)
  están modificando.
- Viaja a los otros módulos en `offering.spi.PrecioDeOferta.exigePrepago`, la proyección
  **económica** de la oferta. No en `OfertaSnapshot`, que a propósito no lleva nada económico.

## 3. La recepción: alerta, nunca bloqueo

La recepción muestra el estado del prepago en `Recepcion.prepago` (`PrepagoDeRecepcion` en el
contrato), **calculado al leer** —nunca persistido en la recepción, que sería una segunda copia que
diverge en cuanto alguien anula el anticipo—:

```
hay un anticipo vigente con este turnoId      -> REGISTRADO (cobroId, importe, saldoAFavor)
la oferta no exige prepago                    -> NO_EXIGIDO
la recepción está ANULADA o CERRADA           -> NO_EXIGIDO
la recepción se resolvió con COBERTURA        -> NO_EXIGIDO
cualquier otro caso                           -> PENDIENTE, con el precio particular sugerido
```

- **Pasar a espera con el prepago PENDIENTE está permitido.** Lo único que cambia es que el evento
  `ESPERA` de `recepcion_evento` queda con motivo `PREPAGO_PENDIENTE: …`: consta que la persona pasó
  a la sala sin el anticipo que el centro exige. Ninguna otra transición (llamar, anular) ni la
  Sesión miran el prepago.
- **Con cobertura no se exige.** El flujo histórico que ADR-0013 reconoce es cobrarle al
  **particular** antes de pasarlo a la sala. Con cobertura paga el financiador y lo del paciente es
  el coseguro, que depende del arancel del convenio y que la recepción no cotiza. Registrar un
  anticipo de coseguro igual se puede (se imputa al coseguro que devengue el cierre); lo que no hay
  es alerta. Decisión a revisar (§9).
- El importe sugerido es el `precio_base` de la oferta. Es una sugerencia: el importe lo decide
  quien cobra.

**Cómo pregunta `scheduling` a `billing` sin cerrar un ciclo.** `billing → encounter → scheduling`
ya existe, así que `scheduling` no puede importar `billing`. Se usa la inversión de siempre:
`scheduling.spi.PrepagoDeTurnoProbe` (declara la pregunta, en lote para la agenda del día) y
`billing.infrastructure.PrepagosDeTurnoEnCobros` la implementa. Mismo patrón que `AtencionProbe`.

## 4. El registro del prepago

Es el **registro de cobro de F-3**, `POST /consultorios/{c}/cobros`, con un campo nuevo y opcional:
**`turnoId`**. No hay endpoint nuevo en la recepción: el prepago lo cobra quien tiene
`cobro:register` (y la caja, si es efectivo), con las reglas de siempre.

| Regla | Respuesta si no se cumple |
|---|---|
| Anticipo **puro**: sin imputaciones y `anticipo = total` | 400 `validation-error` (`CobroInvalidoException`) |
| El turno es de la sede del cobro y del tenant (`scheduling.spi.TurnoDirectory`) | 404 `not-found` |
| El turno es de la misma persona que paga | 409 `prepago-no-admitido` |
| El turno es todavía una reserva viva (`RESERVADO` o `CONFIRMADO`) | 409 `prepago-no-admitido` |
| No hay otro prepago **vigente** en el turno | 409 `prepago-ya-registrado` (con `cobroId`) |

- **Caja**: idéntica a cualquier cobro. El efectivo exige caja abierta y mueve el arqueo; los demás
  medios dejan su movimiento sin tocar el arqueo.
- **Un solo prepago vigente por turno** lo sostiene la base:
  `uk_cobro_prepago_turno_vigente (organization_id, turno_prepago_vigente)`, con
  `turno_prepago_vigente` columna generada = `turno_id` mientras `deleted_at` es NULL. Anular el
  prepago (F-3) libera el turno para cobrar otro. El chequeo previo da el 409 amable; la carrera de
  dos operadores la frena el unique, y el `DataIntegrityViolationException` de ese unique se
  traduce al mismo 409.
- **Idempotencia**: la de F-3. El `turnoId` entra en la huella sólo cuando viene, así los
  reintentos de cobros anteriores no cambian de huella.
- No se exige recepción abierta para prepagar: la recepción no prueba nada (DP-05) y un centro
  puede cobrar el prepago al reservar por teléfono.

## 5. La imputación al cierre

`SesionCerrada` gana **`turnoId`** (encounter, cambio aditivo del `spi`). Un nuevo observador del
cierre, `billing.infrastructure.PrepagoAlCierreDeSesion`, **no hace nada dentro del cierre**: anota
una sincronización `afterCommit`. Después del commit, `billing.application.ImputacionDePrepago`
corre en su **propia transacción** (`REQUIRES_NEW`, `READ_COMMITTED`):

1. prepago vigente del turno (id, no entidad) → **lock** del cobro (`SELECT … FOR UPDATE`, como F-3);
2. si está anulado o sin saldo a favor → nada;
3. la deuda del **paciente** de la sesión (`PARTICULAR` o `COSEGURO`), cobrable, de la misma
   persona y moneda; **nunca** la del financiador (esa se reclama en una presentación, F-4);
4. imputa `min(saldo a favor, saldo de la deuda)` con el primitivo de F-3 (fila en
   `cobro_imputacion`, UPDATE condicional de la deuda que avanza `version`), sin mover caja;
5. auditoría `COBRO_SALDO_IMPUTADO` con `origen = PREPAGO_AL_CIERRE`. El autor es quien cerró.

| Situación | Resultado |
|---|---|
| Anticipo = deuda | Deuda `PAGADA`, anticipo en 0 |
| **Anticipo sobra** | Deuda `PAGADA`, **el resto queda como saldo a favor** (reintegrable o imputable con F-3) |
| **Anticipo no alcanza** | Se imputa entero, la deuda queda `PARCIAL` por la diferencia |
| **Sesión con ausencia** | No hay deuda (07.01) y el observador sale antes: **el anticipo queda disponible** para reintegro o imputación posterior (F-3). Ningún no-show se cobra solo |
| Convenio (financiador + coseguro) | Se imputa al coseguro; lo que sobra queda a favor |
| Prepago anulado antes del cierre | No se imputa nada |
| Sesión sin turno | Nada |
| Prepago registrado **después** del cierre | Queda a favor; se imputa a mano con F-3 |
| La imputación falla (carrera, dato raro) | Se loguea `WARN`; el cierre **ya commiteó** y el anticipo queda a favor |

**Por qué después del commit y no dentro.** La deuda se devenga dentro del cierre (07.01) porque
una prestación sin deuda no se nota. El cobro no: si la imputación corriera adentro, una carrera con
una anulación haría fallar el cierre clínico, que es atar la historia clínica a la caja —lo que
ADR-0013 prohíbe—. Imputar después cuesta poco: en el peor caso el anticipo queda a favor y se
imputa a mano.

## 6. Tablas, migración y contrato

**`V81__e6_prepago_politica_y_anticipo_de_turno.sql`**

| Objeto | Módulo | Cambio |
|---|---|---|
| `oferta_servicio_consultorio` | `offering` | `exige_prepago TINYINT(1) NOT NULL DEFAULT 0` |
| `cobro` | `billing` | `turno_id BIGINT NULL`; `turno_prepago_vigente` generada; `uk_cobro_prepago_turno_vigente (organization_id, turno_prepago_vigente)` |

Sin FK de `cobro.turno_id` a `turno`: es de otro módulo y `billing` guarda la referencia, como
`obligacion.sesion_id`.

**Contrato `0.66.0`, aditivo**: `OfertaResponse.exigePrepago`; operación nueva
`updatePoliticaDePrepagoDeOferta` (`PoliticaDePrepagoRequest`); `RegistrarCobro.turnoId`;
`Cobro.turnoId`; `Recepcion.prepago` (schema `PrepagoDeRecepcion`); problem types
`prepago-no-admitido` y `prepago-ya-registrado`.

## 7. Design challenge (CLAUDE.md §3)

1. **Ownership.** No hay tablas nuevas. `exige_prepago` es de `offering` y sólo la escribe
   `OfertaService`; `cobro.turno_id` es de `billing`. `scheduling` no lee ninguna de las dos: pide la
   política por `offering.spi` y el anticipo por su propio `spi` invertido. `encounter` sólo agrega
   un id a un hecho que ya publicaba.
2. **Ciclos.** Aristas nuevas: `billing → scheduling.spi` (`TurnoDirectory`, y la implementación de
   `PrepagoDeTurnoProbe`). Ya existía `billing → encounter → scheduling`, y `scheduling` no alcanza a
   `billing`. `scheduling → offering.spi` ya existía. `ModuleArchitectureTest` 5/5.
3. **Tenant.** Las columnas nuevas son atributos de filas con `organization_id`. El unique empieza
   por `organization_id`. El turno se resuelve con `find(org, sede, turno)`: un turno de otro tenant
   o de otra sede es 404. El probe filtra por organización (IT: lo que cobró A no se ve desde B).
4. **Reglas maestras.** Deuda ≠ cobro ≠ caja: el prepago no crea deuda (ADR-0013 descarta la
   "obligación anticipada"), la imputación no toca caja, la caja la mueve sólo el cobro. Turno ≠
   Recepción ≠ Sesión: la recepción no cambia de comportamiento por el prepago y la sesión no se
   entera. Cerrar no cobra: la imputación es posterior y no puede hacer fallar el cierre.
5. **Baja lógica.** Nada se borra. Anular un prepago es la anulación de F-3 (flag + reversiones); el
   unique se libera por la columna generada, no por borrar.
6. **Contrato.** Aditivo (§6). Minor `0.66.0`.
7. **Ruta crítica.** Cimientos en `main`: recepción con máquina propia (E-4), anticipos e imputación
   (F-3), devengo con concepto (F-4), política en la oferta (M27). No se adelanta nada.
8. **El caso que rompe el diseño.**
   - *El operador anula el prepago en el mismo instante en que el profesional cierra la sesión.* La
     imputación corre después del commit del cierre y toma el lock del cobro, igual que la
     anulación. Si la anulación gana, la imputación encuentra el cobro anulado y no hace nada (la
     deuda queda pendiente, el dinero ya volvió por la reversión). Si la imputación gana, la
     anulación de F-3 revierte también esa imputación y devuelve la deuda. Nunca queda una deuda
     saldada por un cobro anulado, y **en ningún orden el cierre falla**.
   - *Dos operadores cobran el prepago del mismo turno a la vez.* Los dos pasan el chequeo previo;
     el segundo INSERT choca con `uk_cobro_prepago_turno_vigente` y recibe el mismo 409
     `prepago-ya-registrado`. El cajón recibe una sola vez (`PrepagoDeRecepcionIT`).
   - *El paciente no viene.* El cierre con `AUSENTE` no devenga y el observador sale antes: el
     anticipo queda entero a favor, y el reintegro de F-3 lo devuelve por caja (IT).

## 8. Verificación

- Unitarios: `PrepagoDeRecepcionTest`, `ImputacionDePrepagoTest`, `PrepagoAlCierreDeSesionTest`,
  y casos nuevos en `CobroServiceTest` y `CicloDeRecepcionServiceTest`.
- IT contra MySQL: `PrepagoDeRecepcionIT` (punta a punta en efectivo, sobra por transferencia, no
  alcanza, ausencia con reintegro, pendiente que no bloquea, anulación antes del cierre,
  idempotencia, carrera de dos prepagos, reglas del turno y tenant). Además `RecepcionIT`,
  `AnticipoYAnulacionIT`, `ObligacionDelFinanciadorIT`, `CobroConcurrenteIT`,
  `EsquemaMultiTenantIT` y `OpenApiContractIT`.

## 9. Fuera de alcance y decisiones a revisar

1. **Política sólo en la oferta**, sin default por consultorio (§2).
2. **Alerta sin bloqueo**: ningún RF dice que el prepago bloquee el paso a espera. Si un centro lo
   quisiera bloqueante, sería un modo nuevo de la política, nunca aplicado a la Sesión (DP-06).
3. **Con cobertura no se alerta** (§3). Si se quiere exigir el coseguro por adelantado, la
   recepción tendría que cotizarlo con `contracting.spi.ArancelDirectory`.
4. **Se imputa a una sola deuda**: la del paciente de esa sesión (hay una por el unique de V36).
   El sobrante no se imputa automáticamente a otras deudas viejas del paciente: queda a favor.
5. **La imputación es "mejor esfuerzo" después del commit.** Si falla, queda un `WARN` y el
   anticipo a favor; no hay reintento automático ni bandeja de "prepagos sin imputar".
6. **Prepago sin recepción**: se admite (DP-05). Si se quiere atarlo a una recepción abierta, es
   una regla más en `CobroService`.
7. **La pantalla** (mitad web de E-6) no se hace en este paquete.
