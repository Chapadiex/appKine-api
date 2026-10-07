# AKINE E-4 — Recepción con máquina de estados propia, validación administrativa y camino Particular

**07/10/2026** · rama `akine-E-4-recepcion` · migración `V78` · contrato `0.63.0` · ficha
[F5](../fases/F5-agenda-y-recepcion.md) · decisión **DP-16** (dueño del producto, 07/10/2026).

## 1. Qué decide DP-16 y qué cambia

DP-05 pedía tres máquinas de estado independientes —Turno, Check-in/Recepción y Sesión— y 05.04
"reducida" resolvió el check-in como un estado más del turno (`EN_ESPERA` dentro de
`EstadoTurno`, con `llegada_en` y `estado_antes_de_espera` en la fila de `turno`). La ficha F5
lo declaraba desvío y DU-9 preguntaba si se documentaba o se corregía. **DP-16 lo corrige**:

- Nace la entidad **Recepción** (`scheduling.domain.Recepcion`, tabla `recepcion`) con estados
  propios y un historial append-only (`recepcion_evento`) que guarda actor, hora, estado
  anterior, estado nuevo y motivo de cada transición.
- **El Turno vuelve a ser sólo la reserva.** `EN_ESPERA` sale de `EstadoTurno`. Los turnos que
  estaban en `EN_ESPERA` se migran (`V78`): el turno vuelve al estado del que vino y la llegada
  pasa a una fila de `recepcion`.
- La **validación administrativa** (RF-M13-003/004) y el **camino Particular** (RF-M13-005,
  RN-M13-004) se apoyan en la Recepción. El prepago como anticipo (E-6) también se va a apoyar
  acá, y no se hace en este paquete.
- **Ninguna transición de recepción prueba que una prestación ocurrió** (DP-05 intacta). La
  Sesión sigue pudiendo abrirse desde el turno con o sin recepción, en cualquier estado de ella.

## 2. La máquina de estados

```
                 registrarLlegada (check-in, idempotente)
   (sin recepción) ─────────────────────────────────────▶ LLEGO
   LLEGO     ──validar (elegible)──────────────────────▶ VALIDADA   (modalidad COBERTURA)
   LLEGO     ──validar (no elegible / sin cobertura)───▶ OBSERVADA  (observacion obligatoria)
   OBSERVADA ──validar (revalidar: trajo la orden)─────▶ VALIDADA | OBSERVADA
   LLEGO     ──atenderComoParticular (motivo)──────────▶ VALIDADA   (modalidad PARTICULAR)
   OBSERVADA ──atenderComoParticular (motivo)──────────▶ VALIDADA   (modalidad PARTICULAR)
   VALIDADA  ──pasarAEspera────────────────────────────▶ EN_ESPERA
   OBSERVADA ──pasarAEspera────────────────────────────▶ EN_ESPERA  (la observación queda a la vista)
   EN_ESPERA ──llamar──────────────────────────────────▶ LLAMADA
   LLEGO | VALIDADA | OBSERVADA | EN_ESPERA | LLAMADA
             ──anular (check-in por error)─────────────▶ ANULADA    (terminal; la llegada NO vale)
   LLEGO | VALIDADA | OBSERVADA | EN_ESPERA | LLAMADA
             ──cancelación del turno (automática)──────▶ CERRADA    (terminal; la llegada SÍ vale)
```

| Transición | Quién la dispara | Permiso |
|---|---|---|
| Registrar llegada | Administrativo en el mostrador | `turno:manage` |
| Validar | Administrativo; **el servidor** calcula el resultado | `turno:manage` |
| Atender como Particular | Administrativo, con motivo obligatorio | `turno:manage` |
| Pasar a espera | Administrativo | `turno:manage` |
| Llamar | Profesional o administrativo | `turno:manage` (PROFESIONAL lo tiene en su sede) |
| Anular | Administrativo, motivo opcional | `turno:manage` |
| Cerrar por cancelación | El sistema, dentro de la cancelación del turno | el de cancelar |
| Leer recepción e historial | Cualquiera que lea la agenda | `turno:read` |

No se agrega ningún código de permiso: la matriz §32 no tiene fila de recepción y lo más
cercano —*Gestionar turnos*— tiene exactamente los actores de M13. Sumar uno sería tocar
`PermissionCode`/`RolePermissions`, que son puntos calientes, sin que ningún RF lo pida.

### Reglas que fija

1. **Una sola recepción vigente por turno.** "Vigente" es todo lo que no es `ANULADA`. Lo
   sostiene la base con `uk_recepcion_turno_vigente (organization_id, turno_vigente_id)`, donde
   `turno_vigente_id` es una columna generada que vale `turno_id` salvo en las anuladas (que
   quedan en `NULL` y no colisionan). Anular deja la fila como historia, y un check-in posterior
   crea una recepción nueva.
2. **El check-in es idempotente; anular no** (las mismas dos reglas de 05.04). Registrar la llegada
   con una recepción vigente devuelve esa recepción, sin mover la hora ni registrar un evento.
   Anular sin recepción abierta es 409.
3. **La hora la pone el servidor**, en todas las transiciones. Ningún cuerpo trae una hora.
4. **Validar no consume nada y no persiste nada fuera de la recepción.** Usa la elegibilidad de
   M17, que es una consulta (RN-M17-001). Lo que guarda es un **snapshot administrativo
   preliminar**: práctica, cobertura y convenio con que se validó, y el detalle de lo observado.
5. **Particular es una decisión explícita del operador, con motivo, y no modifica la cobertura
   maestra** (RN-M13-004): sólo escribe en la recepción. Procede sin cobertura aplicable, con
   elegibilidad observada o sin práctica declarada en la oferta.
6. **La observación advierte y no bloquea** (RN-M13-003): una recepción `OBSERVADA` puede pasar a
   espera sin resolverse, y la observación queda en la fila para que la vea quien atiende.
7. **Llamar no abre la Sesión, y abrir la Sesión no mueve la recepción.** Son dos actos de dos
   personas (DP-05). `encounter` no se entera de que la recepción existe.

## 3. Validación administrativa: el algoritmo

`CicloDeRecepcionService.validar`, dentro de la transacción de la transición:

1. **Práctica**: `offering.spi.PracticasDeOfertaDirectory.practicaPrincipal` de la oferta del
   turno (DP-11). Si la oferta no declara práctica → `OBSERVADA` con motivo
   `OFERTA_SIN_PRACTICA`: sin práctica no hay convenio que resolver, y es un hueco de
   configuración del centro, no del paciente.
2. **Cobertura**: `person.spi.CoberturasAplicablesDirectory.aplicables` (B-2) para la persona, la
   práctica y el día **en la zona de la sede**. Vacía → `OBSERVADA` con `SIN_COBERTURA_APLICABLE`
   (es el caso que se resuelve como Particular). Si el operador elige una `coberturaId` que no
   está entre las aplicables → `OBSERVADA` con `COBERTURA_NO_APLICABLE`. Si no elige, se usa la
   primera (la principal va primero).
3. **Elegibilidad**: `person.spi.ElegibilidadAdministrativaDirectory.evaluar` —nuevo en este
   paquete, ver §4— con esa cobertura. Elegible → `VALIDADA`, modalidad `COBERTURA`, con
   `cobertura_id` y `convenio_id`. No elegible → `OBSERVADA` con el detalle de cada requisito
   faltante (`ORDEN: …`, `AUTORIZACION: …`, `CREDENCIAL: …`).

**La elegibilidad no prueba ni impide la prestación.** Por eso el resultado es un estado de la
recepción y nunca un 4xx: "le falta la orden" es el caso más frecuente del mostrador y tratarlo
como error obligaría a la pantalla a manejarlo como excepción.

## 4. El consumidor que `consultarElegibilidadAdministrativa` no tenía

La elegibilidad vive en `person.application.ElegibilidadAdministrativaService` (la ficha la
nombraba como de `contracting`; está en `person`, que es quien conoce órdenes y
autorizaciones). Sólo se alcanzaba por HTTP y exigía un actor de `person`. Se agrega:

- `person.spi.ElegibilidadAdministrativaDirectory` con `evaluar(org, sede, persona, cobertura,
  practica, fecha)`, que devuelve `VeredictoDeElegibilidad` (elegible, motivo, convenio,
  faltantes como texto). No autoriza nada: quien llama ya resolvió permiso y pertenencia.
- `ElegibilidadAdministrativaService.consultar` pasa a delegar en un `evaluar` sin actor, así la
  regla queda en un solo lugar y el endpoint de M17 no cambia de comportamiento.

## 5. La cancelación del turno con una recepción abierta

**Regla:** cancelar un turno cuya recepción está abierta (cualquier estado salvo `ANULADA` y
`CERRADA`) **cierra la recepción en `CERRADA`, conservando la llegada**, con el motivo de la
cancelación. Es lo que 05.04 ya hacía con `EN_ESPERA` —"el paciente vino y el profesional no lo
pudo atender"— trasladado a la entidad que ahora lo representa: borrar la llegada destruiría la
evidencia de que la persona vino. La cancelación de una serie **omite** los turnos con recepción
abierta (motivo de omisión `EN_ESPERA`, que conserva su nombre publicado): un operador que
cancela "este y los siguientes" no tiene por qué estar mirando a quien ya está en la sala.

Reprogramar y marcar ausencia **siguen rechazándose** con la recepción abierta, como desde
`EN_ESPERA`: mover un turno cuyo paciente ya llegó no tiene sentido, y afirmar que no vino alguien
que está en la sala es falso. Con la recepción `ANULADA` sí se puede (la llegada no valía).

## 6. Migración `V78`

1. `CREATE TABLE recepcion` y `CREATE TABLE recepcion_evento`, con `organization_id` y
   `consultorio_id`, FKs con la tabla adelante y CHECKs de coherencia (observada ⟹ observación,
   particular ⟹ motivo, cerrada/anulada ⟹ hora de cierre).
2. **Backfill**: cada turno con `llegada_en` no nulo produce una recepción:
   - `EN_ESPERA` → recepción `EN_ESPERA` con la llegada y el responsable de la fila del turno, y
     el turno vuelve a `estado_antes_de_espera` (o `RESERVADO` si faltara).
   - `CANCELADO` con llegada (cancelado desde la espera) → recepción `CERRADA`, con la llegada y
     el motivo y la hora de la cancelación.
   - Cada una con sus eventos: `LLEGADA` en la hora real de llegada (motivo que dice que fue
     migrada por `V78` y que la validación no existía) y, para las cerradas, el cierre.
3. **Turnos en espera de días pasados**: se migran igual, a `EN_ESPERA`. No se inventa qué pasó
   después: el dato dice que la persona llegó y quedó esperando, y eso es lo que se conserva. El
   turno queda en `RESERVADO`/`CONFIRMADO` con fecha pasada; como su recepción está abierta, no se
   puede marcar ausente (sería falso) ni cancelar (ya pasó). Es exactamente el comportamiento que
   tenía como `EN_ESPERA`, así que la migración no habilita nada que antes no se pudiera.
4. `DROP CHECK ck_turno_espera_tiene_llegada` (ya no hay `EN_ESPERA`) y **`ADD CHECK
   ck_turno_estado`** con los cuatro estados de la reserva. `turno` no tenía CHECK de estado.
5. **No se borran columnas** (ADR-0007: nunca `DROP` en la misma release que introduce el
   reemplazo). `turno.llegada_en`, `llegada_por_cuenta_id` y `estado_antes_de_espera` quedan con
   su valor histórico y el código deja de mapearlas. Contraerlas es una migración posterior,
   cuando ninguna versión desplegada las use. Los eventos `LLEGADA`/`LLEGADA_DESHECHA` de
   `turno_evento` **no se tocan** (append-only): siguen en el historial del turno.

Los check-in deshechos antes de `V78` no se convierten en recepciones `ANULADA`: su único rastro
es `turno_evento` y ahí sigue.

## 7. Contrato `0.63.0` y compatibilidad

**Aditivo:** recurso nuevo `/consultorios/{c}/turnos/{t}/recepcion` (registrar llegada, ver,
historial, validar, Particular, espera, llamado, anulación), schemas `Recepcion` y
`EventoDeRecepcion`, campo `recepcion` en `TurnoDelDia`, problem type
`recepcion-transicion-no-permitida`.

**Lo que es incompatible y cómo se maneja:**

- `EN_ESPERA` en `Turno.estado` y `TurnoDelDia.estado`: el servidor **deja de emitirlo**. Sacarlo
  del enum publicado rompería la compilación del cliente generado en cualquier pantalla que
  compare contra él. **Se mantiene declarado, marcado deprecado en la descripción, durante una
  versión**; sacarlo es un cambio de versión mayor posterior. Los eventos históricos del turno
  siguen pudiendo traer `EN_ESPERA` en `estadoAnterior`/`estadoNuevo`.
- `POST /turnos/{t}/llegada` y `DELETE /turnos/{t}/llegada` quedan **deprecados** (`deprecated:
  true`) y siguen funcionando: el primero registra la llegada en la recepción y el segundo la
  anula. Lo que cambia es que el turno devuelto ya no viene en `EN_ESPERA`.
- `Turno.llegadaEn` queda deprecado: sólo lo completa el `POST` deprecado de llegada. La hora de
  llegada se lee de `TurnoDelDia.recepcion.llegadaEn` (y `TurnoDelDia.llegadaEn` se sigue
  completando desde la recepción, así la pantalla actual de recepción sigue mostrando la hora).

La pantalla de recepción del frontend tiene que pasar a leer `recepcion`; hasta entonces muestra
el turno en `RESERVADO`/`CONFIRMADO` con su hora de llegada.

## 8. Concurrencia

- **Check-in** corre en `READ_COMMITTED` y lee el turno con `SELECT … FOR UPDATE`. Dos check-in
  simultáneos se serializan sobre la fila del turno y el segundo encuentra la recepción del
  primero: **devuelve 200 idempotente, no un 409 por duplicado**. El unique de la base queda como
  red de seguridad.
- **Check-in contra cancelación**: el check-in no escribe ninguna columna del turno, así que el
  `@Version` del turno no lo vería (regla 3 de las reglas fijadas: un `@Version` del padre no
  protege escrituras sobre tablas hijas). Por eso, sólo cuando crea la recepción, **fuerza el
  incremento de la versión del turno** (`PESSIMISTIC_FORCE_INCREMENT`). Es legítimo por la
  recíproca de la misma regla: esa transacción no ensucia ninguna otra columna del turno, así que
  la versión avanza una sola vez.
- Las demás transiciones de recepción usan el `@Version` propio de la recepción con la versión
  que mandó el cliente, como las del turno.

## 9. Design challenge

1. **Ownership.** `recepcion` y `recepcion_evento` son de `scheduling`, como `turno` y
   `turno_evento`. Nadie más las toca: `person` responde la elegibilidad por su `spi` y `offering`
   la práctica por el suyo. `encounter` no las conoce. El reporte de turnos (`reporting` vía
   `scheduling.infrastructure.TurnosEnElReporte`) no las lee.
2. **Ciclos.** Las aristas nuevas son `scheduling → person.spi` (ya existía por
   `PacienteDirectory`) y `scheduling → offering.spi` (ya existía por `OfertaDirectory`). La única
   pieza nueva fuera de `scheduling` es `person.spi.ElegibilidadAdministrativaDirectory`,
   implementada dentro de `person`. `person` no depende de `scheduling` (al revés: `scheduling`
   implementa un contribuyente de `person.spi`). ArchUnit 5/5 en la verificación.
3. **Tenant.** Las dos tablas llevan `organization_id NOT NULL` y `consultorio_id`. El unique de
   vigencia empieza por `organization_id`; los índices de lectura también. Todas las consultas
   filtran por organización y sede, y un turno de otro tenant es 404 antes de mirar la recepción.
4. **Reglas maestras.** Turno ≠ Recepción ≠ Sesión: ninguna transición de recepción toca la
   sesión ni la deuda; `LLAMADA` no es "atendido". Validar no consume autorizaciones ni genera
   obligación (Cobertura del paciente ≠ Convenio ≠ Obligación). Particular no cambia la cobertura
   maestra.
5. **Baja lógica.** Nada se borra: anular y cerrar son estados con hora y motivo; los eventos son
   append-only (entidad con todas las columnas `updatable = false` y puerto sin update ni delete).
   La migración no borra columnas ni eventos.
6. **Contrato.** Aditivo con dos deprecaciones (§7). `EN_ESPERA` se conserva publicado una versión
   para no romper el cliente. Minor `0.63.0`.
7. **Ruta crítica.** Los cimientos están en `main`: cobertura aplicable (B-2), elegibilidad
   (03.06/M17), puente oferta↔práctica (A-9/DP-11) y consumo (C-4). E-6 (prepago) queda afuera y
   se apoya en esto.
8. **El caso que rompe el diseño.**
   - *Check-in concurrente con la cancelación del turno.* Sin control, la cancelación lee "sin
     recepción", el check-in inserta una, y queda un turno `CANCELADO` con un paciente "en
     espera". Se resuelve con la versión forzada del §8: si el check-in commitea primero, la
     cancelación pierde su `UPDATE … WHERE version = ?` y responde 409; si la cancelación
     commitea primero, el check-in —que toma la fila con `FOR UPDATE` en `READ_COMMITTED`— ve
     `CANCELADO` y responde 409. `RecepcionConcurrenteIT` lo ejerce en varias rondas y afirma el
     invariante: nunca un turno cancelado con recepción abierta.
   - *Turnos en espera de días pasados en la migración.* Ver §6.3: se conservan como
     `EN_ESPERA`, sin inventar el desenlace, y el turno no queda habilitado para nada que antes no
     admitiera. `MigracionRecepcionV78IT` aplica las migraciones hasta `V76` sobre un esquema
     propio, siembra un turno en espera de la semana pasada, uno de hoy, uno cancelado desde la
     espera y uno sin llegada, aplica `V78` y verifica cada fila.
   - *Ventana de despliegue.* Durante el rolling deploy una instancia vieja podría intentar
     escribir `EN_ESPERA` y chocar contra `ck_turno_estado`: falla con error de base en vez de
     dejar un dato que la versión nueva no entiende. Se acepta y se escribe.

## 10. Fuera de alcance y decisiones a revisar

- **El indicador `turnos-en-espera` del reporte de turnos pasa a dar siempre 0**, porque cuenta
  `turno.estado`. Ese archivo es de G-1 en este momento y no se toca acá; lo correcto es que la
  recepción aporte su propio indicador (o que el reporte cuente `recepcion` en `EN_ESPERA`).
- **Una recepción en espera de alguien que se fue sin ser atendido** no tiene salida propia: o se
  llama, o se anula (que dice que la llegada no valía). Un estado "se retiró" no está en DP-16.
- **Particular desde `EN_ESPERA`** (decidirlo después de pasar a espera con la observación
  abierta) no se admite: se decide antes de pasar a espera.
- **`billing` no lee la modalidad de la recepción.** F-4 (ya en `main`) devenga la parte del
  financiador cuando la oferta admite obra social y hay cobertura aplicable con arancel, **sin
  mirar la recepción**: una atención que el mostrador resolvió como Particular igual puede
  devengar al financiador. Cerrarlo es exponer la modalidad por `scheduling.spi` y que el
  devengado la consulte; es una decisión de producto (¿manda el mostrador o el convenio?) y se
  deja escrita, no resuelta.
- **La pantalla** (mitad web de E-4) no se hace en este paquete.
