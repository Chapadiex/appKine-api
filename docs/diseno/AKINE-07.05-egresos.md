# AKINE-07.05 — Egresos y pagos a profesionales (M22)

Diseño de la etapa. **El design challenge (`AKINE-07.05-challenge.md`) manda sobre este documento
donde discrepen.**

Trazabilidad: RF-M22-001..005, RN-M22-001..003, RF-M24-006 (relacionar operación y reversión);
RF-M25 para comprobantes — cubierto **parcialmente**, ver §10.
Depende de **AKINE-07.03** (caja diaria, en la rama de la que esta sale) y de **AKINE-02.03**
(colaboradores), las dos cerradas en el backend.

---

## 1. El punto de la etapa, en una frase

**Del lado del egreso también son tres cosas.**

Del lado del paciente, este repositorio ya separó `Obligación ≠ Cobro ≠ Caja` (reglas maestras 6,
7 y 8). Del lado del profesional pasa exactamente lo mismo y con las mismas consecuencias:

| | Del paciente | Del profesional |
|---|---|---|
| **Lo que se debe** | `obligacion` — la deuda del paciente con el centro | **`egreso`** — la deuda del centro con el beneficiario |
| **El acto de saldar** | `cobro` — alguien pagó | **`pago_egreso`** — el centro pagó |
| **El hecho monetario** | `movimiento_caja` de tipo `INGRESO` | `movimiento_caja` de tipo **`EGRESO`** |

La tentación es colapsarlas en una: "un egreso es sacar plata de la caja". Eso es *literalmente*
lo que 07.03 ya entrega (`RF-M20-003`, movimiento manual de tipo `EGRESO`), y no alcanza para M22
por tres razones que se pueden nombrar:

1. **Una liquidación se debe antes de pagarse.** Se cierra el mes, se calcula lo que le toca al
   kinesiólogo, y se le paga el 10. Entre esas dos fechas el centro **debe** esa plata. Si la única
   entidad es el movimiento de caja, esa deuda no existe en ninguna parte y el reporte de "cuánto
   le debo a cada profesional" no se puede escribir.
2. **El pago parcial está declarado como caso borde de la etapa.** Un movimiento de caja no tiene
   saldo: si el egreso *es* el movimiento, pagar 60.000 de una liquidación de 100.000 produce un
   hecho que no sabe que le faltan 40.000.
3. **El comprobante y el período son del compromiso, no del movimiento.** La factura B 0001-00012
   por el período 01/09–30/09 identifica *lo que se debe*. Si se pagó en dos veces, los dos
   movimientos comparten ese comprobante y ninguno de los dos es dueño de él.

Todo lo que sigue se deriva de eso.

---

## 2. Qué NO es un egreso de M22

Declarado por adelantado porque es donde se arruina el modelo:

- **No es una obligación del paciente al revés.** `egreso` **no tiene `persona_id`, ni
  `sesion_id`, ni `obligacion_id`, ni `cobro_id`.** RN-M22-003 dice que pagar a un profesional no
  modifica sesiones históricas, y la forma fuerte de cumplirlo es que el modelo **no tenga cómo**
  nombrarlas. La liquidación se calcula afuera —hoy, por una persona— y entra como un importe
  declarado con un concepto y un período.
- **No es una regla remunerativa.** El plan lo dice con todas las letras: *"sin inventar regla
  remunerativa"*. Esta etapa **no** calcula el 40 % de lo facturado, ni por sesión, ni por hora,
  ni por clase. RF-M22-006 y RF-M22-007 —base de pago por clase o actividad— son de la **segunda
  entrega** (M28/M29) y no están en el alcance.
- **No es un movimiento manual de caja.** RF-M20-003 sigue existiendo y sirve para lo que sirve:
  sacar 3.000 pesos del cajón para comprar café. Un egreso de M22 tiene beneficiario, categoría,
  comprobante y un circuito de confirmación y anulación propio.

---

## 3. El ciclo de vida del egreso, y por qué tiene borrador

```
              confirmar                pagar (n veces)
  BORRADOR ─────────────▶ CONFIRMADO ─────────────────▶ PAGADO
     │                        │   ▲                         │
     │ anular                 │   └──── anular pago ────────┘
     ▼                        ▼ anular
  ANULADO                  ANULADO (sólo sin pagos vigentes)
```

**`BORRADOR` no es decoración de CRUD.** Es la única fase donde el egreso se puede editar, y existe
porque la liquidación de un profesional se arma mirando papeles: se carga el importe, se busca la
factura, se corrige el período. Sin borrador, cada corrección sería una anulación más un alta
nueva, y el histórico se llenaría de anulaciones que no son errores sino tipeo.

**`CONFIRMADO` es el punto de no retorno.** A partir de ahí:

- el egreso **no se edita más**, por ningún camino (409 `egreso-no-editable`);
- recién ahí admite pagos;
- el beneficiario, el importe y el comprobante quedan **congelados**.

**`PAGADO`** es `saldo_pendiente = 0`, derivado, igual que `EstadoObligacion.PAGADA`.

**`ANULADO`** conserva la fila entera con `motivo_anulacion` (RN-M22-002: *anular no significa
borrar*). Un egreso con pagos vigentes **no se anula**: 409 `egreso-con-pagos`, con `yaPagado` como
propiedad del Problem Detail, exactamente como `ObligacionConCobrosException` de 07.01. Primero se
anulan los pagos —que es lo que devuelve la plata a la caja—, después el compromiso.

### Dónde impacta la caja, y la lectura de RN-M22-001

RN-M22-001 dice *"egreso confirmado afecta caja"*. **Lo que mueve la caja es el pago, no la
confirmación**, y la diferencia importa:

- Si confirmar sacara la plata del cajón, **un egreso que se debe y todavía no se pagó no podría
  existir** — y eso es la mitad del objetivo de la etapa (§1).
- Y el **pago parcial**, que la propia etapa declara como caso borde a resolver, sería
  irrepresentable: no se puede sacar media vez la plata.

Una regla que vuelve inexpresable un caso borde declarado por el mismo documento se está leyendo
mal. La lectura que se adopta, y que conserva el propósito entero de la regla:

> **Sólo un egreso confirmado puede mover la caja. Un borrador no afecta nada.**

Se hace cumplir por construcción: `POST /egresos/{id}/pagos` exige `estado = CONFIRMADO`, y no hay
ningún otro camino por el que un egreso toque la caja.

---

## 4. El beneficiario: dos formas, un solo snapshot

`tipo_beneficiario` vale `COLABORADOR` o `EXTERNO`.

| | `COLABORADOR` | `EXTERNO` |
|---|---|---|
| Quién es | Un vínculo de la organización: el kinesiólogo, la recepcionista | El contador, la inmobiliaria, la empresa de limpieza |
| Referencia | `beneficiario_membership_id` **NOT NULL** | NULL |
| Validación al alta | La membership existe en el tenant y está habilitada | Nombre obligatorio |

`CHECK ((tipo_beneficiario = 'COLABORADOR') = (beneficiario_membership_id IS NOT NULL))`.

### Por qué membership y no `account_id`

Por lo mismo que V23 y V28: **la misma persona puede ser profesional en un centro y administrativa
en otro**, y lo que identifica al beneficiario dentro de esta organización es el vínculo, no la
cuenta global. Se resuelve por `organization.spi.ConsultorioMembershipDirectory`, que `billing` ya
puede consumir.

### `beneficiario_nombre` es un snapshot congelado

Se copia al crear el egreso —de `platform.spi.identity.AccountIdentityDirectory` para el
colaborador, del cuerpo para el externo— y **no se vuelve a resolver nunca**. Es el mismo criterio
que `obligacion.snapshot_nombre` en V36: una liquidación de septiembre tiene que poder leerse en
marzo sin depender de que la membership siga existiendo ni de que la persona no se haya casado.

### El caso borde "profesional desvinculado"

**La membership se valida al crear el borrador y no se vuelve a validar nunca más.** Es deliberado
y es la respuesta correcta: si el profesional se fue el 30 de septiembre, **el centro le sigue
debiendo septiembre**. Confirmar y pagar un egreso cuyo beneficiario ya no está vinculado tiene que
funcionar, porque lo contrario convertiría una desvinculación en una forma de no pagar.

Lo que sí se impide es **crear** un egreso nuevo contra una membership que no está habilitada:
`beneficiario-no-vinculado`, 409. Eso es un error de carga, no una deuda vieja.

---

## 5. El comprobante, y la validación "duplicado"

Tres columnas en `egreso`: `comprobante_tipo`, `comprobante_numero`, `comprobante_fecha`.

- **Opcionales en `BORRADOR`, obligatorias para confirmar** (tipo + número). Un egreso confirmado
  sin respaldo documental es plata que salió sin papel, y es exactamente lo que la validación de la
  etapa pide. Si falta al confirmar: **400** `egreso-sin-comprobante` —el estado del servidor está
  perfecto, falta un campo—, mismo criterio que `caja-diferencia-sin-motivo` en 07.03.
- **`beneficiario_clave`** es una columna común, no generada: `"M:<membershipId>"` para el
  colaborador y `"E:<documento o nombre normalizado>"` para el externo. Existe para una sola cosa:
  que el unique del comprobante pueda incluir *de quién* es el comprobante. Dos proveedores
  distintos emiten legítimamente su propia `FACTURA_B 0001-00000123`.
- `UNIQUE (organization_id, beneficiario_clave, comprobante_tipo, comprobante_numero, anulado_key)`
  resuelve el caso borde **"factura externa duplicada"**: la misma factura del mismo beneficiario
  no se carga dos veces. `anulado_key` es el centinela `'1970-01-01 00:00:00'` sobre `anulado_en`
  —el mismo mecanismo de `deleted_key` en V30— para que **anular y rehacer** siga siendo posible:
  si no, un error de carga dejaría ese número de factura quemado para siempre.
- Con `comprobante_numero` NULL el unique no participa: varios NULL no colisionan en MySQL, y un
  borrador sin factura todavía es legítimo.

### Lo que el comprobante **no** es todavía

**No hay archivo adjunto.** RF-M22-003 pide "vincular documentación del egreso"; esta etapa entrega
la **referencia** (tipo, número y fecha) y **no** el binario. La razón está declarada y tiene
nombre: `person` (V40) y `clinical` (V46) tienen cada uno su propio puerto de storage y su propio
adaptador de sistema de archivos, y el challenge de 04.02 dejó escrita la condición de salida —
*"si aparece un tercer consumidor, se extrae a `platform.spi`"*. Esta etapa **es** ese tercer
consumidor. La extracción toca dos módulos cerrados y es una etapa propia; hacerla de contrabando
acá, junto con el modelo entero de M22, es cómo se rompen dos verticales que hoy funcionan.

Queda anotado en §12 y es **una decisión pendiente del usuario**: si RF-M22-003 se declara cubierto
por la referencia documental, o si se abre la etapa de extracción del storage.

---

## 6. El pago, y el saldo que no puede pasar de cero

`pago_egreso` es el acto de saldar, total o parcialmente, un egreso confirmado.

- **Un medio por pago.** A diferencia del cobro —donde el paciente paga mitad efectivo mitad
  tarjeta en un mismo acto de mostrador—, pagarle a un profesional mitad en efectivo y mitad por
  transferencia son **dos hechos distintos**, con dos comprobantes distintos y probablemente dos
  días distintos. Modelarlo como un pago con varios medios obligaría a una tabla hija que nunca
  tendría más de una fila.
- `referencia VARCHAR(80)` guarda el número de transferencia o de recibo. Es lo que hace
  conciliable un pago que no es en efectivo.
- **Pago parcial:** se admite cualquier importe menor o igual al saldo. Varios pagos van llevando
  `saldo_pendiente` a cero.
- **Sobrepago: imposible por construcción.**

El descuento es el `UPDATE` condicional que este repositorio ya usa tres veces —imputar un cobro
(07.02), consumir una autorización (04.05) y mover el saldo de la caja (07.03)—:

```sql
UPDATE egreso
   SET saldo_pendiente = saldo_pendiente - :importe
 WHERE id = :egresoId AND organization_id = :org
   AND estado IN ('CONFIRMADO')
   AND saldo_pendiente >= :importe;
```

**Cero filas es la respuesta, no un error técnico.** Sin lock, sin lectura previa, sin ventana
entre leer y escribir, y sin deadlock posible. Cero filas se desambigua releyendo el egreso —seguro,
porque un `UPDATE` de cero filas no marca la transacción para rollback—: si el estado ya no admite
pago, 409 `egreso-no-pagable`; si es el saldo, 409 `egreso-saldo-insuficiente` con el saldo actual.

El estado se deriva en una segunda sentencia, **con SQL y no leyendo la entidad**, por la trampa que
este repositorio ya pagó: después de un `UPDATE` nativo, la copia que la sesión de JPA tiene en
memoria sigue teniendo el saldo viejo.

```sql
UPDATE egreso SET estado = CASE WHEN saldo_pendiente = 0 THEN 'PAGADO' ELSE 'CONFIRMADO' END
 WHERE id = :egresoId AND estado IN ('CONFIRMADO', 'PAGADO');
```

### `version` en `egreso` sí, y sin contradecir a `jornada_caja`

`jornada_caja` no tiene `@Version` porque todas sus escrituras son SQL nativo y no la
incrementarían: un token optimista que no avanza cuando el dato cambia promete una protección que
no da.

`egreso` sí la tiene, y las dos decisiones conviven porque **las dos fases no se superponen**:

- `version` gobierna **sólo** la fase `BORRADOR`, donde las escrituras son ediciones por JPA y
  dos administrativos sí pueden pisarse.
- El `UPDATE` nativo del saldo ocurre **sólo** en `CONFIRMADO`, donde el egreso ya no se edita.

Un borrador no tiene pagos y un confirmado no se edita, así que la versión y el saldo nunca corren
la misma carrera. **Y no hay `OPTIMISTIC_FORCE_INCREMENT` en ninguna parte**: la recíproca que
04.02 pagó —si el padre además queda sucio, la versión avanza dos veces y el cliente come un 409
del que no puede salir— haría exactamente eso acá, porque las escrituras del saldo tocan columnas
del propio `egreso`.

---

## 7. El pago mueve la caja, en la misma transacción

`CajaDeEgreso` es el gemelo de `CajaDeCobro`, en `billing.application`, y reusa el **mismo**
primitivo: `MovimientoCajaService.asentar(...)`, con `TipoMovimiento.EGRESO` y un origen nuevo,
`OrigenMovimiento.PAGO_EGRESO`.

**No se crea un segundo camino para que salga plata.** Es el punto que esta etapa tenía más fácil de
arruinar: una tabla `egreso_movimiento` propia, o un `UPDATE` directo sobre `jornada_caja`, harían
que el arqueo dejara de ser la suma de un solo ledger y que `saldo_arqueo` tuviera dos dueños.

Lo que se hereda de 07.03, tal cual, sin excepciones nuevas:

| Regla de 07.03 | Cómo cae en el pago a un profesional |
|---|---|
| Sólo `EFECTIVO` afecta el arqueo | Pagar por transferencia asienta su movimiento, se lista y se totaliza, y **no toca el cajón** |
| El efectivo exige jornada abierta | Pagar en efectivo sin caja abierta → 409 `caja-no-abierta`. La plata sale del cajón igual; si el sistema no sabe de qué jornada, el arqueo de ese día no cuadra contra nada |
| **El saldo de la caja nunca queda negativo** | `restarDelSaldo` lleva `AND saldo_arqueo >= :importe`, y `ck_jornada_caja_saldo_no_negativo` lo respalda. Un pago que no entra en el cajón **se rechaza** con 409 `caja-saldo-insuficiente`; no deja la caja en rojo |
| El importe es siempre positivo; el signo lo da el tipo | `EGRESO` resta. Nada cambia |
| El ledger es append-only | El movimiento del pago no se edita ni se borra nunca |

**`pago_egreso` NO tiene `movimiento_caja_id`**, y es la misma decisión que 07.03 tomó para el
cobro: *es el movimiento el que apunta al pago, no al revés*. `movimiento_caja.tipo_origen =
'PAGO_EGRESO'` con `referencia_origen = pago_egreso.id`, y el unique
`uk_movimiento_caja_origen (organization_id, tipo, tipo_origen, referencia_origen, medio)` que ya
existe hace que **un pago produzca a lo sumo un movimiento**, gratis. Además evita la columna
nullable que el orden de inserción obligaría a tener (el `id` del pago no existe antes de
insertarlo).

---

## 8. La anulación: dos operaciones, porque son dos hechos

RF-M22-005 pide *"crear reversión trazable y corregir caja"*. Eso son dos cosas distintas y la
etapa entrega las dos por separado, porque **anular el compromiso no es lo mismo que deshacer el
pago**:

### `POST /egresos/{id}/anulacion` — anula el compromiso

No toca la caja, porque un egreso que sólo se debía nunca movió plata. Exige motivo. **Rechaza con
409 `egreso-con-pagos`** si queda algún pago vigente, y el Problem Detail lleva `yaPagado` para que
la pantalla pueda decir "anulá primero los 60.000 que ya pagaste" en vez de "no se puede".

### `POST /egresos/{id}/pagos/{pagoId}/anulacion` — deshace el pago y corrige la caja

Dos efectos, en la misma transacción:

1. **La plata vuelve al cajón**, como una fila propia de tipo `REVERSION_DE_EGRESO` en
   `movimiento_caja`, con motivo obligatorio y `movimiento_origen_id` apuntando al original. Lo
   hace **`MovimientoCajaService.revertir`**, el mismo método que 07.03 ya escribió: no hay un
   camino de reversión nuevo, y de regalo vienen sus dos garantías —**un movimiento se revierte una
   sola vez** (unique) y la compensación **cae en la jornada abierta hoy**, nunca reescribiendo una
   jornada ya arqueada—.
2. **El saldo vuelve al egreso**: `saldo_pendiente + importe`, y el estado vuelve de `PAGADO` a
   `CONFIRMADO`.

El pago **no se borra**: queda con `estado = ANULADO`, `anulado_en`, `anulado_por_cuenta_id` y
`motivo_anulacion`. RN-M22-002.

> **Consecuencia que hay que declarar:** revertir una reversión no existe. Si alguien anula un pago
> por error, el camino es registrar el pago de nuevo, que es honesto sobre lo que pasó. Es la regla
> de 07.03 y no se toca.

### Un defecto heredado de 07.03 que esta etapa destapa y corrige

`AKINE-07.03-caja.md` §7 dice, textual: *"si no hay jornada abierta **y el movimiento a revertir era
en efectivo**, la reversión se rechaza"*. **El código no hace eso**: `MovimientoCajaService.revertir`
llama a `jornadaAbierta(...)` incondicionalmente y rechaza **cualquier** reversión sin caja abierta,
incluida la de una transferencia — plata que nunca tocó el cajón.

No se notó en 07.03 porque ahí las reversiones nacían de movimientos manuales, que ya exigen caja
abierta. Acá sí se nota: **anular un pago hecho por transferencia no puede depender de que alguien
haya abierto el cajón**.

Se corrige en `revertir`, alineando el código con su propio diseño: la jornada se exige **sólo
cuando el movimiento original era en efectivo**, exactamente como `asentar` ya lo hace. Es un
cambio de comportamiento de una operación de 07.03 que **amplía** lo que se acepta, nunca lo que se
rechaza — ningún caso que hoy funciona deja de funcionar.

---

## 9. Esquema — `V57`

Dos tablas nuevas y **un solo `ALTER`** sobre una tabla del mismo módulo.

### `egreso`

`organization_id`, `consultorio_id`, `categoria VARCHAR(32)`,
`tipo_beneficiario VARCHAR(16)`, `beneficiario_membership_id BIGINT NULL`,
`beneficiario_clave VARCHAR(80)`, `beneficiario_nombre VARCHAR(160)`,
`beneficiario_documento VARCHAR(32) NULL`,
`periodo_desde DATE NULL`, `periodo_hasta DATE NULL`, `concepto VARCHAR(280)`,
`importe_total`, `saldo_pendiente`, `moneda CHAR(3)`, `estado VARCHAR(16)`,
`comprobante_tipo`, `comprobante_numero`, `comprobante_fecha`,
`registrado_en/por`, `confirmado_en/por`, `anulado_en/por`, `motivo_anulacion`,
`anulado_key DATETIME(6) AS (IFNULL(anulado_en,'1970-01-01 00:00:00')) STORED`,
`idempotency_key`, `request_hash`, `version`, `created_at`, `updated_at`.

Importes `DECIMAL(12,2)`, como toda la economía del repositorio.

- `UNIQUE (organization_id, beneficiario_clave, comprobante_tipo, comprobante_numero, anulado_key)` — §5.
- `UNIQUE (organization_id, idempotency_key)` — igual que `cobro` (V37) y `movimiento_caja` (V54).
- `CHECK` de estado, `CHECK (importe_total > 0)`, `CHECK (saldo_pendiente >= 0)`,
  `CHECK (saldo_pendiente <= importe_total)`.
- `CHECK` de beneficiario: el `tipo` y la presencia de `beneficiario_membership_id` se implican.
- `CHECK` de período: los dos NULL o los dos presentes, y `desde <= hasta`.
- `CHECK` de confirmación completa y de anulación completa, cada uno con sus campos todos o
  ninguno, mismo criterio que `ck_obligacion_anulacion_completa` (V36) y
  `ck_jornada_caja_cierre_completo` (V54).
- `CHECK (estado <> 'BORRADOR' OR saldo_pendiente = importe_total)` — un borrador no tiene pagos.
- `CHECK` de idempotencia completa (clave y hash viajan juntos).
- Índices: `(organization_id, consultorio_id, estado, registrado_en)` para la bandeja;
  `(organization_id, beneficiario_clave, estado)` para "cuánto le debo a este profesional";
  `(organization_id, consultorio_id, periodo_desde, periodo_hasta)` para el filtro por período.
- **Sin baja lógica con el cuarteto `active`/`deleted_at`**: el ciclo de vida no es alta/baja, es
  borrador/confirmado/anulado, igual que `jornada_caja`. Nada se borra por ningún camino.

### `pago_egreso`

`organization_id`, `consultorio_id`, `egreso_id`, `importe`, `moneda`, `medio VARCHAR(24)`,
`referencia VARCHAR(80) NULL`, `fecha_negocio DATE`, `estado VARCHAR(16)`,
`pagado_en/por`, `anulado_en/por`, `motivo_anulacion`,
`idempotency_key`, `request_hash`, `version`, `created_at`, `updated_at`.

- `organization_id` en la hija **no es negociable** aunque se llegue por `egreso_id`: ADR-0004 dice
  "sin excepción", y una consulta que se olvide del JOIN cruzaría tenants sin fallar. Misma
  convención que `cobro_medio` (V37) y `movimiento_caja` (V54).
- `UNIQUE (organization_id, idempotency_key)`.
- `CHECK (importe > 0)`, `CHECK` de estado, `CHECK` de anulación completa, `CHECK` de idempotencia.
- Índice `(organization_id, egreso_id, pagado_en)`.
- **Sin `movimiento_caja_id`** — §7.

### El `ALTER` sobre `movimiento_caja`

`ck_movimiento_caja_tipo_origen` hoy acepta `('COBRO','MANUAL','REVERSION')`. Se reemplaza para
admitir `'PAGO_EGRESO'`. Es el **único** cambio sobre el esquema de 07.03, es aditivo —ningún valor
existente deja de ser válido— y es sobre una tabla **del mismo módulo**, `billing`.

---

## 10. API — contrato `0.39.0`

Ocho operaciones, todas bajo `/api/v1/consultorios/{consultorioId}/egresos`. **Aditivas**: ningún
path existente cambia de forma y ningún campo se quita ni se renombra.

| Verbo | Ruta | RF |
|---|---|---|
| `POST` | `/egresos` | RF-M22-001 alta (nace `BORRADOR`) |
| `PUT` | `/egresos/{egresoId}` | RF-M22-001 editar el borrador |
| `POST` | `/egresos/{egresoId}/confirmacion` | RF-M22-001 confirmar |
| `POST` | `/egresos/{egresoId}/anulacion` | RF-M22-005 anular el compromiso |
| `GET` | `/egresos` | RF-M22-004 filtros: fecha, categoría, beneficiario, estado |
| `GET` | `/egresos/{egresoId}` | RF-M22-004 detalle con sus pagos |
| `POST` | `/egresos/{egresoId}/pagos` | RF-M22-002 registrar pago |
| `POST` | `/egresos/{egresoId}/pagos/{pagoId}/anulacion` | RF-M22-005 reversión que corrige caja |

**Ninguna responde 204 sin cuerpo**, así que la trampa del `produces` —la que rompió activación de
cuenta y recuperación de contraseña— no aplica. Se declaran `application/json` y
`application/problem+json` igual, por consistencia con `CajaController` y `CobroController`.

**Tipos de problema nuevos** en `platform.spi.problem.ProblemType`, mapeados en
**`BillingProblemHandler`** —el advice del propio módulo, nunca en `GlobalExceptionHandler`—:

| Tipo | HTTP | Cuándo |
|---|---|---|
| `egreso-no-editable` | 409 | Editar algo que ya no es borrador |
| `egreso-no-confirmable` | 409 | Confirmar algo que ya no es borrador |
| `egreso-sin-comprobante` | **400** | Confirmar sin tipo y número de comprobante |
| `egreso-ya-anulado` | 409 | Anular dos veces |
| `egreso-con-pagos` | 409 | Anular un egreso con pagos vigentes (lleva `yaPagado`) |
| `egreso-no-pagable` | 409 | Pagar un borrador, un anulado o uno ya saldado |
| `egreso-saldo-insuficiente` | 409 | El pago excede el saldo (lleva `saldoDisponible`) |
| `egreso-comprobante-duplicado` | 409 | Ese comprobante de ese beneficiario ya está cargado |
| `pago-egreso-ya-anulado` | 409 | Anular dos veces el mismo pago |
| `beneficiario-no-vinculado` | 409 | Crear un egreso contra una membership no habilitada |

Se **reusan** `NOT_FOUND` (cross-tenant → 404, nunca 403), `IDEMPOTENCY_KEY_CONFLICT`,
`CAJA_NO_ABIERTA`, `CAJA_SALDO_INSUFICIENTE`, `CAJA_MONEDA_DISTINTA` y `MOVIMIENTO_NO_REVERSIBLE`,
que llegan intactos desde 07.03 a través de `asentar` y `revertir`.

Los `paths` del YAML **no se editan a mano**: se regeneran. Sin Docker, `OpenApiContractIT` queda en
drift, que es deuda ya declarada del repositorio y no de esta etapa.

---

## 11. Permisos

**`caja:operate`**, el mismo que 07.03. **No se crea un permiso nuevo, y es una decisión.**

La matriz mínima de §32 tiene doce filas y **ninguna dice "Registrar Egreso"**. La fila más cercana
es *Operar Caja* (`No | Sí | Sí | No | Sí | No`), y sus titulares —`ORG_ADMIN`,
`CONSULTORIO_ADMIN` y `ADMINISTRATIVO`— son exactamente los actores que M22 declara: *Administrativo*
y *Administrador*. Inventar `egreso:manage` sería inventar una fila de la matriz.

Es el mismo precedente que 07.02 dejó escrito para `cobro:register`: *"separar 'ver deuda' de
'cobrar' exigiría un permiso más que la matriz §5 no tiene"*.

> La matriz dice también *"el backend debe implementar permisos más granulares"*, así que la puerta
> está abierta. **Queda como decisión pendiente del usuario**: si M22 merece su propio
> `egreso:manage`. Cambiarlo después es agregar un código al catálogo y una línea a
> `CajaAcceso` — no toca el modelo.

`PROFESIONAL` **no** lo tiene, y acá importa más que en 07.03: sin esa exclusión, un profesional
podría cargarse a sí mismo una liquidación.

**Alta, confirmación, pago, anulación de egreso y anulación de pago se auditan con `AuditTrail`,
dentro de la transacción de negocio** (eventos `EGRESO_REGISTRADO`, `EGRESO_CONFIRMADO`,
`EGRESO_ANULADO`, `EGRESO_PAGADO`, `EGRESO_PAGO_ANULADO`). La consulta no se audita: no es acceso
clínico y el volumen la volvería ruido.

---

## 12. Lo que esta etapa NO hace

Declarado para que nadie lo busque ni lo dé por hecho.

- **No adjunta archivos.** §5. Esta etapa sería el tercer consumidor del storage duplicado y la
  extracción a `platform.spi` es una etapa propia. **Decisión pendiente del usuario.**
- **No calcula liquidaciones.** Ni por sesión, ni por hora, ni por clase, ni por porcentaje. El
  importe lo declara una persona. RF-M22-006 y RF-M22-007 son de la segunda entrega.
- **No prohíbe períodos superpuestos.** Dos liquidaciones del mismo profesional para el mismo mes
  son legítimas —una corrección, un segundo concepto—, y un unique sobre el período convertiría un
  caso normal en un error. El caso borde declarado se responde **mostrándolos**: el filtro por
  beneficiario y período los pone uno al lado del otro.
- **No numera los egresos.** Ningún RF pide un correlativo de egreso, y un numerador es una fila
  de lock más y un `UPDATE ... ultimo_numero + 1` más. Si M23 lo pide para un reporte, se agrega
  entonces, con la regla del repositorio: `UPDATE ... ultimo_numero + 1`, nunca `MAX+1`, y la
  idempotencia evaluada **antes** de pedir número.
- **No modela cuentas bancarias ni concilia tarjetas.** Sigue siendo lo que 07.03 dejó afuera.
- **No hay frontend.** El cliente TypeScript se genera desde el contrato y el contrato no se puede
  regenerar sin Docker.
- **No hay tests de integración.** Docker no arranca. Lo que sólo se puede probar contra MySQL real
  queda en `docs/tests-diferidos.md` con etapa destino.

---

## 13. Bloques de implementación

1. **`V57`** — `egreso`, `pago_egreso` y el `ALTER` del `CHECK` de `tipo_origen`.
2. **Dominio** — `Egreso`, `PagoEgreso`, `EstadoEgreso`, `EstadoPagoEgreso`, `CategoriaEgreso`,
   `TipoBeneficiario`, excepciones y puertos.
3. **Persistencia** — `EgresoRepository` y `PagoEgresoRepository`, con los `UPDATE` condicionales.
4. **Aplicación** — `EgresoService`, `PagoEgresoService` y `CajaDeEgreso`; el arreglo de
   `MovimientoCajaService.revertir` (§8).
5. **API** — `EgresoController`, DTOs, tipos de problema y handlers.
6. **Contrato** — `0.39.0` en `pom.xml`, `application.yml` y el `info.version` del YAML. Los
   `paths` no se tocan.
7. **Tests unitarios** — un solo archivo, con los casos que la etapa exige y nada más.
