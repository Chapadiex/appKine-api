# AKINE-03.01 — Persona, PerfilPaciente, búsqueda y deduplicación

> **Estado:** backend implementado y verificado. Frontend pendiente.
> **Fuentes:** M07 §1–§8 del documento integrado (RF-M07-001..003, 007..010; RN-M07-001..008),
> regla maestra 13, DP-03 (ADR-0010), ADR-0004, ADR-0007, ADR-0018.
> **Migración:** `V27__m07_persona_y_perfil_paciente.sql`. **Contrato:** OpenAPI `0.13.0`.

---

## 0. Lo que esta etapa es, en una frase

Separar la **identidad administrativa** (`Persona`) del **perfil clínico** (`PerfilPaciente`),
de modo que registrar a alguien nunca lo convierta en paciente, y que volverlo paciente sea un
acto explícito, autorizado y auditado.

Todo lo demás de la etapa —la búsqueda, la deduplicación, el alta mínima— existe al servicio de
esa separación.

---

## 1. La decisión central: dos filas, no una

El UML de 2019 une Persona y Paciente. RN-M07-005 exige separarlas. **No es un refinamiento
estético.**

Con una sola tabla, dar de alta a quien viene a una clase de pilates lo convierte en paciente, y a
partir de ahí todo el sistema clínico tiene una ficha que no debería existir. Es exactamente el
**error estructural 2** del plan (`Paciente → Sesión` como atajo suficiente) entrando por la
puerta del alta, y RN-M07-006 lo prohíbe de frente.

### Por qué una tabla separada y no una columna `es_paciente`

Tres motivos, y el tercero es el que manda:

1. Una columna booleana no puede llevar **quién** activó el perfil ni **cuándo**.
2. No sostiene la vigencia ni la baja que RF-M07-005 va a pedir en 03.02.
3. **Un `UPDATE persona SET es_paciente = 1` es una línea que cualquier camino puede escribir sin
   querer.** Insertar en otra tabla es un acto deliberado que se ve en el diff y que pasa por un
   servicio con su propio permiso y su propio evento de auditoría.

El tercero es RF-M07-010 convertido en estructura. Cuando F5 traiga turnos y F9 traiga
inscripciones a clases, esos caminos van a necesitar dar de alta personas — y **no van a tener
forma de crear pacientes**.

### Cómo se sostiene, concretamente

| Garantía | Dónde vive |
|---|---|
| `Persona` no tiene campo ni relación hacia su perfil | `Persona.java` — hay un test que lo asserta sobre la API, no sobre un valor |
| `PersonaService` recibe el puerto de perfiles **solo para leer** | Nunca llama a `save` ni a `saveAndFlush`; hay un test que lo verifica |
| La activación vive en una sola clase | `PerfilPacienteService`, el único lugar del sistema que crea un paciente |
| `perfil_paciente` no referencia nada clínico | Sin `historia_clinica_id`; `PersonaMigrationIT` lo verifica contra el esquema real |

**Y activar el perfil tampoco crea Historia Clínica.** La HC es M09, vive en `clinical` —que no
existe— y la regla maestra 1 la separa del Caso y de la Sesión. Esta fila dice "esta persona es
paciente" y nada más.

---

## 2. Por qué `Persona` es de la organización y no de la sede

`persona` lleva `organization_id NOT NULL` y **no lleva `consultorio_id`**.

1. **DP-03 (ADR-0010) ya lo decidió.** La Historia Clínica pertenece a la organización. Una
   identidad de alcance sede produciría dos personas para el mismo ser humano en un centro con dos
   sedes, y después dos historias clínicas dentro de la misma organización.
2. **El caso borde "duplicado entre sedes" se resuelve por construcción.** Con el unique de
   documento a nivel organización, ese duplicado **no se puede crear**. No hay detección que
   escribir: hay un unique.

Que la persona se haya registrado en una sede concreta es dato del hecho (turno, inscripción), no
de la identidad. Cuando F5 y F9 existan, la sede vivirá ahí.

---

## 3. Modelo de datos — migración V27

### 3.1 `persona`

Lo que no es obvio:

- **El documento es nullable.** "Persona sin DNI" es un caso borde declarado y real: un menor, un
  extranjero recién llegado, una urgencia. Un `NOT NULL` obligaría a inventar documentos falsos,
  que es la peor salida —contamina el padrón con claves que después chocan de verdad—.
- **El unique igual sirve.** `uk_persona_documento_vigente (organization_id, tipo_documento,
  documento_clave, deleted_key)`. En MySQL varios `NULL` no colisionan en un unique, y esa
  propiedad —que en otras tablas es la trampa que obliga al centinela— acá es **exactamente el
  comportamiento buscado**: N personas sin documento conviven, y en cuanto una declara documento
  entra en la unicidad. Cero ramas de código.
- **Cuatro columnas `*_clave` materializadas.** `documento_clave`, `apellido_clave`,
  `nombre_clave`, `telefono_clave`. Un `WHERE UPPER(apellido) LIKE ...` no usa índice, y el padrón
  es la primera tabla del sistema con volumen real (RNF-M07-004). Las escribe el dominio en el
  constructor y en cada edición; no hay setter suelto.
- **`deleted_key`, el centinela de siempre.** RN-M07-004 prohíbe borrar físicamente. El reflejo
  `UNIQUE (..., deleted_at)` está **roto**: todas las vigentes tienen `NULL`, no colisionan entre
  sí, y el unique terminaría protegiendo el histórico y desprotegiendo lo vigente.
- **`ck_persona_documento_completo`.** Tipo y número van los dos o ninguno. Un tipo sin número no
  identifica a nadie; un número sin tipo no se compara entre países.

### 3.2 `perfil_paciente`

`uk_perfil_paciente_persona (organization_id, persona_id, deleted_key)` es lo que hace la
activación **idempotente sin ventana de carrera**: dos requests simultáneos no producen dos
perfiles. Y con `deleted_key`, un perfil dado de baja se puede reactivar — que es el caso borde
"perfil dado de baja" de la etapa.

`activado_por` es un `Long` pelado, sin FK a `cuenta`: esa tabla es de `identity` y una FK cruzaría
la propiedad de datos entre módulos.

### 3.3 El hueco en V26

02.07 se escribió en paralelo, en el mismo árbol de trabajo, y las dos etapas nacieron como V26.
Flyway rechaza dos migraciones con la misma versión y la aplicación no arranca. Esta corrió a V27
y 02.07 terminó commiteándose como V28. **El hueco se deja como está**: Flyway no exige versiones
contiguas, y renumerar una migración ya aplicada es peor que un número sin usar.

### 3.4 Lo que NO se modela, y por qué

- **Ninguna FK hacia `cuenta`.** RN-M07-002: paciente y usuario de portal no son necesariamente la
  misma entidad. El vínculo es de la etapa de autoservicio; ponerle la columna hoy invitaría a
  usarla como si estuviera resuelto.
- **Coberturas, adjuntos, deuda**: 03.02, 03.03, 03.04.
- **Nada clínico.** M09 y M14, en un módulo que no existe.
- **La baja lógica tiene columnas y no tiene endpoint.** RF-M07-005 es 03.02; las columnas están
  desde ahora porque los dos uniques las necesitan y agregarlas después obligaría a rehacerlos.

---

## 4. Las dos capas de protección contra duplicados

Son **distintas** y confundirlas es el error a evitar:

| | Documento repetido | Posible duplicado |
|---|---|---|
| Naturaleza | Invariante **duro** | **Advertencia** |
| Lo garantiza | `uk_persona_documento_vigente` | Consulta de coincidencias exactas |
| Se puede confirmar | **No, nunca** | Sí, reenviando con `confirmaPosibleDuplicado` |
| `type` | `persona-documento-taken` | `persona-posible-duplicado` |
| Extra | `personaExistenteId` | `candidatos` |

### Por qué RN-M07-001 lo hace cumplir el backend y no la pantalla

RN-M07-001 dice "debe existir búsqueda previa a la creación". El reflejo es un buscador arriba del
formulario: necesario y **no suficiente**. El backend es la autoridad (regla maestra 12), y un alta
por API, por importación o desde una pantalla futura distraída entraría sin comprobación alguna.
Con la pantalla como única defensa, la regla dura lo que dura el primer cliente nuevo.

### Por qué la detección es exacta y no fonética

Nada de Soundex ni distancia de edición. Un detector generoso produce advertencia en casi toda
alta, el operador aprende a confirmar sin leer, y la regla se vuelve un click de más que no
protege nada. Un detector estricto avisa poco y cuando avisa, acierta. Además, lo exacto se
indexa; lo difuso no.

**La edición no corre la detección.** Corregir el apellido hasta que coincida con otra persona es
marginal; advertir en cada PATCH pediría confirmación cada vez que se corrige un teléfono. El
invariante duro sí se sigue verificando, porque lo verifica el unique.

---

## 5. Autorización

| Operación | Control |
|---|---|
| Leer | **Pertenencia**: contexto de organización activo. Sin código de permiso |
| Mutar | `paciente:manage` evaluado **con la sede del contexto** |

### El permiso se evalúa con la sede aunque la Persona sea de la organización

Es la decisión menos obvia de la etapa. Una `persona` no tiene `consultorio_id`, así que el
reflejo es evaluar con `consultorioId = null`. **Eso rompe la matriz.**
`PermissionEvaluatorService.alcanceCubre` concede un alcance de sede solo cuando la consulta nombra
una sede: sin ella devuelve `false`. Con la consulta sin sede pasarían `ORG_ADMIN` y plataforma, y
quedarían afuera `CONSULTORIO_ADMIN` y `ADMINISTRATIVO` — a quienes §4 les dice "Sí". **El
recepcionista no podría dar de alta a nadie.**

Consecuencia: sin contexto de sede no se muta el padrón (403), igual que ofertas y disponibilidad.
La persona no queda atada a esa sede.

### `paciente:manage` no es un código nuevo

Existía en la matriz §5 desde el primer día con fase destino F3, y en el código con la nota "sin
asignación base todavía: deniega". Esta etapa creó el módulo que lo evalúa y le dio la asignación
que §4 ya le daba. La enmienda está en `docs/seguridad/matriz-permisos-minima.md` §12.

Lo que sí hubo que agregar: **`paciente:manage` a los otorgables como grant.** §4 le dice al
`PROFESIONAL` "Según permiso"; hasta esta etapa el único código otorgable era
`auditoria:read-clinica` y cualquier otro se rechazaba con 400, así que esa celda no tenía forma
de cumplirse.

### El hueco que esta etapa NO cierra

**No existe `paciente:read`** y no se crea: la matriz no lo declara y una etapa no amplía la
matriz. Con pertenencia sola, una membership con rol `PACIENTE` lee el padrón entero de su
organización, cuando §4 le asigna "Propio".

**Aprobar un `paciente:read` no lo resolvería.** El problema no es el código de permiso sino el
alcance `OWN`, que no está implementado en ninguna parte: no existe vínculo entre una cuenta y una
persona, justamente porque RN-M07-002 los separa. Queda como hueco conocido con etapa destino en
el autoservicio.

---

## 6. Contrato — OpenAPI 0.13.0

| Operación | Ruta |
|---|---|
| `buscarPersonas` | `GET /api/v1/personas` |
| `verPersona` | `GET /api/v1/personas/{personaId}` |
| `crearPersona` | `POST /api/v1/personas` |
| `editarPersona` | `PATCH /api/v1/personas/{personaId}` |
| `activarPerfilPaciente` | `POST /api/v1/personas/{personaId}/perfil-paciente` |

La ruta **no lleva la organización**: el tenant sale del contexto validado, no de la URL. Dejar
que el cliente lo nombre sería darle un lugar donde afirmar una pertenencia que el servidor tiene
que verificar igual.

**El paginado se recorta en la BASE**, a diferencia de espacios y ofertas, que traen todo y
recortan en memoria. Aquellos podían: una sede tiene decenas de espacios. El padrón tiene decenas
de miles de personas.

`activarPerfilPaciente` responde **200 y no 201**, y es idempotente: activar dos veces devuelve el
perfil que ya existe. Un 409 obligaría a toda pantalla a distinguir "ya era paciente" de un
conflicto real, y lo natural sería que terminara ignorando los dos.

Tres `type` nuevos: `persona-documento-taken`, `persona-posible-duplicado`, `persona-inactiva`.

---

## 7. Design challenge

1. **Ownership.** `persona` y `perfil_paciente` son de `person`. Ningún otro módulo las toca.
2. **Ciclos.** `person` → `organization.spi` + `platform.spi`. Nadie depende de `person`. ArchUnit
   verde.
3. **Tenant.** Las dos tablas llevan `organization_id NOT NULL` y todo índice declarado empieza por
   él. `PersonaMigrationIT` lo verifica contra el esquema real.
4. **Reglas maestras.** La 1 es exactamente lo que esta etapa protege: el perfil no es HC.
5. **Baja lógica.** Sin borrado físico. Las columnas existen; el endpoint es 03.02.
6. **Contrato.** Aditivo: cinco operaciones nuevas, ningún cambio incompatible.
7. **Ruta crítica.** Depende solo de 01.03, que está cerrada. No adelanta nada.
8. **El caso que rompe el diseño.** *Dos recepcionistas dando de alta a la misma persona sin
   documento, al mismo tiempo, con el mismo nombre.* Ninguna de las dos protecciones actúa: el
   unique no aplica —sin documento no hay clave— y la detección de coincidencias corre antes del
   INSERT en las dos transacciones, así que las dos ven el padrón sin la otra. **Entran las dos
   fichas.** Es deliberado y correcto: dos hermanos homónimos sin DNI son dos personas legítimas, y
   el sistema no tiene forma de distinguir ese caso del duplicado. La corrección es administrativa
   —fusionar fichas—, y esa herramienta es de 03.02 junto con la baja lógica.

---

## 8. Lo que esta etapa deja abierto

- **Búsqueda por número de afiliado** (RF-M07-001). El afiliado es dato de la cobertura: 03.04.
- **Baja lógica y fusión de fichas** (RF-M07-005). 03.02.
- **`personaExistenteId` casi siempre viaja en `null`.** Después de un flush fallido no se puede
  volver a consultar la sesión JPA —lo que sale de ahí es un 500 en vez del 409 legítimo—, así que
  averiguar quién tiene ese documento exige otra transacción. La propiedad se publica igual: el día
  que se resuelva, el contrato no cambia.
- **El alcance `OWN`**, sin el cual la celda "Propio" de la matriz no se puede cumplir.
- **El frontend.** No empezado.
