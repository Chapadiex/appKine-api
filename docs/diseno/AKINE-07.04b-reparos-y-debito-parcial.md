# AKINE-07.04b — Reparos documentales (RF-M21-003) y débito parcial

> **Propuesta, no decisión.** Implementa las dos opciones *recomendadas* de
> `docs/fases/decisiones-pendientes.md` (**U-28** y **U-11**) y de la ficha F7 (n.º 3 y n.º 4) en
> un PR en borrador para que el usuario decida con el código a la vista. Migración **`V95`**,
> contrato **0.100.0**.

## 1. Qué se decide y qué se implementa

| Decisión | Recomendada (lo que este PR hace) | Alternativas que quedan afuera |
|---|---|---|
| U-28 · RF-M21-003 | Falta orden o autorización que el convenio **exigía el día de la prestación** → **reparo** (bloquea confirmar). Credencial que el convenio exigía y estaba vencida (o la cobertura no tiene número de afiliado) → **aviso** (se confirma igual) | Todo aviso; todo reparo |
| U-11 · débito parcial | Conciliar salda lo **cobrado** (presentado − debitado) y deja pendiente **sólo lo debitado**, re-presentable en otro lote | Pasarlo al paciente o darlo de baja como pérdida — lo decide el administrativo, no el sistema |

## 2. RF-M21-003 — orden, autorización y credencial

### 2.1 Qué se mira

F-4 congeló en cada obligación del financiador **qué exigía** el convenio
(`snapshot_requeria_orden`, `_autorizacion`, `_credencial`) y si la credencial estaba vencida ese día
(`snapshot_credencial_vencida`). Lo que faltaba es la **evidencia**: ¿el paciente tiene la orden y la
autorización? Eso vive en `person` y se lee por un puerto nuevo de sólo lectura,
`person.spi.RespaldoDocumentalDirectory`:

| Requisito | Se cumple si… | Por qué así |
|---|---|---|
| Orden | hay una `orden_medica` **activa** del paciente, **vigente el día de la prestación**, sin cobertura o de esa cobertura | La misma regla que `ElegibilidadAdministrativaService.evaluarOrden` (`OrdenMedica#vigenteEl` + `sirveParaCobertura`): un mismo requisito no puede cumplirse distinto en la recepción y en la presentación |
| Autorización | hay una `autorizacion` **activa y APROBADA** de esa cobertura y esa práctica cuya **vigencia cubre el día de la prestación** | **No se mira el saldo**, a diferencia de la recepción: la sesión que se presenta pudo haber gastado la última unidad. Exigir saldo hoy marcaría como faltante justo la autorización que se usó |
| Credencial | `snapshot_credencial_vencida = false` **y** la cobertura tiene número de afiliado | El vencimiento es el congelado (lo que valía ese día); el número se lee vivo porque F-4 no lo congela y cargarlo después es justamente el remedio |

Los requisitos se evalúan **sólo cuando el convenio congelado los exigía** (`TRUE`). `NULL` —obligaciones
anteriores a F-4 o sin convenio— no exige nada: es RN-M17-005/006 aplicada al pasado.

### 2.2 Reparo y aviso

`ValidacionDePresentacion` gana una segunda lista, **`avisos`**, con la misma forma que `hallazgos`.

- `hallazgos` sigue significando **lo que bloquea**: `confirmable = hallazgos vacío && hay ítems`.
  Ganan `SIN_ORDEN` y `SIN_AUTORIZACION`.
- `avisos` **no bloquea**: `CREDENCIAL_FALTANTE`.

Una lista aparte y no un campo `severidad` en cada reparo: un cliente 0.99.0 que lee `hallazgos` como
"lo que bloquea" sigue teniendo razón sin cambiar una línea.

### 2.3 Dónde bloquea: confirmar, no incluir

Los reparos documentales se evalúan en `validar` y `confirmar`, **no al incluir** el ítem. `incluir`
sigue rechazando sólo lo que no tiene arreglo dentro del lote (anulada, sin saldo, otro financiador,
otra sede, otra moneda). Razón: si la orden faltante impidiera incluir, el ítem no estaría en el lote
y la pantalla de validación no podría mostrarlo — el administrativo se enteraría prestación por
prestación al armar, en vez de ver la lista entera y salir a juntar los papeles. El servidor sigue
siendo la autoridad: `confirmar` recorre los reparos de nuevo y responde 409
`presentacion-con-hallazgos`.

## 3. Débito parcial

### 3.1 El defecto

Hoy debitar 1.000 de un ítem de 6.000 lo deja `DEBITADO` (libera la obligación) y la conciliación
saltea todo ítem que no sea `INCLUIDO`. El lote cuadra (6.000 − 1.000 − 5.000 = 0) pero la obligación
del financiador sigue `PENDIENTE` por **6.000**: los 5.000 cobrados no saldan nada y además la
obligación queda **libre por 6.000**, así que otro lote puede reclamarla entera mientras este
todavía no concilió.

### 3.2 El modelo mínimo

**No hace falta tabla ni columna nueva.** `presentacion_item.importe_debitado` ya existe y la
obligación ya sabe tener saldo parcial (`saldo` + estado `PARCIAL`, V36). Lo que falta es que el
**estado del ítem** distinga el débito parcial, porque de eso depende la columna generada que hace
cumplir RN-M21-003. Dos estados nuevos:

| Estado | Ocupa la obligación | Cuándo |
|---|---|---|
| `INCLUIDO` | sí | sin cambios |
| `DEBITADO` | no | **débito total** (importe = presentado). Sin cambios |
| **`DEBITADO_PARCIAL`** | **sí** | débito menor que lo presentado, lote en curso. Sigue ocupando: lo no debitado todavía se está cobrando en ESTE lote |
| `ACEPTADO` | sí | conciliado sin débito. Sin cambios |
| **`ACEPTADO_PARCIAL`** | **no** | conciliado con débito parcial: se saldó lo cobrado y **se libera** para re-presentar lo debitado |
| `ANULADO` | no | sin cambios |

Conciliar, por cada ítem:

```
INCLUIDO          → ACEPTADO          descuenta importe_presentado              (sin cambios)
DEBITADO_PARCIAL  → ACEPTADO_PARCIAL  descuenta importe_presentado − importe_debitado
DEBITADO          → (queda)           no descuenta nada                         (sin cambios)
```

El descuento usa el mismo `CobroRepositoryPort.descontarSaldo` + `actualizarEstadoPorSaldo` de
siempre: UPDATE nativo condicional (`saldo >= :importe`) que **mueve `version`**, y el estado sale del
saldo (`PARCIAL` si queda algo). No se agrega ningún UPDATE nativo.

Después de conciliar, la obligación queda `PARCIAL` con saldo = lo debitado, aparece en la bandeja
de elegibles y un lote nuevo la incluye por `obligacion.saldo` —sólo lo debitado— porque así congela
`importe_presentado` el constructor del ítem desde 07.04.

### 3.3 Invariantes

- **Lote:** `saldo = total_presentado − total_debitado − total_cobrado`, sin cambios (`registrarDebito`
  ya acepta importes parciales).
- **Conciliación:** Σ por ítem de lo aceptado (`presentado` para `ACEPTADO`, `presentado − debitado`
  para `ACEPTADO_PARCIAL`, 0 para `DEBITADO`) = `total_cobrado`. Se sigue de la anterior con saldo 0.
- **Obligación:** después de conciliar, `saldo_final = saldo_al_incluir − lo aceptado del ítem`, y
  `importe_original` no se toca. DECIMAL de punta a punta.
- **Ítem (CHECK nuevo de V95):** los dos estados parciales exigen `0 < importe_debitado < importe_presentado`
  y el débito completo (motivo, instante, actor).

### 3.4 V95

- `ck_presentacion_item_estado`: se reemplaza por la **unión** de los cuatro valores de V56 más los dos
  nuevos. Es el error de V57 (`akine-alter-check-no-es-aditivo`): un CHECK que se reemplaza tiene que
  listar todo lo anterior, y `PresentacionesMigrationIT` lo verifica insertando los seis.
- `ocupa_marca`: `MODIFY` de la expresión generada (`STORED`) para que `DEBITADO_PARCIAL` ocupe. El
  unique `uk_presentacion_item_ocupa` no se toca.
- `ck_presentacion_item_debito_completo` se reemplaza para cubrir los dos estados parciales, y se
  agrega `ck_presentacion_item_debito_parcial`.
- `estado` sigue en `VARCHAR(16)`: `DEBITADO_PARCIAL` y `ACEPTADO_PARCIAL` miden exactamente 16.

**No hay migración de datos.** Un `DEBITADO` parcial registrado antes de V95 sigue con la regla vieja
(no salda al conciliar): reclasificarlo a `DEBITADO_PARCIAL` lo haría ocupar una obligación que en el
medio pudo haber entrado a otro lote —el unique lo rechazaría— y reclasificar lotes ya conciliados
movería saldos de cuentas corrientes cerradas. Es un desvío declarado; la consulta para encontrarlos
está en §6.

## 4. Contrato 0.99.0 → 0.100.0 (aditivo, minor)

- `ValidacionDePresentacion.avisos` (array nuevo).
- `ReparoDePresentacion.hallazgo`: + `SIN_ORDEN`, `SIN_AUTORIZACION`, `CREDENCIAL_FALTANTE`.
- `PresentacionItem.estado`: + `DEBITADO_PARCIAL`, `ACEPTADO_PARCIAL`.
- Descripciones de `debitarItemDePresentacion` y `conciliarPresentacion`.

Ningún campo cambia de tipo ni desaparece. Valores nuevos en un enum de **respuesta** es el mismo
criterio aditivo que el repo ya aplicó con `HallazgoDeValidacion` en F-4.

## 5. Design challenge (CLAUDE.md §3)

1. **Ownership.** Ninguna tabla nueva. `presentacion_item` es de `billing` (V56) y sólo `billing` la
   escribe. `orden_medica`, `autorizacion` y `cobertura_paciente` son de `person` y se **leen** por su
   `spi`; `billing` no escribe nada de `person`.
2. **Ciclos.** La arista nueva es `billing → person.spi`, que **ya existe** (`ObligacionService`,
   `CobroService`, `ObligacionDevengador`). Nadie depende de `billing`, así que no puede cerrar ciclo.
   `person.spi` no gana ninguna dependencia: el adaptador vive en `person.infrastructure` y usa sus
   propios puertos.
3. **Tenant.** No hay tabla nueva. Las tres lecturas de `person` van por `organization_id` + `persona_id`
   (`activasDe`, `aprobadasDePersona`, `findByIdAndOrganizationIdAndPersonaId`). El unique de ocupación
   ya lleva `organization_id`.
4. **Reglas maestras.** Obligación ≠ Cobro ≠ Caja intacta: el débito sigue sin mover caja, el pago del
   financiador sigue sin saldar obligaciones, y **sólo conciliar** salda. Lo nuevo es *cuánto* salda.
   Turno ≠ Sesión no se toca. La presentación sigue sin escribir en `encounter` ni `clinical`.
5. **Baja lógica.** Nada se borra. El ítem parcial conserva motivo, actor e instante del débito, y el
   remanente se re-presenta en **otra fila** de otro lote: la historia de los dos reclamos queda.
6. **Contrato.** Aditivo (§4) → minor 0.100.0. El frontend con cliente 0.99.0 no se rompe: verá dos
   estados que no conoce (los mostrará crudos) y no verá `avisos`.
7. **Ruta crítica.** Los cimientos existen: la obligación del financiador con snapshot (F-4), el
   débito y la conciliación (07.04), y orden/autorización/cobertura con su `spi` (03.06, 04.05, B-2).
   No se adelanta nada.
8. **El caso que rompe el diseño.**
   - *Re-presentar lo debitado antes de conciliar.* Con la regla de hoy (el parcial libera), un segundo
     lote incluiría la obligación **por 6.000** mientras el primero va a cobrar 5.000 de ella: reclamo
     doble. Por eso el parcial **sigue ocupando** hasta conciliar; el unique sobre la columna generada
     lo rechaza en la base, no un `if`. Costo aceptado: lo debitado se re-presenta recién después de
     conciliar el primer lote.
   - *La obligación ya no tiene el saldo al conciliar* (se cobró al paciente en el medio). El UPDATE
     condicional afecta cero filas: se registra el aviso en el log y el ítem pasa igual a
     `ACEPTADO_PARCIAL` —el lote ya está explicado—, el mismo criterio que 07.04 aplica a `INCLUIDO`.
   - *Reparo sin remedio dentro del sistema.* Si la sesión se atendió sin autorización y el financiador
     no la otorga retroactivamente, el reparo `SIN_AUTORIZACION` no se levanta nunca: el ítem hay que
     **quitarlo del borrador** y decidir esa deuda por otra vía. Es exactamente lo que el reparo
     quiere: que no se presente algo que el financiador va a debitar entero.
   - *Débito por el total sobre un ítem.* Sigue siendo `DEBITADO` y libera: no hay nada cobrado que
     saldar.

## 6. Lo que queda afuera

- **Segundo débito** sobre un ítem ya debitado parcialmente: 409 como hoy (`item-no-debitable`).
- **Pasar lo debitado al paciente o darlo de baja**: decisión del administrativo, sin flujo nuevo.
- **Débitos parciales previos a V95**: siguen con la regla vieja. Para encontrarlos:
  `SELECT * FROM presentacion_item WHERE estado = 'DEBITADO' AND importe_debitado < importe_presentado`.
- **Frontend**: mostrar `avisos`, los tres hallazgos nuevos y los dos estados de ítem. Espera el
  cliente 0.100.0.
