# Design challenge — AKINE-08.01

Paso obligatorio de `CLAUDE.md` §3. **Manda sobre `AKINE-08.01-clases-programadas.md`.**

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿La clase vive en `scheduling` o en un módulo nuevo?

Dos tablas nuevas, **las dos propiedad del módulo nuevo `activity`**: `clase_programada` y
`clase_evento`. Ningún otro módulo las lee ni las escribe.

La pregunta de verdad es si la clase debía vivir en `scheduling`, y **la respuesta es no**. §2.4 del
plan asigna `activity` a M28–M29 —clases, inscripciones, participación, pases y abonos— y esta
etapa abre esa fase. Meterla en `scheduling` habría comprado la exclusión mutua gratis (mismo
módulo, mismas tablas, mismo lock) al precio de que 08.02 a 08.09 —inscripciones, lista de espera,
asistencia, derivación clínica, packs, créditos, abonos— terminaran todas dentro del módulo de
turnos. Eso no es un módulo de agenda: es el segundo entregable entero mal ubicado.

**Lo que `activity` NO posee y toca por `spi`:** `agenda_sede` sigue siendo de `scheduling` y
`activity` la bloquea **a través de `scheduling.spi.AgendaDeSede`**, nunca importando su
repositorio ni su entity. Es la misma línea que 05.02 ya había trazado al negarse a reusar la fila
de `consultorio_calendario` de `resource`.

> **Matiz honesto:** que un módulo tome un lock que pertenece a otro es acoplamiento, y hay que
> llamarlo por su nombre. Es acoplamiento **deliberado y explícito**, porque la alternativa —un
> segundo punto de serialización— no es menos acoplamiento, es el mismo acoplamiento sin
> exclusión. La forma elegida lo deja visible en el `spi` en vez de esconderlo.

**Veredicto: pasa.**

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

Aristas nuevas, todas salientes de `activity`: `activity → scheduling.spi`, `→ offering.spi`,
`→ organization.spi`, `→ resource.spi`, `→ person.spi`, `→ platform.spi`.

`scheduling` **no importa nada de `activity`**, y no lo va a hacer: lo que necesita saber de las
clases lo pregunta por dos interfaces declaradas en **su propio** `spi`
—`OcupacionExternaProbe` y `EventoExternoDeAgenda`— que `activity` implementa. Es la inversión
estándar del proyecto, la misma de `scheduling.spi.AtencionProbe` que implementa `encounter`. Una
interfaz implementada no es una arista de compilación.

`activity` es **hoja**: ningún módulo depende de ella. Un módulo hoja no puede cerrar un ciclo.

**Comprobado, no razonado.** El challenge de 04.05 dio por verificada de memoria una arista que sí
cerraba ciclo, y lo destapó una clase sonda. Acá se hizo lo mismo antes de escribir una línea de
dominio: se creó `activity.infrastructure.SondaDeCiclos` declarando **las ocho** dependencias que
el módulo va a tomar y se corrió `ModuleArchitectureTest` en verde
(`sin_ciclos_entre_modulos` usa `SlicesRuleDefinition`, que busca ciclos de cualquier longitud, no
sólo de dos). La sonda se borra al escribir las clases reales.

**Veredicto: pasa, verificado con ArchUnit.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí. `organization_id` en las dos tablas y **encabezando** todos los índices y el único unique:
`uk_clase_idempotencia (organization_id, idempotency_key)` —alcance organización y no sede, igual
que `V30`: una clave reusada apuntando a otra sede sigue siendo la misma clave—.

Los tres índices de `clase_programada` empiezan por `organization_id`, y los dos que deciden la
correctitud llevan además `deleted_key` antes del intervalo, para que las canceladas no entren y no
haya que filtrarlas después.

`consultorio_id` va porque el hecho pertenece a una sede (RN-M28-001), y va **aunque sea derivable**
de la oferta: derivarlo obliga a un join para filtrar por tenant y habilita la consulta sin join que
ningún test de una etapa detecta, porque los tests de una etapa usan un solo tenant.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿el diseño confunde Turno/Sesión, o disponibilidad con reserva?

Cuatro riesgos reales, uno por uno:

1. **Clase ≠ N turnos.** Es la regla propia de la etapa y su criterio de aceptación
   (CA-M28-001-06). Ninguna fila de `turno` representa una clase, ni se crea una al inscribir
   (08.02). Lo que comparten no es la tabla: es **el lock**.
2. **Turno es reserva; Sesión es atención realizada** (regla 4, DP-05). Una clase programada es
   **reserva de recursos**, no prestación. No crea Sesión, no toca `encounter`, no devenga nada, y
   `estado = PROGRAMADA` no prueba que nadie haya entrenado. RN-M28-007 lo dice explícito y su
   consecuencia llega en 08.03.
3. **Disponibilidad es oferta; la clase es reserva**, y no se fusionan. La clase **consume**
   disponibilidad y no la modifica: `resource` sigue siendo el dueño de la franja. La capacidad
   efectiva se calcula **al leer** y no se materializa — el mismo criterio de 02.04 y 05.01.
4. **Información histórica no se elimina** (regla 10). Ver punto 5.

Un quinto que conviene nombrar aunque no sea de esta etapa: **Obligación ≠ Cobro**. La clase no
devenga nada. Cuando 08.03 registre asistencia y 08.09 integre lo económico, la deuda se devengará
dentro de la transacción del hecho, con importe congelado y `DECIMAL`.

**Veredicto: pasa.**

## 5. Baja lógica — ¿hay borrado físico de información histórica relevante?

Ninguno. Cancelar una clase **no borra la fila**: `estado = CANCELADA`, `deleted_at`,
`motivo_cancelacion`, actor e instante, más un evento en `clase_evento`, que es append-only y cuyo
puerto **no declara `update` ni `delete`** —la única garantía real de inmutabilidad—.

Reprogramar tampoco recrea nada: es un `UPDATE` sobre la misma fila, y la clase conserva su
identidad (CA-M28-005-06).

**La desviación que hay que declarar:** no se usa el cuarteto completo
`active`/`deleted_at`/`deactivation_reason`/`deleted_key`. Falta `active` y el motivo se llama
`motivo_cancelacion`. **Es deliberado**: la clase ya tiene `estado`, y `active` sería una segunda
fuente de verdad sobre el mismo hecho. `V30` tomó exactamente esta decisión para `turno`, que es el
otro evento de agenda del sistema, y es más importante que los dos eventos de la misma grilla se
lean igual que que una clase se lea como un espacio.

**Veredicto: pasa, con la desviación declarada.**

## 6. Contrato — ¿el cambio de API es aditivo?

**Sí, estrictamente.** Siete operaciones nuevas —seis de `clases` y la agenda unificada— y
**ninguna existente cambia de forma**: `GET .../turnos?fecha=` devuelve exactamente lo que devolvía,
y la agenda unificada es un endpoint nuevo al lado, no un reemplazo. `0.33.0` → **`0.40.0`**, minor:
aditivo.

`RevalidadorDeSlot` cambia de **comportamiento** —una reserva de turno puede fallar ahora donde
antes pasaba, si hay una clase encima— pero **no de contrato**: el error es el
`recurso-ocupado` que esa operación ya declara, con otro `detail`. Un cliente en `0.33.0` no se
entera y no se rompe. Que sea aditivo en el contrato y no en el comportamiento es la parte que hay
que decir en voz alta: **es el punto de la etapa**, no un efecto colateral.

El YAML **no se edita a mano**. Queda en drift hasta que alguien regenere con Docker.

**Veredicto: pasa.**

## 7. Ruta crítica — ¿tiene sus cimientos, o lo estoy adelantando?

Los cimientos que M28 declara son M04 Espacios (02.02), M05 Profesionales (02.03/02.04), M07
Personas (03.01), M12 Agenda (05.01–05.03), M13 Recepción (05.04) y M27 Ofertas (02.06/02.07).
**Todos existen y están cerrados.** En particular existe lo único sin lo cual esta etapa no se
podía escribir: el **lock de `agenda_sede` y el patrón de exclusión de 05.02**.

**Dependencia declarada que NO está:** el plan pide AKINE-07.09 —gate y release del MVP—. No está
cerrada, y varias etapas de F7 siguen en vuelo en otros worktrees. Se avanza igual porque la
dependencia de 07.09 es de **release**, no técnica: 08.01 no consume ninguna capacidad que 07.09
produzca. **Queda anotado como desvío del plan**, no como algo resuelto.

### Qué entra acá y qué queda para 08.02 y 08.03 — explícito, porque esta etapa abre la fase

**Entra:** la clase como evento único de agenda (crear, reprogramar, cancelar, historial),
capacidad efectiva, exclusión mutua con el turno, y la proyección unificada discriminada.

**08.02 — inscripciones y cupos:** `InscripcionClase`, los seis estados de RN-M28-004, la ocupación
real (que hoy es `0` en un único lugar del servicio, con la etapa destino escrita al lado), la lista
de espera, la concurrencia del último cupo y las notificaciones al reprogramar o cancelar.

**08.03 — asistencia:** asistencia por participante, detalle operativo y la consecuencia económica,
**sin crear un turno por participante**.

Dos límites que esta etapa deja escritos para no sorprender a las siguientes: la regla de "no bajar
la capacidad por debajo de la ocupación confirmada" **está implementada y hoy lee cero** —08.02 la
enciende sin escribir nada nuevo—, y **ninguna respuesta de 08.01 lleva lista de participantes**,
decisión de seguridad que 08.02 no debe deshacer por inercia.

**Veredicto: pasa, con el desvío de 07.09 declarado.**

## 8. El caso que rompe el diseño

**"Se reserva un turno encima de una clase y nadie se entera."** Y su gemelo: dos clases pisándose,
y una clase encima de un turno.

Por qué es el caso: ningún unique puede expresarlo —09:00–10:00 y 09:30–10:00 no comparten un valor
de columna, y MySQL 8.4 no tiene exclusion constraints— y las dos escrituras viven en **módulos
distintos**, así que la tentación natural es que cada módulo serialice lo suyo. Si cada uno toma su
propio lock, las dos transacciones **no se ven** y las dos ganan: el box queda doblemente vendido y
el sistema no reporta ningún error. Sería un defecto silencioso, el peor tipo.

**Cómo se resuelve, en una línea: la clase se disputa la misma fila de `agenda_sede` que el turno.**
Un único punto de serialización por sede, tomado antes de leer nada, en `READ_COMMITTED`, con la
fila-lock creada en una transacción aparte.

Desarmado en los cuatro pares:

| | gana | cómo |
|---|---|---|
| turno vs turno | ya resuelto en 05.02 | lock + `RevalidadorDeSlot` |
| **clase vs turno** | el que toma el lock primero | `ClaseService` consulta turnos por `scheduling.spi.AgendaDeSede` bajo el lock |
| **turno vs clase** | ídem | `RevalidadorDeSlot` consulta clases por `OcupacionExternaProbe`, bajo el mismo lock |
| **clase vs clase** | ídem | repositorio propio, bajo el mismo lock |

Los cuatro se serializan porque **las cuatro escrituras se disputan la misma fila antes de leer**.
La que llega segunda lee el estado ya commiteado por la primera y falla con `recurso-ocupado`.

**Tres cosas que esto cuesta, y que no se esconden:**

1. **Contención.** Toda escritura de agenda de una sede —turnos y ahora también clases— se
   serializa. Es la decisión que `V30` ya había tomado a conciencia: corrección sobre paralelismo,
   porque un centro de kinesiología hace unas pocas por minuto. Una clase por día y por sala no
   mueve la aguja.
2. **Un módulo bloquea una fila de otro.** Ver el matiz del punto 1.
3. **Nada de esto se ejerció contra MySQL real**, porque Docker no arranca. Y es exactamente lo que
   un mock no puede probar: un mock que devuelve cero filas no reproduce el gestor de locks de
   InnoDB. **No se simula.** Queda en `docs/tests-diferidos.md` con etapa destino, como el escenario
   que hay que correr en el primer `verify` con Docker vivo, junto con el de que `V58` siquiera
   ejecute.

**Veredicto: pasa en diseño, con la verificación concurrente explícitamente diferida y sin
sustituto falso.**

---

## Resultado

**Ocho de ocho pasan.** Tres cosas quedan escritas y no resueltas, y ninguna bloquea el diseño:
el acoplamiento deliberado del lock cruzado (1), el desvío de la dependencia 07.09 (7) y la
verificación concurrente diferida por Docker (8).

Se avanza a `tasks`.
