# AKINE-08.09b — Honorarios por clase (DP-38, U-41)

> 18/10/2026 · rama `akine-dp38-honorarios-por-clase` · contrato **0.134.0** · migración **V114** (reservadas al principio 0.131.0/V111; DP-39 tomó 0.132.0/V112 y la renovación 0.133.0/V113).
>
> Fuente: decisión del usuario del 18/10/2026 sobre **U-41** (**DP-38**, registrada en el plan §7 por
> #161), RF-M22-006, RF-M22-007 (CA-M22-006-06, CA-M22-007-06), la forma técnica de
> `AKINE-08.09-integracion-economica.md` §5 y el egreso de 07.05.

## 1. La decisión, en una tabla

| Pregunta de U-41 | Respuesta del usuario | Dónde vive |
|---|---|---|
| (a) Qué esquema | **Por participante presente** (`PRESENTE` o `PRESENTE_TARDE`) | `offering`: `oferta_honorario_clase.importe_por_participante` |
| (b) Dónde se declara | **En la oferta, con vigencia** desde/hasta, sin solapamiento, baja lógica | `offering`, mismo patrón que el precio particular (B-3) |
| (c) Varios instructores | **Partes iguales**; redondeo al centavo que cierra exacto, el resto al primero por orden estable | `activity`: `clase_instructor` + cálculo del borrador |
| (d) Borrador o automático | **Borrador por período y colaborador**, calculado al pedirlo; el administrador lo **confirma** y eso crea el **egreso** de M22 con snapshot | `activity` calcula; `billing` persiste egreso + liquidación |

Reglas que la decisión fija y el diseño hace cumplir:

- **Confirmar es idempotente** (`idempotencyKey` en el cuerpo, como el egreso).
- **Una clase ya liquidada no entra en otro borrador** del mismo colaborador.
- **Anular el egreso libera sus clases**: vuelven a aparecer en el borrador siguiente.
- **Un cambio futuro de tarifa no altera una liquidación cerrada** (CA-M22-007-06): el detalle
  por clase se copia al confirmar y no se recalcula nunca.

## 2. Módulos y dirección de dependencias

```
offering  ──(nada nuevo)
activity  ──► offering.spi.HonorariosDeClase      (tarifa vigente por oferta)
          ──► billing.spi.LiquidacionesDeHonorarios (qué clases ya se liquidaron; confirmar)
billing   ──► organization.spi                     (beneficiario, como el egreso manual)
```

`activity → billing.spi` ya existía (`DevengoDeVentas`, `DevengoDeClases`) y
`activity → offering.spi` también. **No se agrega ninguna arista nueva entre módulos**, sólo
interfaces nuevas en `spi` existentes. `billing` no conoce clases: guarda un `clase_id` opaco y
el snapshot; liberar las clases al anular el egreso es un `UPDATE` sobre su propia tabla, sin
llamar a `activity` (eso cerraría el ciclo que 08.09 §3 ya evitó).

## 3. Modelo

### 3.1 `oferta_honorario_clase` (offering)

Copia deliberada de `oferta_precio_particular` (V80): `importe_por_participante` (> 0),
`moneda`, `vigencia_desde`/`vigencia_hasta` inclusivas, cuarteto de baja lógica, `@Version`.
**El importe no se edita**: se cierra la vigencia y se carga otro. El no-solapamiento lo hace
cumplir el lock de la fila de la oferta (`bloquearParaConfigurar`), en `READ_COMMITTED`, igual
que B-3. Lectura y escritura exigen `consultorio:manage`: un honorario es configuración de la
oferta y no lo tiene que ver un `PROFESIONAL` ni un `PACIENTE` (el precio particular deja leer a
cualquier miembro; acá no, porque es lo que cobra un colega).

La tarifa que aplica a una clase es la vigente **el día local de la clase en la sede**
(zona IANA del consultorio), no la de hoy.

### 3.2 `clase_instructor` (activity)

La clase tenía **un** profesional (`clase_programada.profesional_membership_id`). La decisión (c)
necesita varios, así que se agregan los **instructores adicionales** en una tabla propia; el
principal sigue donde estaba y nada de lo existente cambia. Reemplazar la lista no borra: marca
`removido_en` (regla maestra 10).

**Orden estable** = el principal primero y después los adicionales por `membership_id`
ascendente. Es el orden que decide a quién va el centavo sobrante.

Validaciones al reemplazar (`PUT .../clases/{id}/instructores`, `clase:manage`): clase de la
sede y no cancelada, memberships de la organización vigentes, sin repetidos ni el principal,
habilitadas para la oferta (lista vacía = todos, regla de V28), y **la clase sin liquidación
vigente** (`clase-con-honorarios-liquidados`): cambiar el reparto de una clase ya pagada dejaría
el snapshot contando otra historia. Toma la clase `FOR UPDATE`, igual que reprogramar.

> **Límite declarado:** el adicional **no ocupa agenda**. La exclusión de turnos y clases sigue
> mirando sólo al principal. Hacer que el adicional reserve su franja es tocar el lock de
> `agenda_sede` y la exclusión de `scheduling`; queda fuera de esta etapa.

### 3.3 `liquidacion_honorario` y `liquidacion_honorario_clase` (billing)

Cabecera: egreso (`UNIQUE (organization_id, egreso_id)`), colaborador, período, total, moneda,
estado `VIGENTE`/`LIBERADA`, quién y cuándo confirmó, `idempotency_key` + `request_hash`
(`UNIQUE (organization_id, idempotency_key)`).

Detalle, **una fila por clase** con el snapshot completo: clase, inicio y fecha local, oferta y su
nombre, título, presentes, tarifa (`honorario_id` e importe por participante), total de la clase,
cantidad de instructores, posición del colaborador e importe que le toca.

**El candado contra la doble liquidación es un unique**:
`(organization_id, clase_id, colaborador_membership_id, liberada_key)`, con `liberada_key = 0`
mientras está vigente y `= id` al liberarse. Es el patrón del `deleted_key` (regla 10 del
`CLAUDE.md` raíz): varias filas liberadas conviven, una sola vigente.

**Oferta en el egreso (DP-39): una liquidación por oferta.** DP-39 (#162, `V112`) agregó
`egreso.oferta_id`, y DP-39 (b) dice que el egreso de honorarios "nace ligado a su oferta". Un
egreso tiene **una** oferta, así que **una liquidación es de una sola oferta**: su egreso se imputa
a ella y los honorarios entran solos en `rentabilidad-por-servicio`, sin prorrateo. El borrador y
la confirmación aceptan `ofertaId` para acotar; si el borrador de un colaborador mezcla ofertas y
no se acota, confirmar es **400** y la pantalla confirma una por oferta. Se descartó **partir
automáticamente** en N egresos en una sola confirmación: la idempotencia (una clave, una
liquidación), el 409 de desactualizado y la respuesta pasarían a ser de un conjunto, y el
administrador perdería el control de qué egreso nace. El snapshot sigue llevando la oferta por
fila (`oferta_id`, `oferta_nombre`), que es lo que RF-M22-007 pide; un egreso de liquidación
tampoco cambia su `ofertaId` al editarse (409 `egreso-de-liquidacion`).

## 4. El cálculo (activity, `HonorariosDeClasesService`)

Para `(sede, colaborador, desde, hasta)`, con `hasta - desde ≤ 62 días`:

1. Clases **`REALIZADA`**, no borradas, de la sede, con inicio en
   `[desde 00:00, hasta+1 00:00)` de la zona de la sede, donde el colaborador es el principal o un
   adicional vigente.
2. Por clase: presentes (`PRESENTE` + `PRESENTE_TARDE`), instructores en orden estable y tarifa
   vigente el día local.
3. `total = tarifa × presentes`; `base = total / n` truncado al centavo;
   `resto = total − base × n`; el primero cobra `base + resto`, los demás `base`. La suma de las
   partes es exactamente el total.
4. Quedan **excluidas** con motivo, y se informan: `YA_LIQUIDADA` (con el egreso que la tiene),
   `SIN_HONORARIO` (ninguna tarifa vigente ese día) y `SIN_PRESENTES` (total cero: un egreso por
   cero no existe y la clase queda libre para una corrección posterior de asistencia).
5. Si las incluidas tienen tarifas en monedas distintas, 400: un egreso tiene una sola moneda;
   se liquida acotando el período.

### Confirmar

`POST /consultorios/{c}/honorarios/liquidaciones`, en `READ_COMMITTED`:

1. `caja:operate` en la sede (el permiso del egreso; DU-10 no abrió otro).
2. **Bloquea `FOR UPDATE` las clases candidatas**, ordenadas por id. Es el punto de serialización:
   dos confirmaciones del mismo colaborador sobre las mismas clases van en fila, y la segunda
   relee —en `READ_COMMITTED`— lo que la primera ya liquidó.
3. Idempotencia **bajo el lock**: misma clave y mismo pedido devuelve la liquidación existente;
   otro pedido, 409.
4. Recalcula y compara con lo que el administrador vio (`claseIds` e `importeTotal`): si cambió,
   **409 `borrador-de-honorarios-desactualizado`** con lo actual. No se confirma algo distinto
   de lo que se miró.
5. Sin clases incluidas, **409 `liquidacion-de-honorarios-vacia`**.
6. `billing` crea el egreso **`BORRADOR`** (`COLABORADOR`, `HONORARIOS_PROFESIONALES`, período,
   comprobante opcional) por el mismo camino que el egreso manual —beneficiario vigente,
   comprobante no duplicado, auditoría `EGRESO_REGISTRADO`— y la liquidación con su detalle, en la
   misma transacción (`MANDATORY`, sin `REQUIRES_NEW`: la traba del pool de G-10).

El egreso nace borrador porque **confirmar un egreso exige comprobante** (07.05) y la factura del
profesional llega después de la liquidación. El ciclo del egreso sigue siendo el de M22.

### Lo que el egreso de una liquidación ya no admite

Editar **importe, categoría o período** de un egreso con liquidación vigente es
**409 `egreso-de-liquidacion`**: el total tiene que seguir siendo la suma del detalle. Concepto y
comprobante sí se editan, que es para lo que existe el borrador.

### Anular el egreso libera

`EgresoService.anular` (con el egreso ya `FOR UPDATE`) marca la liquidación `LIBERADA` y pone
`liberada_key = id` en su detalle, en la misma transacción, y audita
`LIQUIDACION_HONORARIOS_LIBERADA`. Las filas quedan: la historia de "se liquidó y se anuló" no se
borra.

## 5. API (contrato 0.134.0, aditivo)

| Operación | Ruta | Permiso |
|---|---|---|
| `listHonorariosPorClaseDeOferta` | `GET /consultorios/{c}/ofertas/{o}/honorarios-por-clase` | `consultorio:manage` |
| `createHonorarioPorClaseDeOferta` | `POST` ídem | `consultorio:manage` |
| `cambiarFinHonorarioPorClaseDeOferta` | `PUT .../{honorarioId}` | `consultorio:manage` |
| `deactivateHonorarioPorClaseDeOferta` | `DELETE .../{honorarioId}` | `consultorio:manage` |
| `getInstructoresDeClase` | `GET /consultorios/{c}/clases/{id}/instructores` | `clase:read` |
| `reemplazarInstructoresDeClase` | `PUT` ídem | `clase:manage` |
| `calcularBorradorDeHonorarios` | `GET /consultorios/{c}/honorarios/borrador` | `caja:operate` |
| `confirmarLiquidacionDeHonorarios` | `POST /consultorios/{c}/honorarios/liquidaciones` | `caja:operate` |
| `verLiquidacionDeHonorarios` | `GET /consultorios/{c}/honorarios/liquidaciones/{id}` | `caja:operate` |

`Egreso` suma `liquidacionHonorariosId` (sólo en el detalle; `null` en listados y en egresos
manuales). Problem types nuevos: `honorario-de-clase-solapado`, `honorario-de-clase-inactivo`,
`clase-con-honorarios-liquidados`, `borrador-de-honorarios-desactualizado`,
`liquidacion-de-honorarios-vacia`, `egreso-de-liquidacion`.

## 6. Tests

- Unitarios del reparto (centavo al primero, uno solo, sin instructores) y de la tarifa por día.
- `HonorariosPorClaseIT` contra MySQL: oferta con tarifa → clase con dos instructores →
  asistencias (presente, tarde, ausente) → borrador de cada instructor (centavo al primero) →
  confirmar → egreso `BORRADOR` con detalle y snapshot → reintento con la misma clave sin
  duplicar → la clase ya no entra → subir la tarifa no toca lo liquidado → editar el importe del
  egreso es 409 → anular el egreso libera y la clase vuelve al borrador → otro tenant no la ve.
- ArchUnit (`ModuleArchitectureTest`) sin cambios de reglas.

## 7. Design challenge

1. **Ownership.** `oferta_honorario_clase` es de `offering` (la oferta es suya); `clase_instructor`
   de `activity`; `liquidacion_honorario*` de `billing`. Nadie lee una tabla ajena: `activity` pide
   la tarifa por `offering.spi` y lo liquidado por `billing.spi`; `billing` guarda `clase_id` y
   `oferta_id` como referencias con FK pero **nunca las lee**.
2. **Ciclos.** Sólo aristas existentes (`activity → offering`, `activity → billing`,
   `billing → organization`). Liberar al anular no llama a `activity`: es una escritura de
   `billing` sobre su tabla, y el borrador siguiente "ve" la clase libre porque pregunta.
   ArchUnit corrido.
3. **Tenant.** Las tres tablas llevan `organization_id NOT NULL` y todo unique/índice empieza por
   él. Las consultas del cálculo filtran organización y sede. Una membership de otro tenant da un
   borrador vacío; una liquidación de otro tenant, 404.
4. **Reglas maestras.** Egreso ≠ pago ≠ caja se respeta: confirmar la liquidación crea un egreso
   **borrador**, no mueve caja. La liquidación no toca sesiones, asistencias ni obligaciones
   (RN-M22-003): lee asistencias y nada más.
5. **Baja lógica.** Tarifa: baja con motivo. Instructores: `removido_en`. Liquidación: `LIBERADA`
   con `liberada_key`, filas conservadas. Ningún `DELETE`.
6. **Contrato.** Aditivo: nueve operaciones, un campo opcional en `Egreso` y seis problem types.
   Cambia una respuesta: editar importe/categoría/período de un egreso **de liquidación** pasa de
   200 a 409 — no existía antes de esta versión, así que no rompe ningún uso. Minor 0.134.0.
7. **Ruta crítica.** Clase, asistencia, oferta con vigencias y egreso están en `main`. Lo único
   que faltaba como cimiento —varios instructores— entra acá, mínimo y sin tocar agenda.
8. **El caso que rompe el diseño.** *Dos administradores confirman a la vez la liquidación de
   octubre del mismo profesional.* Sin el lock, los dos calculan la misma base y los dos insertan:
   el unique del detalle frena al segundo con un 500. Con el `FOR UPDATE` de las clases en orden de
   id, el segundo espera, relee en `READ_COMMITTED`, encuentra las clases liquidadas y recibe
   **409 `borrador-de-honorarios-desactualizado`** (o la misma liquidación, si traía la misma
   clave). El unique queda como cinturón. Segundo caso: *corrigen una asistencia después de
   liquidar.* La liquidación no cambia (CA-M22-007-06); si el centro quiere pagar la diferencia,
   anula el egreso —libera la clase— y vuelve a liquidar.
