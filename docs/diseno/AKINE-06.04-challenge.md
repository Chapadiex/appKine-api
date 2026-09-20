# Design challenge — AKINE-06.04 · Tratamientos realizados

> Revisión adversarial obligatoria del §3 de `CLAUDE.md`, previa a escribir código.
> **Manda sobre `AKINE-06.04-tratamientos-realizados.md`** donde discrepen.

Veredicto global: **el diseño pasa**, con tres decisiones que se derivan al usuario (§9) y una
corrección que este challenge le impuso al diseño (§4).

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

**Propietario: `encounter`. Las dos tablas. Ningún otro módulo las lee ni las escribe.**

| Tabla | Propietario | Por qué |
|---|---|---|
| `tratamiento_realizado` | `encounter` | Cuelga de `sesion`, que es de `encounter` desde V33 |
| `tratamiento_parametro` | `encounter` | Cuelga del tratamiento |

Las FK salen hacia `practica` (de `resource`), `espacio` (de `resource`) y `consultorio` /
`organization`. **Una FK que cruza módulos no es una violación de ownership** y hay precedente
explícito: V48 declaró `fk_sesion_caso` hacia `caso_clinico`, que es de `clinical`, con el
argumento de que *"lo que la regla 1 prohíbe es que `encounter` LEA o ESCRIBA esa tabla, no que el
motor proteja la integridad"*.

Lo que `encounter` **no** hace es consultar `practica` ni `espacio`: pregunta por
`resource.spi.CatalogoDirectory` y `resource.spi.EspacioDirectory`.

**Objeción considerada y rechazada: ¿no debería ser de `clinical`?** El plan dice *"rutas
`clinical`, `resource` y componentes de tratamiento"*, escrito cuando se asumía que la Sesión
viviría en `clinical`. No vive ahí: DP-10 la dejó en `encounter` y V33 la creó ahí. Poner la hija
en `clinical` y la madre en `encounter` obligaría a `clinical` a escribir filas cuya sesión no
puede leer. **El plan está desactualizado en este punto, no el diseño.**

---

## 2. Ciclos — ¿la dependencia entre módulos es unidireccional? ¿Pasa ArchUnit?

**Arista nueva: `encounter → resource.spi`.** Es la única.

**Verificada con una clase sonda y ArchUnit, NO razonando el grafo.** Es el precedente que el
challenge de 04.05 dejó pagado: dio por bueno de memoria que `person → encounter.spi` no cerraba
ciclo y era falso, porque `SlicesRuleDefinition` busca ciclos de **cualquier longitud** y la cabeza
encuentra los de dos.

Procedimiento ejecutado: se creó `encounter.infrastructure.SondaCicloTemporal` importando
`CatalogoDirectory` y `EspacioDirectory`, y se corrió `ModuleArchitectureTest`.

```
Tests run: 5, Failures: 0, Errors: 0 — com.akine.architecture.ModuleArchitectureTest
```

Verde. La sonda se borró acto seguido.

Que además sea explicable ayuda pero **no fue la prueba**: `resource` sólo alcanza `organization` y
`platform`, y ninguno de los dos alcanza `encounter`. Y el `spi` de `resource` fue escrito
**para** este consumidor — `EspacioDirectory` dice literalmente *"RF-M04-005, espacio realmente
utilizado en la sesión"*.

La otra arista que el diseño toca, `encounter → person.spi`, **ya existía** (04.05) y sólo se le
agrega un campo a un record.

---

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

**Sí, las dos, y `consultorio_id` en `tratamiento_realizado`** porque el hecho pertenece a una sede.

| Constraint / índice | Forma |
|---|---|
| `uk_tratamiento_orden` | `(organization_id, sesion_id, orden, deleted_key)` |
| `ix_tratamiento_sesion` | `(organization_id, sesion_id, orden)` |
| `ix_tratamiento_practica` | `(organization_id, practica_id)` |
| `uk_tratamiento_parametro_clave` | `(organization_id, tratamiento_realizado_id, clave)` |

Todos empiezan por `organization_id`. **Ninguna consulta resuelve por id pelado**: el repositorio
filtra siempre por tenant y sesión, y el acceso pasa antes por `findByIdInScope` de la sesión, que
ya filtra por sede.

**`tratamiento_parametro` lleva `organization_id` aunque sea redundante** —se podría derivar del
padre—. Es la regla de `AGENT.md` §5 sin excepción, y la redundancia es lo que permite que el
unique de clave sea tenant-safe sin un join.

Cross-tenant → **404**. Falta de contexto → **403**. Sin desvíos.

---

## 4. Reglas maestras — ¿el diseño confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?

No, y en un punto el challenge **corrigió** al diseño.

- **HC ≠ Caso ≠ Sesión (1):** el tratamiento cuelga de la **Sesión** y de nada más. No lleva
  `caso_id` ni `historia_clinica_id`: se resuelven por la sesión, y duplicarlos habilitaría que
  discrepen. Mismo criterio con el que la sesión no guarda `persona_id`.
- **Plan ≠ Turno ≠ Sesión (2) y RN-M14-004:** es el eje de la etapa. `tratamiento_realizado` no
  tiene ninguna referencia a `plan_item`, ni columna que diga "cumple el ítem X". Atarlos haría que
  registrar lo realizado moviera el plan, que es la confusión exacta que 04.04 se negó a cometer.
- **Turno ≠ Sesión (4):** no se toca el turno. El espacio realmente usado **no** actualiza el
  espacio asignado al turno.
- **Obligación ≠ Cobro ≠ Caja (6,7):** no se toca nada económico. La cantidad consumida por sesión
  **no cambia** (diseño §11) precisamente para no decidir plata de callado.
- **Regla maestra 11 —la efectiva se calcula al leer—:** aplicada. Sin columna de resumen en
  `sesion` (diseño §8).

**La corrección que este challenge impuso:** el borrador del diseño validaba ocupación y capacidad
del espacio, porque el plan las lista entre las validaciones. Es **la regla maestra 4 invertida**:
un tratamiento realizado es un hecho consumado y rechazarlo por ocupación no evita la
sobreocupación —ya ocurrió— sino que impide documentarla. La ocupación es regla de **reserva**.
Quedó como diseño §5.1.

---

## 5. Baja lógica — ¿hay borrado físico de información histórica relevante?

**No.** Cuarteto completo `active` / `deleted_at` / `deactivation_reason` / `deleted_key` con
centinela `'1970-01-01'`, y `deleted_key` dentro del unique de `orden`. `orden` **no se reutiliza**.

**La única excepción, y es deliberada: `tratamiento_parametro` se borra físicamente en el `PUT`.**

Hay que defenderlo porque es un `DELETE` real:

- Un parámetro **no tiene identidad ni historia propias**. Es un atributo del tratamiento, como lo
  sería una columna. Nadie referencia un parámetro; no hay auditoría que lo nombre.
- El `PUT` reemplaza el tratamiento **completo**, y la fila del tratamiento sí conserva su
  identidad y su rastro.
- Versionar parámetros sería construir el mecanismo de `entrada_clinica` (04.02) para algo que sólo
  se edita mientras la sesión está en borrador — **y una sesión cerrada no se edita** (diseño §7).
  Cuando la sesión se cierra, los parámetros quedan congelados para siempre.

Si mañana se pudiera editar un tratamiento de una sesión **cerrada**, esta excepción deja de valer
y hay que versionarlos. Ese día es **06.06**, y queda anotado acá para que lo lea quien la escriba.

---

## 6. Contrato — ¿el cambio de API es aditivo? ¿Requiere versión mayor?

**Aditivo puro. Versión menor: `0.37.0`.**

Cuatro operaciones nuevas bajo un path nuevo. **Ninguna operación existente cambia de forma.**

Lo que sí cambia y hay que mirar: **`SesionCerrada` y `ConsumoPorSesion` crecen un campo cada uno.**
Son records de `spi`, **internos al backend**: no aparecen en el OpenAPI y ningún cliente los ve.
Agregar un componente a un record Java **rompe la compilación de todo constructor existente**, que
es el efecto deseado —el compilador señala cada llamador— y es también la trampa del merge limpio
que no compila. Los llamadores están en `SesionService` y `ConsumoDeAutorizacionEnCierre`, los dos
en esta rama.

**`DELETE` responde 204 y el controller declara `application/json` en `produces`.** Sin eso el
cliente generado come 406 antes de entrar al método.

El contrato **no se puede regenerar sin Docker** y queda en drift declarado. El YAML no se edita a
mano.

---

## 7. Ruta crítica — ¿este módulo tiene sus cimientos, o lo estoy adelantando?

**Los tiene todos. Esta etapa llega tarde, no temprano.**

Dependencias declaradas por el plan: **02.02** (espacios), **02.05** (catálogo clínico) y **06.03**.

| Dependencia | Estado |
|---|---|
| 02.02 — `espacio`, `EspacioDirectory` | Cerrada (V19) |
| 02.05 — `practica`, `CatalogoDirectory` | Cerrada (V20) |
| 06.01 / 06.02 / 06.05 — Sesión, evaluación, cierre | Cerradas |
| 04.05 — consumo de autorizaciones | Escrita, en esta misma rama |

**La anomalía que hay que declarar: 06.03 no está cerrada.** Corre en otro worktree y tiene
reservadas `V51` y `V52`. El plan pone 06.03 como dependencia de 06.04.

**No bloquea, y la razón es concreta:** 06.03 es "examen completo" —más profundidad en la
evaluación, que vive en columnas de `sesion`—. Esta etapa **no toca `sesion`** (diseño §5) y no lee
ningún campo de evaluación. El acoplamiento previsto era que las dos escribieran en la misma tabla,
y como ésta no escribe ahí, no hay conflicto ni de esquema ni de código. **V55 no colisiona con
V51–V54 porque las versiones se reservaron antes de escribir.**

**06.05 ya está cerrada y el plan la declara dependiente de 06.04**, o sea que el orden real ya se
invirtió antes de que yo llegara. Esta etapa lo repara parcialmente: §3.1 le arregla a 06.05 la
imputación de su cierre.

---

## 8. El caso que rompe el diseño

### 8.1 El nombrado: el paciente con dos coberturas y una práctica que sólo una cubre

Paciente con dos autorizaciones aprobadas y vigentes:

| Autorización | Práctica | Vence | Saldo |
|---|---|---|---|
| **A** | Fonoaudiología | 30/09 | 10 |
| **B** | Kinesiología | 31/12 | 4 |

Se cierra una sesión donde se realizó **kinesiología**.

- **Hoy (04.05):** se elige "la que vence antes" → **A**. Se descuenta una unidad de
  **fonoaudiología** por una sesión de kinesiología. El saldo de B no se mueve. Nadie se entera:
  el log dice `Autorizacion consumida` y el desenlace es `CONSUMIDA`. **El defecto declarado, en
  vivo.**
- **Con este diseño:** el filtro por práctica deja sólo **B**. Se descuenta de B. A queda intacta.

**Y la variante que de verdad tensiona el diseño:** misma sesión, pero **sin** autorización de
kinesiología. Hoy consumiría A. Con este diseño **no consume nada** y devuelve
`SIN_AUTORIZACION_PARA_LA_PRACTICA`.

¿Es una regresión? **No, y hay que poder defenderlo:** el cierre clínico **no se bloquea** —el
observador no lanza, DP-06 intacto—, y el desenlace queda en el log y en la elegibilidad. Lo que
antes pasaba era que el centro le presentaba al financiador una unidad de fonoaudiología por una
sesión de kinesiología, y eso no es un consumo: es una declaración falsa que además le come al
paciente unidades que iba a necesitar.

### 8.2 El que casi rompe el diseño y obligó a escribir §3.3

**Todas las sesiones ya cerradas, y las que nunca van a tener tratamientos.**

Si el filtro por práctica se aplicara siempre, una sesión sin tratamientos registrados —que es
**toda** sesión anterior a esta etapa, y también las de ofertas que no registran prácticas—
tendría conjunto vacío de prácticas, ninguna autorización matchearía y **el sistema dejaría de
consumir autorizaciones por completo**.

Una etapa que "arregla la imputación" y apaga el consumo del sistema entero es peor que el defecto.

**Cómo lo resuelve:** el conjunto vacío significa *"no sé qué prácticas fueron"*, no *"ninguna"*.
Ante esa ignorancia se conserva **exactamente** el comportamiento de hoy. El filtro se aplica sólo
cuando hay dato con el cual filtrar.

### 8.3 El de concurrencia

Dos pestañas del mismo profesional agregando un tratamiento a la misma sesión a la vez. Las dos
leen `sesion` en versión N, las dos calculan `orden = 3`.

**Lo resuelve el `OPTIMISTIC_FORCE_INCREMENT`:** las dos emiten
`UPDATE sesion SET version = N+1 WHERE version = N`; la segunda afecta cero filas y come 409. Nunca
llegan las dos al `INSERT`. El `UNIQUE (organization_id, sesion_id, orden, deleted_key)` es el
respaldo, no el mecanismo.

**Y el que el force-increment podría causar y no causa:** la versión avanza **una** sola vez,
porque la escritura no toca ninguna columna de `sesion`. Es la recíproca que 04.02 pagó. Queda
anotado en diseño §4 que **agregar una columna de resumen a `sesion` obligaría a sacar el
force-increment**.

> **Sin verificar contra base real.** Nada de esto se ejerció contra MySQL: Docker no arranca. El
> `CHECK` de tipado de parámetros, el unique con `deleted_key` y el comportamiento del
> force-increment están razonados y probados con mocks, **no ejecutados**. Va a
> `docs/tests-diferidos.md`.

---

## 9. Decisiones que este challenge deriva al usuario

Ninguna se inventa acá.

1. **¿El avance del plan pasa a contarse por práctica?** Exige `plan_item.practica_id`, altera una
   tabla de `clinical`, reabre 04.04 y obliga a decidir si un plan se planifica por oferta o por
   práctica (diseño §3.4). **Mientras no se decida, el avance sigue por oferta y el límite de 04.04
   sigue abierto.**
2. **¿Qué parámetros exige cada práctica?** `plan_sesiones.txt` §10.4 los pide condicionales por
   tipo y no existe configuración que los declare. Esta etapa valida que estén **tipados**, no
   cuáles son obligatorios.
3. **¿Una sesión con cinco tratamientos consume cinco unidades o una?** Sigue consumiendo **una**.
   Cambiarlo es una decisión económica sin RF que la respalde.
