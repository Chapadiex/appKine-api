# AKINE-02.04 — Disponibilidad semanal, excepciones y feriados

> Diseño de etapa. Módulo propietario: `resource`. Contrato OpenAPI **0.11.0** (aditivo).
> Migraciones **V22** y **V23**. ADR nueva: **ADR-0022**.

Trazabilidad: RF-M05-003 (configurar disponibilidad semanal), RF-M05-004 (registrar
excepción), RF-M05-005 (modificar disponibilidad detectando conflictos);
RN-M05-001 (la disponibilidad pertenece a Profesional + Consultorio),
RN-M05-002 (las excepciones prevalecen sobre el horario base),
RN-M05-003 (desvincular no elimina autoría histórica),
RN-M05-004 (los turnos futuros afectados quedan visibles para resolución).

---

## 0. Nota previa sobre las fuentes

El plan de implementación cita `Adicional.txt §2–3` como insumo de esta etapa.
**Ese archivo no existe en `docs/`.** El diseño se construye sobre RF-M05-003..005,
RN-M05-001..002 y lo que las etapas anteriores ya dejaron construido. Si el archivo
aparece, hay que revisar esta sección antes que ninguna otra.

---

## 1. Decisiones cerradas con el usuario

| Decisión | Resuelto | Alternativa descartada |
|---|---|---|
| Recurrencia | Bloques recurrentes con vigencia; la efectiva se **calcula al leer** | Materializar días concretos; RRULE iCalendar |
| Husos | Bloques en **hora local + día de semana**; el huso sale de `consultorio.timezone` | Guardar instantes UTC |
| Feriados | **Calendario nacional seedeado** (tabla global) **+ cierres manuales** | Solo cierres manuales; calendario por sede |
| Permiso del `PROFESIONAL` | **Solo lectura** | Cargar sus propias ausencias; editar todo lo propio |
| Módulo propietario | **`resource`** | `organization`; módulo `scheduling` nuevo |
| Alcance de la etapa | Backend + contrato + frontend | Solo backend; backend + E2E reales |

### Por qué hora local y no UTC

Un "lunes 09:00" tiene que seguir siendo 09:00 después de un cambio de huso o de horario
de verano. Guardado como instante UTC se corre solo, y el corrimiento aparece meses después
como turnos desfasados una hora sin que nadie haya tocado nada.

### Por qué el feriado nacional no cierra el centro por sí solo

Muchos centros de kinesiología atienden los feriados. Un `feriado` es un **hecho del
calendario**, no una decisión operativa: la decisión es de la sede y vive en
`consultorio_calendario.cierra_por_feriado`, con la posibilidad de sobreescribir un feriado
puntual mediante una excepción de `APERTURA`.

### Por qué no se crean permisos nuevos

**Lecturas con `colaborador:read`. Mutaciones con `consultorio:manage`.** Los dos códigos ya
existen en la matriz §5, son de F1 y están aprobados.

- `colaborador:read` — "Listar colaboradores del alcance". La matriz §6 se lo da a
  `PROFESIONAL` y `ADMINISTRATIVO` con alcance Consultorio. **La disponibilidad de un
  profesional es información de un colaborador**, así que el código aplica sin estirarlo.
- `consultorio:manage` — la matriz §6 se lo **niega** a `PROFESIONAL` y `ADMINISTRATIVO`.

De ahí sale "solo lectura" sin inventar ningún código y sin sumar nada a la lista de permisos
propuestos y sin aprobar (`catalogo:read`, `catalogo:manage`).

**No se usa `espacio:read`**, aunque su descripción diga "y la disponibilidad de una sede":
esa es la disponibilidad **del espacio físico** (M04, RF-M04-003), otro concepto. Ver §11.

---

## 2. Modelo de datos

### 2.1 `feriado` — global, sin `organization_id` (V22)

```
id, pais CHAR(2) NOT NULL DEFAULT 'AR', fecha DATE NOT NULL, nombre VARCHAR(160) NOT NULL,
tipo CHECK IN ('INAMOVIBLE','TRASLADABLE','PUENTE','NO_LABORABLE','RELIGIOSO'),
created_at, updated_at
UNIQUE (pais, fecha)
INDEX (pais, fecha)
```

Un feriado nacional no pertenece a ninguna organización. Mismo precedente que los catálogos
clínicos globales: **ADR-0021**. Requiere **ADR-0022** declarando la excepción a ADR-0004,
igual que ADR-0019 la declaró para `identity` y ADR-0021 para los catálogos.

El seed de Argentina se carga por año en V22. Los feriados trasladables y los puentes se
deciden por decreto: el seed **envejece**, y por eso la sede siempre puede cargar excepciones
propias sin depender de que el seed esté al día.

### 2.2 `consultorio_calendario` — política de la sede, propiedad de `resource` (V23)

```
id, organization_id NOT NULL, consultorio_id NOT NULL,
pais CHAR(2) NOT NULL DEFAULT 'AR',
cierra_por_feriado TINYINT(1) NOT NULL DEFAULT 1,
version, created_at, updated_at
UNIQUE (organization_id, consultorio_id)
```

Lo natural sería una columna en `consultorio`. **Esa tabla es de `organization`**, y que
`resource` la escriba rompe la pregunta §1 del design challenge. Tabla propia, aunque hoy
lleve un solo flag con valor operativo.

La fila **se crea a demanda** con los valores por defecto la primera vez que la sede se
consulta o se edita. Eso importa para §5: es la fila que serializa los writes.

### 2.3 `profesional_disponibilidad` — los bloques recurrentes (V23)

```
id, organization_id NOT NULL, consultorio_id NOT NULL, membership_id NOT NULL,
dia_semana TINYINT NOT NULL,             -- ISO-8601: lunes = 1 .. domingo = 7
hora_desde TIME NOT NULL,                -- hora LOCAL de la sede
hora_hasta TIME NOT NULL,                -- exclusivo
vigencia_desde DATE NOT NULL,
vigencia_hasta DATE NULL,                -- exclusivo; NULL = sin fin previsto
active TINYINT(1) NOT NULL DEFAULT 1, deleted_at DATETIME(6) NULL,
deactivation_reason VARCHAR(280) NULL,
version, created_at, updated_at

CHECK (dia_semana BETWEEN 1 AND 7)
CHECK (hora_hasta > hora_desde AND hora_hasta <= '24:00:00')
CHECK (vigencia_hasta IS NULL OR vigencia_hasta > vigencia_desde)
CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
    OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL))
INDEX (organization_id, consultorio_id, membership_id, dia_semana, vigencia_desde)
```

**La doble ventana es deliberada y ya la usa `espacio`:** `vigencia_*` es la ventana
*operativa* (desde cuándo atiende ese bloque) y `active/deleted_at` es el ciclo de vida
*administrativo* (bloque cargado por error). Mezclarlas pierde la diferencia entre "dejó de
atender los martes en marzo" y "esto nunca debió existir".

**`hora_hasta <= '24:00:00'` resuelve el cruce de medianoche sin permitirlo.** MySQL acepta
`'24:00:00'` en una columna `TIME`. Un bloque nocturno se carga como dos: lunes 22:00–24:00 y
martes 00:00–02:00. Si se permitiera `22:00–02:00` en una sola fila, toda comparación
`desde < hasta` mentiría, y el solapamiento dejaría de ser detectable con aritmética simple.

**No hay unique que impida el solapamiento.** MySQL 8.4 no tiene *exclusion constraints* —
son de PostgreSQL. El solapamiento se resuelve en aplicación: ver §5.

### 2.4 `disponibilidad_excepcion` — cierres y aperturas (V23)

```
id, organization_id NOT NULL, consultorio_id NOT NULL,
membership_id BIGINT NULL,               -- NULL = excepción de la SEDE entera
tipo   CHECK IN ('CIERRE','APERTURA'),
motivo CHECK IN ('AUSENCIA','LICENCIA','FERIADO','BLOQUEO','AMPLIACION','OTRO'),
fecha_desde DATE NOT NULL, fecha_hasta DATE NOT NULL,   -- exclusivo
hora_desde TIME NULL, hora_hasta TIME NULL,             -- ambos NULL = día completo
feriado_id BIGINT NULL,                                 -- FK -> feriado
notes VARCHAR(280) NULL,
active, deleted_at, deactivation_reason, version, created_at, updated_at

CHECK (fecha_hasta > fecha_desde)
CHECK ((hora_desde IS NULL AND hora_hasta IS NULL)
    OR (hora_desde IS NOT NULL AND hora_hasta IS NOT NULL
        AND hora_hasta > hora_desde AND hora_hasta <= '24:00:00'))
INDEX (organization_id, consultorio_id, fecha_desde, fecha_hasta)
INDEX (organization_id, consultorio_id, membership_id, fecha_desde)
```

- **`membership_id NULL` significa alcance sede, no hueco.** Es exactamente el mismo
  significado que `consultorio_id NULL` ya tiene en `membership` (V10) y en
  `colaborador_invitacion` (V21).
- **`hora_* NULL` = día completo.** Cubre el caso borde "excepción parcial" sin una columna
  de flag adicional: si vienen las horas, la excepción recorta una franja; si no vienen,
  tapa el día.
- **`APERTURA` no es decorativa.** RF-M05-004 pide "ausencia, licencia, bloqueo **o ampliación
  excepcional**". Es lo que permite atender un feriado o sumar una banda extra un sábado
  puntual.

### 2.5 Lo que no hay, a propósito

**No hay tabla de disponibilidad efectiva materializada.** Obligaría a un job que la
reescriba, y entre que una regla cambia y el job corre la base diría una cosa distinta de la
verdad. Es el mismo razonamiento por el que V21 no guarda el estado `EXPIRADA`: lo que se
puede derivar del reloj no se almacena, porque lo almacenado se desincroniza y lo derivado no.

---

## 3. Módulos, SPI y dependencias

Propietario de las cuatro tablas: **`resource`**. Ningún otro módulo las toca.

`resource` ya depende de `organization.spi` — `EspacioService` importa `ConsultorioDirectory`,
`PermissionGuard` y `AccountContextDirectory`. Esta etapa **no agrega ninguna flecha nueva
entre módulos**; agrega superficie a un SPI que ya existe.

### SPI nuevo en `organization` (aditivo)

`MembershipSnapshot` de hoy **no expone `consultorioId` y no se puede buscar por id**, así que
no alcanza para validar "esta membership es de esta sede y estaba vigente en esta fecha".

```java
// organization.spi
public record ConsultorioMembershipSnapshot(
        long membershipId, long accountId, long organizationId, Long consultorioId,
        String roleCode, Instant validFrom, Instant validUntil, boolean active) {
    public boolean validAt(Instant momento) { /* vigencia Y baja lógica */ }
}

public interface MembershipDirectory {
    Optional<ConsultorioMembershipSnapshot> find(long organizationId, long membershipId);
}
```

Registro nuevo en vez de agregarle campos a `MembershipSnapshot`: ese record ya lo consume
`identity`, y sumarle componentes rompe a todos sus constructores.

### SPI nuevo en `resource` — la costura de F5

```java
// resource.spi
public interface DisponibilidadImpactProbe {
    record Impacto(long turnosAfectados, Instant primero) { }
    Impacto turnosEn(long organizationId, long consultorioId, long membershipId,
                     Instant desde, Instant hasta);
}
```

**RF-M05-005 pide detectar conflictos con turnos futuros y los turnos no existen.** El módulo
`scheduling` es F5. La costura se declara ahora, con implementación vacía, exactamente como
`EspacioOccupancyProbe` en 02.02 — y como allá, el DTO documenta que hoy devuelve cero y que
un cliente escrito hoy sigue funcionando cuando deje de hacerlo.

`resource` implementa además `ColaboradorDesvinculacionProbe` (que ya existe en
`organization.spi`) para que, al desvincular a alguien, la pantalla muestre cuántos bloques
futuros de disponibilidad quedan colgando.

---

## 4. Cálculo de la disponibilidad efectiva

Entrada: `(organizationId, consultorioId, membershipId, desde, hasta)` en **fechas locales**
de la sede. Salida: intervalos con `Instant` y **la regla que los produjo**.

```
1. timezone := consultorio.timezone      (ConsultorioDirectory; NOT NULL desde V18)
2. membership válida en la ventana       (MembershipDirectory.validAt)
   - si no lo está: la disponibilidad efectiva es vacía. No es un error.
3. por cada fecha local F en [desde, hasta):
   a. BASE     := bloques con dia_semana = ISO(F), activos, cuya vigencia cubre F
   b. APERTURA := BASE union excepciones APERTURA que cubren F (de la sede y del profesional)
   c. FERIADO  := si existe feriado(pais, F) Y consultorio_calendario.cierra_por_feriado
                  -> si NO hay APERTURA que cubra F: el día queda vacío, razonVacio = FERIADO
                  -> si HAY APERTURA que cubre F: el día parte SOLO de esas aperturas;
                     los bloques base de (a) se DESCARTAN para esa fecha
   d. EFECTIVA := APERTURA menos excepciones CIERRE que cubren F (sede y profesional)
                  menos FERIADO
4. convertir cada intervalo local a Instant con ZoneId(timezone)
5. anotar en cada intervalo qué regla lo creó y qué regla lo recortó
```

**El orden lo fija RN-M05-002: las excepciones prevalecen sobre el horario base.** Por eso
`CIERRE` se aplica **último** y puede recortar incluso lo que abrió una `APERTURA`. Es
determinista y no depende del orden de inserción de las filas.

### Por qué una apertura en un feriado descarta el horario base

Este punto decía otra cosa hasta el 26/08/2026 y estaba mal. La versión anterior hacía que la
apertura **solo cancelara** el cierre por feriado, lo que deja vivos los bloques base.

El caso que lo rompe: un centro trabaja los lunes de 08:00 a 18:00 y su política es cerrar los
feriados. El 25 de diciembre cae lunes y el admin declara "este año abrimos de 10:00 a 14:00".
Con la regla vieja el día resolvía a `08:00–18:00 ∪ 10:00–14:00 = 08:00–18:00`: **la apertura no
servía para nada** y el sistema ofrecía ocho horas de turnos un día que el centro pensaba abrir
cuatro. Declarar una apertura especial tiene que significar que la apertura *es* el día.

Consecuencia que hay que tener presente: una apertura de **alcance sede** (`membership_id NULL`)
en un feriado descarta el horario base de **todos** los profesionales de esa sede ese día. Es
coherente con la regla, pero una apertura de sede mal cargada recorta la agenda de todo el mundo,
así que la pantalla avisa al guardar (§7).

Cuando el feriado **no** cierra —porque la sede tiene `cierra_por_feriado = false`— no pasa nada
especial y los bloques base aplican normalmente.

**El paso 5 no es un extra: es el criterio de aceptación.** El plan pide que "la
disponibilidad efectiva sea determinista y **explique qué regla la afecta**". Sin el origen
en la respuesta, el CA no se puede declarar cubierto y la pantalla no puede mostrarle al
admin por qué un martes quedó vacío.

### Horario de verano

La conversión local → `Instant` usa `ZoneId` de la sede. Dos casos que hay que resolver
explícitamente aunque Argentina hoy no aplique DST — la columna `timezone` admite cualquier
huso:

- **Hueco (adelanto):** la hora local no existe. `ZonedDateTime` la corre hacia adelante.
- **Solapamiento (atraso):** la hora local ocurre dos veces. Se toma el **primer** offset.

Se documenta y se testea. Nunca se usa `LocalDateTime.toInstant(ZoneOffset)` con un offset
fijo: eso es exactamente el bug que aparece seis meses después.

---

## 5. Solapamiento y concurrencia

**MySQL 8.4 no puede impedir el solapamiento con una constraint.** No existen exclusion
constraints ni índices únicos parciales: son de PostgreSQL. El chequeo va en aplicación, y
por lo tanto necesita serialización real o dos requests concurrentes insertan dos bloques que
se pisan y ninguno de los dos ve al otro.

**Un `SELECT ... FOR UPDATE` sobre los bloques existentes no alcanza**, y esto ya nos costó
tiempo antes: bloquear filas que existen no impide que otra transacción **inserte** una fila
nueva en el hueco.

**Lo que se usa:** antes de validar el solapamiento, la transacción toma
`SELECT ... FOR UPDATE` sobre la fila de **`consultorio_calendario` de esa sede** — que
siempre existe porque se crea a demanda. Eso serializa los writes de disponibilidad por sede.
Es aceptable: editar horarios es una acción administrativa de baja frecuencia, no un camino
caliente.

**Dos trampas que hay que evitar explícitamente:**

- **Nunca leer primero sin `FOR UPDATE` y bloquear después.** La escalada S→X con dos
  transacciones en el mismo camino es un deadlock, no una espera.
- El bloqueo se toma **al principio** de la transacción, antes de cualquier lectura de
  bloques.

Para el read-modify-write de una edición se suma el **bloqueo optimista** por `version`, igual
que en `espacio`: una versión vieja produce `409 concurrent-modification` en vez de pisar el
cambio ajeno en silencio.

### Idempotencia (CA-M05-003-05 y CA-M05-004-05)

Sin `Idempotency-Key`. Un alta cuyo `(membership, día, horas, vigencia)` coincide **exacto**
con un bloque activo existente devuelve **200 con ese bloque**, no un duplicado y no un 409.
Un alta que **solapa sin coincidir** devuelve **409**. Un reintento de red cae siempre en el
primer caso, que es lo que el criterio pide.

---

## 6. Contrato — OpenAPI 0.11.0

Cambio **aditivo**: bump menor, sin ventana de compatibilidad.

| Método | Path | Autorización |
|---|---|---|
| `GET` | `/api/v1/consultorios/{cid}/profesionales/{mid}/disponibilidad` | `colaborador:read` |
| `POST` | `/api/v1/consultorios/{cid}/profesionales/{mid}/disponibilidad` | `consultorio:manage` |
| `PUT` | `/api/v1/consultorios/{cid}/profesionales/{mid}/disponibilidad/{id}` | `consultorio:manage` |
| `DELETE` | `/api/v1/consultorios/{cid}/profesionales/{mid}/disponibilidad/{id}` | `consultorio:manage` |
| `GET` | `/api/v1/consultorios/{cid}/profesionales/{mid}/disponibilidad/efectiva?desde&hasta` | `colaborador:read` |
| `GET` | `/api/v1/consultorios/{cid}/excepciones?desde&hasta&membershipId` | `colaborador:read` |
| `POST` | `/api/v1/consultorios/{cid}/excepciones` | `consultorio:manage` |
| `DELETE` | `/api/v1/consultorios/{cid}/excepciones/{id}` | `consultorio:manage` |
| `GET` | `/api/v1/consultorios/{cid}/calendario?desde&hasta` | `colaborador:read` |
| `PUT` | `/api/v1/consultorios/{cid}/calendario` | `consultorio:manage` |
| `GET` | `/api/v1/feriados?pais&desde&hasta` | autenticado |

Reglas heredadas que se aplican sin excepción: **cross-tenant → 404, nunca 403**; **falta de
contexto → 403, nunca 401**; la ventana `desde/hasta` lleva **tope máximo** (el mismo
razonamiento que la matriz §264: sin tope, una ventana enorme es un scan y un problema de
disponibilidad).

`DELETE` es **baja lógica con motivo obligatorio**, nunca borrado físico.

---

## 7. Frontend

Feature nueva `features/resource/disponibilidad`, junto a las que ya existen.

- **Editor semanal compacto** — grilla de 7 días con los bloques de cada día y su vigencia.
- **Panel de excepciones** — alta de cierre/apertura, rango de fechas, día completo o franja,
  alcance sede o profesional.
- **Preview de disponibilidad efectiva** — una semana resuelta, mostrando **qué regla produjo
  o recortó cada franja**. Es el criterio de aceptación hecho pantalla.
- **Política de feriados de la sede** y listado de los feriados de la ventana.
- **Modo lectura para `PROFESIONAL`** — misma pantalla sin acciones. La autorización real la
  aplica el backend; el frontend solo evita ofrecer botones que van a dar 403.

Cliente TypeScript regenerado y fijado en **0.11.0**. Prohibidos los DTO manuales.

---

## 8. Tests

Cobertura dirigida a donde está el riesgo, no uniforme.

- **Unitarios del calculador de intervalos** — es el corazón de la etapa y ahí sí hay
  densidad: unión, resta, franja parcial, día completo, medianoche, vigencia que empieza o
  termina dentro de la ventana, feriado con y sin política, apertura recortada por cierre,
  hueco y solapamiento de DST.
- **Integración contra MySQL real** — solapamiento concurrente (dos altas simultáneas),
  aislamiento de tenant, baja lógica que conserva historia, membership desvinculada que deja
  la efectiva vacía sin borrar nada.
- **Frontend unitario** — editor y preview con `HttpTestingController`.
- **E2E** — uno: editar disponibilidad con conflicto y ver la explicación de la regla.

---

## 9. Design challenge

**1. Ownership.** `resource` es propietario de las cuatro tablas. Ningún otro módulo las
escribe ni las lee directamente. `organization` solo gana un SPI aditivo.

**2. Ciclos.** `resource → organization.spi`, la misma dirección que ya usa `EspacioService`.
No hay flecha nueva. ArchUnit no cambia.

**3. Tenant.** Tres de las cuatro tablas llevan `organization_id NOT NULL` y **todos** sus
uniques e índices empiezan por él. `feriado` no lo lleva: excepción declarada en ADR-0022,
con el precedente de ADR-0021 para los catálogos globales.

**4. Reglas maestras.** No se confunde HC/Caso/Sesión ni Obligación/Cobro/Caja. La distinción
que sí toca esta etapa y queda escrita: **disponibilidad ≠ turno**. La disponibilidad es
*oferta*; el turno es *reserva*. F5 no debe fusionarlas.

**5. Baja lógica.** `active/deleted_at/deactivation_reason` en bloques y excepciones. Ningún
`DELETE` físico. La disponibilidad de una membership desvinculada **no se borra**: el cálculo
la ignora porque `validAt` da `false`, y la historia queda intacta (RN-M05-003).

**6. Contrato.** Aditivo. `0.10.0 → 0.11.0`, bump menor, sin ventana de compatibilidad.

**7. Ruta crítica.** Las dependencias están construidas: 02.03 cerrada, `consultorio.timezone`
`NOT NULL` desde V18, `membership` desde V10–V12. No adelanta `scheduling`: deja la costura.

**8. El caso que rompe el diseño.** Un profesional con disponibilidad futura cargada y turnos
ya tomados al que se desvincula. Hoy se resuelve a medias, y hay que decirlo: la disponibilidad
sobrevive y deja de computar sola, y `ColaboradorDesvinculacionProbe` le muestra a quien
desvincula cuántos bloques futuros quedan. **Los turnos siguen sin existir**, así que
RN-M05-004 —"los turnos futuros afectados deben quedar visibles para resolución"— queda
**declarado y no cerrable en esta etapa**. Va a `docs/tests-diferidos.md` con destino F5.

---

## 10. Lo que esta etapa deja abierto

| Pendiente | Destino |
|---|---|
| RN-M05-004: turnos futuros afectados visibles al desvincular | F5 — `scheduling` |
| `DisponibilidadImpactProbe` sin implementación real | F5 — `scheduling` |
| El seed de feriados envejece; los trasladables se deciden por decreto | Mantenimiento anual |
| Horario general del consultorio (`RF-M03-002`, `CA-M03-002` parcial) | F5 |

---

## 11. Dos "disponibilidades" que no son la misma cosa

El repositorio ya tiene el nombre ocupado y conviene decirlo antes de que alguien las mezcle:

| | Qué responde | Módulo | RF |
|---|---|---|---|
| `DisponibilidadView` / `EspacioAvailabilityResponse` (02.02) | Si un **espacio físico** está en servicio en una ventana | `resource` (M04) | RF-M04-003 |
| Lo que agrega esta etapa | En qué franjas **atiende un profesional** en una sede | `resource` (M05) | RF-M05-003..005 |

Son ortogonales y las dos hacen falta: el motor de slots de 05.01 va a intersecarlas —un turno
necesita un profesional que atienda **y** un box libre—. Por eso los tipos nuevos **no** se
llaman `Disponibilidad*` a secas. Nomenclatura fijada:

- `BloqueDisponibilidad`, `DisponibilidadEfectiva`, `FranjaEfectiva` para el profesional.
- Lo de 02.02 conserva su nombre; no se renombra nada.
