# AKINE E-3 — Series de turnos (DP-04)

> Paquete **E-3** de `docs/fases/01-trabajo-en-paralelo.md` (grupo G5, carril E). Cierra el ítem
> "Modelo de serie (05.03)" de `docs/fases/F5-agenda-y-recepcion.md`.
> Migración reservada: **`V70`**. Contrato: **0.57.0**.

## 1. Qué se cubre y de dónde sale

M12 **no tiene un RF de "serie"**: la palabra no aparece en el documento integrado. Lo que obliga
a modelarla es DP-04 (ADR-0011) y la ficha de la etapa 05.03 del plan, que la pide sobre los RF
genéricos de crear, cancelar y reprogramar turno.

| Requisito | Qué dice | Cómo se cubre |
|---|---|---|
| DP-04 / ADR-0011 | "Modelo explícito de serie/regla", pero **cada Turno conserva identidad, estado e historial propios** | `turno_serie` describe la regla; cada ocurrencia es un `turno` pleno con `serie_id` (§2) |
| DP-04 | "La primera ausencia se registra como `AUSENTE` y nunca elimina la serie" | No hay ningún camino que toque la serie desde una ausencia: `marcarAusente` no cambió y el IT lo fija (§8) |
| DP-04 | Cancelar futuros exige **confirmación explícita, motivo obligatorio y auditoría**; los pasados quedan inalterables | Cancelación con alcance, `cantidadConfirmada` obligatoria, motivo `@NotBlank`, auditoría por turno y por serie (§4) |
| Plan 05.03, Backend | "Manejo de serie/regla recurrente, cancelación explícita de futuros" | §3, §4 |
| Plan 05.03, API | "Comandos específicos para cada transición **y para cancelar futuros de una serie**" | `POST .../series-de-turnos/{id}/cancelacion` |
| Plan 05.03, Frontend | "Confirmación **con alcance y cantidad** antes de cancelar turnos futuros" | Previsualización `GET .../alcance` y `cantidadConfirmada` en el comando (§4.1) |
| Plan 05.03, Reglas | "La cancelación masiva solo afecta turnos futuros pendientes y registra actor, motivo, fecha y alcance" | §4.2 |
| RF-M12-002 | Crear turno; "si la operación no admite modo parcial, no dejar cambios parciales" | Alta de serie **todo o nada** (§3) |
| RF-M12-004 / RN-M12-002 | Cancelar no elimina físicamente | La cancelación por alcance es la misma transición de 05.03 aplicada a N turnos |
| RF-M12-005 / RN-M12-003 | Reprogramar conserva trazabilidad | Reprogramación por alcance **mueve** cada turno, con su evento `REPROGRAMACION` (§5) |
| RN-M12-004 / RNF-M12-003 | La confirmación previene doble reserva; locking ante recursos compartidos | Todo bajo el lock de `agenda_sede`, en `READ_COMMITTED` (§6) |
| RF-M26-002/003 (E-5) | Avisos de reserva, cancelación y reprogramación | Un aviso **por ocurrencia**, con los tipos que ya existen (§7) |
| Plan §17 (riesgos) | "Cancelar una serie con alcance equivocado; la UI debe previsualizar turnos afectados" | §4.1 |

### Lo que queda AFUERA, y por qué

- **Recurrencias que no sean semanales** (cada N semanas, mensual, "el primer lunes"). Ningún RF las
  pide; la regla semanal con días y cantidad o fecha fin es lo que describe el flujo histórico y lo
  que alcanza para un tratamiento de kinesiología. `frecuencia` existe como columna con un único
  valor permitido para que agregar otra sea aditivo.
- **Editar la regla de una serie ya creada** ("de ahora en más, los jueves"). Es una reprogramación
  por alcance más una regla nueva, y ningún RF la pide. La regla queda como **la regla con la que
  se generó**: los turnos son la verdad (ADR-0011: "la serie es solo la regla que los generó").
- **Extender una serie** (agregar ocurrencias al final). Sin RF; se crea otra serie.
- **Alta parcial** ("reservá las que tengan lugar"). Ver la decisión a revisar §10.1.
- **Vínculo Plan de Tratamiento → serie** (`clinical` referenciando la serie por `spi`, como anticipa
  ADR-0011). Es de C-9/F4 y no tiene escritor hoy.
- **Un aviso consolidado por serie** (un correo con todas las fechas). Exige un tipo nuevo en
  `notification`, que es del carril A. Ver §10.3.
- **Frontend** (confirmación de alcance en la UI): es la mitad web de E-3 y va en `appKine-web`, en
  la rama homónima, contra este contrato.

## 2. Modelo

```
 turno_serie (regla)                          turno (ocurrencia, entidad plena)
 ─────────────────────                        ──────────────────────────────────
 id, organization_id, consultorio_id    1 ─┐  id, ..., serie_id NULL ── fk_turno_turno_serie
 oferta_id, persona_id,                    └─ N estado, version, historial propio (turno_evento)
 profesional_membership_id
 frecuencia = SEMANAL
 dias_semana  "1,4"   (ISO: 1 = lunes)
 hora_local   09:00
 fecha_desde, fecha_hasta | cantidad   (exactamente uno)
 timezone     (la de la sede al crearla)
 cantidad_generada
 idempotency_key, request_hash
 creada_por_cuenta_id, creada_en, version
```

- **`turno.serie_id` es nullable** y lo fija el alta de serie antes del INSERT; después no cambia
  (`updatable = false`). Un turno suelto sigue siendo exactamente lo que era: la migración es
  aditiva y no rellena nada.
- **La serie no tiene estado ni baja.** No es dueña del ciclo de vida de sus miembros (ADR-0011):
  "la serie está cancelada" es un hecho que se lee en sus turnos, no una columna que pueda
  contradecirlos. Por eso tampoco lleva `deleted_at`: no hay ninguna operación que la dé de baja.
- **`timezone` se congela** al crear: la regla dice "lunes 09:00" en hora local, y si la sede
  cambiara de zona la regla tiene que seguir explicando las ocurrencias que generó.
- **Tope de 52 ocurrencias.** Con `cantidad` es un `CHECK`; con `fecha_hasta`, el servicio cuenta
  las ocurrencias antes de reservar y rechaza con 400. Un año de sesiones semanales es más que
  cualquier plan de kinesiología, y sin tope un error de tipeo en la fecha fin toma el lock de la
  sede para reservar cientos de turnos. Decisión a revisar §10.2.

## 3. Alta de serie — todo o nada

`POST /api/v1/consultorios/{consultorioId}/series-de-turnos`

```
 1. asegurar agenda_sede            <- transacción aparte (INSERT ... ON DUPLICATE KEY UPDATE)
 2. LOCK agenda_sede (FOR UPDATE)   <- antes de leer un solo turno
 3. idempotencia (turno_serie.idempotency_key)
 4. oferta, persona con perfil de paciente vigente
 5. expandir la regla -> instantes (zona de la sede)
 6. INSERT turno_serie
 7. por cada ocurrencia, en orden: vigencia de la oferta ese día, RevalidadorDeSlot,
    INSERT turno (serie_id), turno_evento RESERVA, aviso TURNO_RESERVADO
 8. auditoría TURNO_SERIE_CREADA
```

- **Todo o nada.** Si una sola ocurrencia no tiene lugar —slot inexistente, sin cupo, recurso
  ocupado, oferta fuera de vigencia ese día— la transacción entera hace rollback: ni la serie ni
  ninguna ocurrencia quedan. RF-M12-002 lo dice para "crear turno" ("de lo contrario no dejar
  cambios parciales") y no hay RF que pida modo parcial, así que se toma la política más
  conservadora. El 409 conserva el `problemType` de la causa (`slot-no-disponible`,
  `slot-completo`, `recurso-ocupado`, `oferta-no-agendable`) y agrega **`ocurrenciaInicio`**: la
  pantalla puede decir "el lunes 22 a las 9 ya está tomado" y ofrecer otro día u hora para toda la
  serie. No se publica un tipo nuevo: para la pantalla la acción es la misma que en una reserva
  suelta, con una fecha más.
- **Las ocurrencias no chocan entre sí** al revalidarse: cada una se inserta antes de revalidar la
  siguiente y la consulta de solapamiento las ve (misma transacción). Una regla semanal no produce
  dos ocurrencias el mismo día, pero si lo hiciera el revalidador la rechazaría como a cualquiera.
- **Toda ocurrencia tiene que ser futura.** Una regla cuya primera ocurrencia ya pasó es 400: el
  pasado no se reserva, y "salteá las pasadas" sería una alta parcial disfrazada.
- **Idempotencia** en `turno_serie`, con `uk_turno_serie_idempotencia (organization_id,
  idempotency_key)` y hash del pedido, como la reserva de 05.02: misma clave y mismo pedido devuelve
  la serie ya creada con 200 y no encola avisos; otro pedido con la misma clave es 409
  `idempotency-key-conflict`. Los turnos de la serie **no** llevan clave propia: la idempotencia es
  de la operación, y la operación es la serie.

## 4. Cancelar con alcance

`POST /api/v1/consultorios/{consultorioId}/series-de-turnos/{serieId}/cancelacion`
`{ alcance, turnoId, motivo, cantidadConfirmada }`

| Alcance | Candidatos |
|---|---|
| `ESTE` | el turno pivote |
| `ESTE_Y_SIGUIENTES` | los turnos de la serie con `inicio >= inicio del pivote` (incluido) |
| `TODA_LA_SERIE` | todos los turnos de la serie (el pivote es opcional) |

De los candidatos se **afectan** solo los **pendientes**: `RESERVADO` o `CONFIRMADO`, que todavía no
empezaron y sin Sesión registrada. El resto se **omite** con su motivo: `YA_EMPEZO`,
`ESTADO_TERMINAL` (cancelado o ausente), `EN_ESPERA` (el paciente está en la sala: eso se resuelve
turno por turno, no en lote) y `CON_ATENCION`.

"Siguientes" se ordena por el `inicio` **actual**, no por el orden de generación: después de una
reprogramación individual, "este y los siguientes" sigue queriendo decir lo que ve el operador en el
calendario.

### 4.1 Confirmación explícita

`GET /api/v1/consultorios/{consultorioId}/series-de-turnos/{serieId}/alcance?alcance=&turnoId=`
devuelve afectados y omitidos con el mismo cálculo que el comando. El comando exige
`cantidadConfirmada` y la compara contra lo que calcula **bajo el lock**: si difiere —alguien movió,
canceló o atendió un turno entre la previsualización y el click— es **409 `conflict`** y no se
toca nada. Es la "confirmación explícita" de DP-04 hecha contrato: el backend rechaza la
operación sin ella, que es lo que pide ADR-0011 ("la cancelación masiva expone en el contrato la
confirmación explícita y el motivo obligatorio").

La cantidad no detecta un intercambio (sale uno, entra otro, misma cuenta). Se aceptó: el lock de
sede serializa contra toda reprogramación y la ventana real es la de la pantalla abierta. Ver §10.4.

### 4.2 Qué hace sobre cada turno afectado

Exactamente lo que hace la cancelación de 05.03, extraída a un método compartido para que no haya
dos copias: estado `CANCELADO`, motivo, actor, `deleted_at` (libera el lugar), evento
`CANCELACION` en `turno_evento`, auditoría `TURNO_CANCELADO` y aviso `TURNO_CANCELADO`. Además,
una entrada de auditoría **de la serie**, `TURNO_SERIE_CANCELADA`, con el alcance, el pivote, la
cantidad y los ids afectados: es el "alcance" que DP-04 pide registrar y que ningún evento de turno
individual puede contar.

**Toma el lock de la sede**, a diferencia de la cancelación suelta. Cancelar no puede crear un
solapamiento, pero el conjunto afectado tiene que ser estable entre el cálculo y la escritura —es lo
que la cantidad confirmada promete—, y la única operación que puede meter o sacar un turno del
alcance es una reprogramación, que toma ese lock.

## 5. Reprogramar con alcance

`POST /api/v1/consultorios/{consultorioId}/series-de-turnos/{serieId}/reprogramacion`
`{ alcance, turnoId, inicio, profesionalId?, motivo, cantidadConfirmada }`

- **Mueve, no cancela y crea.** Cada turno afectado conserva su id, su historial y la Sesión que
  pueda colgar de él (`uk_sesion_turno`): es la regla de 05.03 aplicada a N turnos.
- **El pivote es obligatorio en los tres alcances**: `inicio` es el horario nuevo **del pivote**, y
  de ahí sale el desplazamiento. El desplazamiento se calcula en **hora local** (de "lunes 09:00" a
  "martes 10:00" son +1 día y +1 hora) y se aplica a la hora local de cada turno afectado. Así un
  turno que ya se había movido individualmente conserva su diferencia, y un cambio de horario de
  verano no corre las ocurrencias una hora.
- **`profesionalId` opcional**: si viene, todos los afectados pasan a ese profesional (el caso
  "el profesional deja los lunes"); si no, cada uno conserva el suyo.
- **Orden de proceso**: con desplazamiento positivo, del último al primero; con negativo, del
  primero al último. Sin esto, mover cuatro lunes una semana para adelante choca contra el lunes
  siguiente de la propia serie, que todavía no se movió.
- **Todo o nada**, con `ocurrenciaInicio` en el 409 como en el alta. Mismos afectados y omitidos que
  la cancelación, misma `cantidadConfirmada`.
- Cada turno se revalida con `RevalidadorDeSlot` y `turnoExcluidoId` = él mismo, y vuelve a
  `RESERVADO` (lo confirmado era otro horario), con evento `REPROGRAMACION`, auditoría y aviso
  `TURNO_REPROGRAMADO`. Más `TURNO_SERIE_REPROGRAMADA` en la auditoría de la serie.
- La **regla de la serie no se reescribe** (ver §1, fuera de alcance).

`ESTE` por la serie es lo mismo que `POST /turnos/{id}/reprogramacion`, y los endpoints sueltos de
05.03 **siguen sirviendo para turnos de una serie**: cancelar o mover una ocurrencia sola no afecta a
sus hermanas (ADR-0011, consecuencia positiva 3).

## 6. Concurrencia

Las tres escrituras de serie siguen el orden de 05.02/05.03 sin cambios: fila de `agenda_sede`
asegurada en su propia transacción con `INSERT ... ON DUPLICATE KEY UPDATE`, `FOR UPDATE` antes de
leer un solo turno, y `READ_COMMITTED` para que la revalidación vea lo que otra transacción acaba de
commitear. **Ningún unique expresa el solapamiento** de dos ocurrencias con otra reserva: lo hace
cumplir el revalidador bajo el lock.

Cada turno que se cancela o se mueve pasa por `saveAndFlush`, así que su `@Version` avanza en la
fila y la versión devuelta es la que quedó guardada (la trampa de "la versión que devuelve vieja").
No hay ningún UPDATE nativo: no hace falta tocar `version` a mano.

## 7. Avisos (E-5)

**Uno por ocurrencia**, con `TURNO_RESERVADO`, `TURNO_CANCELADO` y `TURNO_REPROGRAMADO`, encolados en
la misma transacción y con las claves idempotentes de E-5 (`turno-reservado:{id}`, etc.). Cada turno
es una entidad con identidad propia (DP-04) y el paciente tiene que saber **qué** fechas cambiaron.
El costo es una serie de 12 = 12 correos. Consolidarlo en un aviso por serie es un tipo nuevo en
`notification` (carril A): decisión a revisar §10.3.

## 8. Lo que no cambió y se prueba igual

- **Ausencia**: `marcarAusente` no conoce la serie. Marcar ausente la primera ocurrencia deja las
  demás vivas (IT).
- **Turnos sueltos**: `serie_id` NULL; reservar, cancelar y mover no cambiaron de comportamiento.

## 9. Design challenge

1. **Ownership.** `turno_serie` es de `scheduling`, como `turno` (ADR-0011: "`scheduling` es
   propietario de Serie y Turno"). Ningún otro módulo la lee ni la escribe; `clinical` la
   referenciará por `spi` cuando exista el vínculo con el Plan, que queda afuera.
2. **Ciclos.** No se agrega ninguna dependencia entre módulos: el servicio nuevo usa los mismos
   `spi` que `TurnoService` y `CicloDeTurnoService` (`offering`, `organization`, `person`,
   `notification`, `platform`, `resource`) y la `AtencionProbe` propia. ArchUnit 5/5.
3. **Tenant.** `turno_serie.organization_id NOT NULL` con FK; el unique de idempotencia lo
   incluye; el índice nuevo de `turno` es `(organization_id, serie_id, inicio)`. Toda lectura de la
   serie filtra por organización **y** sede, y una serie de otro tenant es 404 (IT).
4. **Reglas maestras.** La serie es de **turnos**, no de sesiones: no genera "sesiones futuras" —el
   error del documento de 2019 que ADR-0011 nombra—. No toca Sesión, obligación ni cobro: un turno
   con atención se omite del alcance, nunca se cancela por lote.
5. **Baja lógica.** Ningún DELETE. Cancelar por alcance es la transición de estado de 05.03 con
   `deleted_at`; la serie no se borra ni se cierra; `turno_evento` sigue append-only.
6. **Contrato.** Aditivo: cinco operaciones nuevas, `serieId` opcional en `Turno` y en
   `TurnoDelDia`, y la propiedad `ocurrenciaInicio` en problemas ya publicados. Minor: **0.57.0**.
7. **Ruta crítica.** Los cimientos están: reserva atómica (05.02), ciclo de turno (05.03), avisos
   (E-5), sondas de ocupación externa (08.01). Lo que se adelanta es nada: el vínculo con el Plan
   (que sí dependería de C-9) queda afuera.
8. **El caso que rompe el diseño.** *Una serie de 12 lunes se da de alta mientras otra recepcionista
   reserva el lunes 6 a las 9 para otro paciente.* Sin cuidado: las dos validan contra una foto en
   la que el hueco está libre, la serie entra con 12 turnos y la reserva con 1, y el lunes 6 hay
   dos pacientes encima. **Resolución:** las dos operaciones toman el `FOR UPDATE` de la misma fila
   de `agenda_sede` antes de leer, en `READ_COMMITTED`. Si gana la reserva, la serie revalida el
   lunes 6, encuentra el turno recién commiteado y hace rollback **entero** con `recurso-ocupado` y
   `ocurrenciaInicio` = lunes 6: ni serie ni ocurrencias. Si gana la serie, la reserva recibe 409.
   Nunca hay dos turnos en el hueco y nunca hay una serie de 11 de 12. Es el IT
   `SerieDeTurnosIT#alta_de_serie_compite_con_una_reserva_que_pisa_una_ocurrencia`.

   Segundo candidato: *mover "este y los siguientes" una semana para adelante*. Cada destino es el
   lugar actual del turno siguiente de la misma serie, y procesando en orden cronológico el primero
   choca contra su hermano. Se resuelve con el orden de proceso de §5 (inverso para desplazamiento
   positivo), y lo cubre un unitario.

## 10. Decisiones a revisar

1. **Alta todo o nada.** Si una ocurrencia no tiene lugar, no se crea nada. La alternativa —crear
   las que entran y devolver las que no— es más cómoda en el mostrador pero deja una serie con
   huecos que nadie decidió. Sin RF, se eligió la conservadora; la pantalla puede reintentar con
   otro horario o crear dos series. **Costo conocido:** un feriado cae en algún día de la regla en
   casi cualquier serie larga, y con todo o nada esa serie entera falla con `slot-no-disponible` y
   la fecha del feriado en `ocurrenciaInicio`. Si en el mostrador eso resulta frecuente, la
   alternativa más barata es una política explícita "saltear feriados" en el pedido, no el modo
   parcial genérico.
2. **Tope de 52 ocurrencias** por serie.
3. **Un aviso por ocurrencia**, no uno por serie (§7).
4. **Confirmación por cantidad**, no por la lista de ids (§4.1). Más simple para la UI y alineado
   con el plan ("confirmación con alcance y cantidad"); no detecta un intercambio de mismo tamaño.
5. **`EN_ESPERA` se omite** de la cancelación por lote aunque la cancelación suelta lo admite: un
   paciente que está en la sala no se cancela sin mirarlo.
6. **La regla no se reescribe** al reprogramar por alcance: describe cómo se generó la serie, no
   dónde están hoy sus turnos.
