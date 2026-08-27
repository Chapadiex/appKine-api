# AKINE-02.06 — Servicio global y oferta por consultorio

> Diseño de etapa. Módulo propietario: **`offering`, que no existe y esta etapa crea**.
> Contrato OpenAPI **0.12.0** (aditivo). Migración **V24**. ADR nueva: **ADR-0023, consolidadora**.

Trazabilidad: RF-M03-006 (configurar servicios ofrecidos por consultorio), RF-M03-007 (valores
operativos por defecto), RF-M06-006..008 (catálogo global de Servicios, naturaleza y modalidad,
Servicio ≠ Práctica), RF-M27-001..003 (crear/editar Servicio, crear Oferta);
RN-M03-006..007, RN-M06-004..006, RN-M27-001..008; reglas maestras **13–20 y 27**.

---

## 0. Lo que esta etapa es, en una frase

Separar **qué existe como concepto** (`Servicio`) de **cómo lo presta un centro concreto**
(`OfertaServicioConsultorio`), que es la regla maestra 14 —*"el Servicio define el concepto
general; la Oferta define cómo lo presta un consultorio concreto"*— y es la **Fase 1** de las cinco
que declara §30.7.

Nada más. Las otras cuatro fases —clases programadas, esquemas de cobro, actividades clínicas
grupales, y la eventual consolidación en `EventoAgenda`— son etapas posteriores.

---

## 1. Decisiones cerradas con el usuario

| Decisión | Resuelto | Alternativa descartada |
|---|---|---|
| Módulo propietario | **`offering` nuevo** | Extender `resource` |
| Alcance de `Servicio` | **Puramente global**, sin `organization_id` | Global + contextual con `owner_key`; global con habilitación por sede |
| Permisos | **Reusar los existentes**, sin códigos nuevos | Proponer `servicio:*` y `oferta:*`; aprobar la matriz primero |

### Por qué `offering` y no `resource`

`AGENT.md` §4 ya declara el módulo `offering` para M27, y **el código lo viene anticipando**:
`EspacioTipo`, `EspacioController` y `DenialKind` dicen textualmente *"cuando llegue F4 con
`offering` (M27)"*. El diseño de cierre de 02.01 también lo da por hecho.

El plan de implementación, en cambio, instruye modificar *"rutas `resource`/catálogos"*. Es una
contradicción real, y gana `AGENT.md` porque el protocolo del repositorio lo declara vinculante.

El motivo de fondo, más allá de la autoridad documental: **RN-M06-004 separa los dos conceptos con
una frase que no deja lugar a dudas** — *"el Servicio es lo que el consultorio ofrece; la Práctica
es una intervención que puede realizarse durante una atención"*. `resource` es el vocabulario
clínico y físico: especialidades, prácticas, nomencladores, espacios, disponibilidad. Servicio es
vocabulario **comercial**: lo que el centro le ofrece a una persona. Meterlos en la misma caja es
exactamente el error que la regla maestra 14 previene.

Hay además un argumento práctico: `resource` ya carga dos conceptos distintos llamados
"disponibilidad" —la del espacio físico (M04) y la del profesional (M05)—, y sumarle un tercer
vocabulario lo vuelve el módulo donde va todo lo que no se sabe dónde poner.

### Por qué `Servicio` es puramente global

RN-M27-001 dice *"un Servicio es catálogo global/conceptual"* y no menciona ninguna población por
tenant. §30.6 lista sus campos y **no incluye `organizationId`**, a diferencia de `especialidad` y
`practica`, que sí lo llevan nullable. Y no existe ningún RF de solicitud de alta para Servicio,
como sí existe RF-M06-005 para el catálogo clínico.

La frase *"del catálogo global **o autorizado**"* de RF-M03-006 es el único indicio en contra, y
se lee igual de bien como "un servicio global que este centro tiene permitido ofrecer" —un gating—
que como una segunda población de filas. **La configuración propia de cada centro ya tiene dónde
vivir: es la Oferta.** Duplicar esa capacidad en el catálogo sería dos formas de hacer lo mismo.

> **Corrección a un documento anterior.** El registro de cierre de 02.05 afirma que 02.06 hereda
> *"la misma pareja de poblaciones, con el mismo `owner_key`"*. Es impreciso en las dos mitades:
> `Servicio` no tiene segunda población, y `OfertaServicioConsultorio` lleva `organization_id`
> **NOT NULL**, así que no tiene el problema que `owner_key` resuelve —varios `NULL` que no
> colisionan en un unique de MySQL—. La Oferta se parece a `espacio`, no al catálogo clínico.

### Por qué no se crean permisos nuevos

Mismo criterio que 02.04 y 02.05: la matriz de permisos **no la amplía una etapa**. Las mutaciones
de la Oferta van con `consultorio:manage`; las lecturas, por pertenencia a la sede. El `Servicio`
global lo administra el rol de plataforma, con el mismo mecanismo que 02.05 usa para el catálogo
global.

> **Hueco heredado que esta etapa NO cierra y conviene tener presente:** hoy **ningún endpoint le
> dice al frontend si quien mira tiene rol de plataforma**, así que la pantalla de administración
> del `Servicio` global arrastra el mismo problema que dejó abierto RF-M06-005 en 02.05. La consola
> de plataforma sigue siendo una etapa propia, y su prerrequisito sigue siendo ese endpoint.

---

## 2. La deuda de ADR que esta etapa paga

ADR-0004 exige que toda tabla de negocio lleve `organization_id NOT NULL`. Hay cuatro excepciones
declaradas: **ADR-0019** (identidad), **ADR-0020** (rol de plataforma), **ADR-0021** (catálogos
clínicos) y **ADR-0022** (feriados, de 02.04).

**ADR-0020 y ADR-0021 dicen, textualmente, que la cuarta excepción no escribe otro ADR incremental
sino que consolida los anteriores en uno que los supersede.** ADR-0022 fue esa cuarta y no lo hizo:
su propia línea 117 reconoce la deuda y la difiere.

`servicio` sería la **quinta**. La regla ya se pospuso una vez y esta etapa la cumple:
**ADR-0023 consolida 0019, 0020, 0021 y 0022, los supersede, y agrega `servicio`.** Debe declarar
en un solo lugar el criterio —qué hace que una tabla sea legítimamente global— en vez de dejarlo
disperso en cinco documentos que hay que leer juntos para entenderlo.

---

## 3. Modelo de datos — migración V24

### 3.1 `servicio` — global, sin `organization_id`

```
id, codigo VARCHAR(64) NOT NULL, nombre VARCHAR(160) NOT NULL, descripcion VARCHAR(500) NULL,
naturaleza  CHECK IN ('CLINICO','TERAPEUTICO','PREVENTIVO','BIENESTAR'),
modalidad_default CHECK IN ('INDIVIDUAL','GRUPAL'),
requiere_caso_clinico_default TINYINT(1) NOT NULL DEFAULT 0,
genera_registro_clinico_default TINYINT(1) NOT NULL DEFAULT 0,
active, deleted_at, deactivation_reason, deleted_key (generado),
version, created_at, updated_at
UNIQUE (codigo, deleted_key)
UNIQUE (nombre, deleted_key)
```

Sin `owner_key`: no hay dos poblaciones que discriminar, así que el centinela no hace falta. Sí el
`deleted_key`, por el mismo motivo que en `espacio` — permitir reusar un nombre después de una baja
lógica sin que el unique lo impida.

**Los tres campos `*_default` son propuesta inicial, no regla.** RF-M06-006 lo dice: *"los defaults
no reemplazan la configuración concreta de cada Oferta"*. Y RN-M06-005 es más fuerte todavía: la
naturaleza *"sirve para clasificación y no debe imponer por sí sola comportamiento clínico"*.

### 3.2 `oferta_servicio_consultorio` — con tenant y sede

```
id, organization_id NOT NULL, consultorio_id NOT NULL, servicio_id NOT NULL FK -> servicio,
nombre_comercial VARCHAR(160) NOT NULL, descripcion VARCHAR(500) NULL,
modalidad CHECK IN ('INDIVIDUAL','GRUPAL'),
duracion_minutos INT NOT NULL, capacidad INT NOT NULL,
precio_base DECIMAL(12,2) NULL, moneda CHAR(3) NULL,
esquema_cobro VARCHAR(32) NULL,
admite_obra_social TINYINT(1) NOT NULL DEFAULT 0,
requiere_caso_clinico TINYINT(1) NOT NULL,
genera_registro_clinico TINYINT(1) NOT NULL,
requiere_profesional TINYINT(1) NOT NULL,
requiere_espacio TINYINT(1) NOT NULL,
vigencia_desde DATE NOT NULL, vigencia_hasta DATE NULL,   -- exclusivo, NULL = sin fin
active, deleted_at, deactivation_reason, deleted_key (generado),
version, created_at, updated_at

CHECK (duracion_minutos > 0)
CHECK (capacidad > 0)
CHECK (modalidad <> 'GRUPAL' OR capacidad > 1)
CHECK (vigencia_hasta IS NULL OR vigencia_hasta > vigencia_desde)
CHECK ((moneda IS NULL AND precio_base IS NULL) OR (moneda IS NOT NULL AND precio_base IS NOT NULL))
CHECK (baja coherente: active/deleted_at/deactivation_reason se mueven juntos)
UNIQUE (organization_id, consultorio_id, nombre_comercial, deleted_key)
INDEX (organization_id, consultorio_id, active, nombre_comercial)
INDEX (organization_id, consultorio_id, servicio_id)
```

**`capacidad > 1` para `GRUPAL`, no `> 0`.** RF-M27-003 dice *"modalidad GRUPAL requiere capacidad
mayor a cero"*, pero una oferta grupal de capacidad 1 es una oferta individual mal rotulada, y en
F2 el motor de inscripciones la trataría como grupo de una persona. El check más estricto es la
lectura útil de la regla; queda anotado como decisión revisable.

**Precio y moneda viajan juntos o no viajan.** Un precio sin moneda no es un precio, y este SaaS
va a operar en más de un país.

**`esquema_cobro` es un dato declarado, no resuelto.** RN-M27-006 dice que las reglas económicas
dependen de convenios y aranceles vigentes, y **M15, M16 y M18 no existen**. En esta etapa se
guarda y se muestra; nadie lo interpreta.

### 3.3 Lo que NO se modela, y por qué

- **Nada de `Turno` ni `Sesión`.** No existen: no hay módulo `scheduling` ni `clinical`. La regla
  maestra 27 —*"no exige reemplazar inmediatamente las entidades Turno o Sesión vigentes"*— es una
  restricción sobre el **orden de construcción futuro**, no sobre código presente. **No hay ninguna
  FK que escribir hoy.**
- **Nada de la relación Oferta ↔ Práctica.** RF-M06-008 dice que una oferta clínica *"puede
  utilizar una o más Prácticas durante sus atenciones"* — durante la **atención**, que es un hecho
  de `clinical`, que no existe. Modelar la tabla puente ahora sería inventar la forma de un vínculo
  cuyo consumidor nadie escribió. Se declara la costura y se difiere.
- **Nada de habilitación de profesionales ni espacios por oferta.** RF-M03-006 paso 7 lo pide
  *"cuando corresponda"*, y `requiere_profesional` / `requiere_espacio` capturan la intención sin
  materializar dos tablas puente que solo la agenda va a consumir.

---

## 4. Módulo, dependencias y SPI

Propietario de las dos tablas: **`offering`**, nuevo.

ArchUnit trabaja por *slices* sobre `com.akine`, así que el módulo queda cubierto por la regla de
no-ciclos y por la de `spi`-como-único-punto-de-entrada **sin tocar el test**. Lo que hay que
respetar es la dirección:

```
offering  →  organization.spi   (ConsultorioDirectory, PermissionGuard, AccountContextDirectory)
offering  →  platform.spi.audit (AuditTrail)
```

**Nadie importa `offering`.** Los consumidores —agenda, inscripciones, cobros— son fases
posteriores. Esta etapa **no declara un SPI de `offering`**: una interfaz sin llamador es código
muerto, y el precedente de 02.04 dice que una costura se declara cuando se cablea, no antes.

> **`offering` NO depende de `resource`.** Podría parecer que sí, porque el catálogo clínico vive
> ahí, pero la relación Oferta ↔ Práctica se difiere (§3.3) y sin ella no hay nada que consultar.
> La flecha se agrega en la etapa que la necesite, no ahora "por las dudas".

---

## 5. Contrato — OpenAPI 0.12.0

Cambio **aditivo**: bump menor.

| Método | Path | Autorización |
|---|---|---|
| `GET` | `/api/v1/servicios` | autenticado |
| `POST` | `/api/v1/servicios` | rol de plataforma |
| `PUT` | `/api/v1/servicios/{id}` | rol de plataforma |
| `DELETE` | `/api/v1/servicios/{id}` | rol de plataforma |
| `GET` | `/api/v1/consultorios/{cid}/ofertas` | pertenencia |
| `POST` | `/api/v1/consultorios/{cid}/ofertas` | `consultorio:manage` |
| `PUT` | `/api/v1/consultorios/{cid}/ofertas/{id}` | `consultorio:manage` |
| `DELETE` | `/api/v1/consultorios/{cid}/ofertas/{id}` | `consultorio:manage` |

Reglas heredadas, sin excepción: **cross-tenant → 404, nunca 403**; **falta de contexto → 403,
nunca 401**; `DELETE` es **baja lógica con motivo obligatorio**.

**El `type` del 409 por concurrencia es `conflict`, no `concurrent-modification`** — el hallazgo de
02.04: `OptimisticLockingFailureException` plano lo mapea el handler global. No repetir la
inexactitud que arrastran los contratos de 02.02 y 02.05.

---

## 6. Frontend

Feature nueva. **Colisión léxica que hay que evitar desde el nombre**: `appKine-web` ya tiene
`features/resource/models/situacion-de-servicio.ts`, donde "servicio" significa *si un espacio
físico está operativo* (M04). No es un bug, pero un modelo `Servicio` en el mismo repo hace que
"en servicio" y "el Servicio X" convivan sin distinguirse en un review.

Nomenclatura fijada: la feature es **`features/offering/`**, las pantallas son
`CatalogoDeServiciosPage` y `OfertasDeLaSedePage`, y **nada se llama `ServicioPage` a secas**.

Dos pantallas: el catálogo global de servicios (solo lectura salvo rol de plataforma) y las ofertas
de la sede. La segunda es la que usa un administrador todos los días.

---

## 7. Design challenge

**1. Ownership.** `offering` es propietario de `servicio` y `oferta_servicio_consultorio`. Ningún
otro módulo las toca.

**2. Ciclos.** `offering → organization.spi` y `offering → platform.spi.audit`. Nadie importa
`offering`. Unidireccional; ArchUnit lo cubre por slices sin cambios.

**3. Tenant.** `oferta_servicio_consultorio` lleva `organization_id NOT NULL` y todos sus índices
empiezan por él. `servicio` no lo lleva: excepción declarada en **ADR-0023**, que además consolida
las cuatro anteriores.

**4. Reglas maestras.** La 14 es el objetivo de la etapa. La 15 y la 20 se cumplen estructuralmente:
no hay ninguna condición por nombre, todo sale de `modalidad`, `requiere_*` y `esquema_cobro`. La
17 y la 19 se respetan porque esta etapa **no crea ningún registro clínico**: solo declara si una
oferta lo requeriría. La 27 no aplica todavía: no hay Turno ni Sesión que reemplazar.

**5. Baja lógica.** `active` / `deleted_at` / `deactivation_reason` en las dos tablas, con check de
coherencia. RN-M27-007: una oferta inactiva no admite reservas nuevas y conserva sus históricos.
RF-M27-002: *"no borrar físicamente si existen referencias"*.

**6. Contrato.** Aditivo. `0.11.0 → 0.12.0`, bump menor.

**7. Ruta crítica.** Las dependencias declaradas (02.01, 02.05) están cerradas. Las de M27 hacia
M15/M16/M18 **no existen**, y por eso `esquema_cobro` se guarda sin resolverse. No se adelanta
`scheduling` ni `clinical`.

**8. El caso que rompe el diseño.** Un `servicio` global que se da de baja mientras hay ofertas
activas que lo referencian en varios centros. RF-M27-002 prohíbe el borrado físico y RN-M03-006
prohíbe afectar históricos. La baja del servicio **no cascadea**: las ofertas vigentes siguen
operando y lo que se impide es crear ofertas nuevas sobre un servicio inactivo —RF-M27-002:
*"al inactivar impide nuevas altas de oferta según política"*—. Hay que decidir explícitamente si
además se avisa a los centros afectados, y **eso necesita saber cuántos son**: es una consulta
cross-tenant que solo el rol de plataforma puede hacer.

---

## 8. Lo que esta etapa deja abierto

| Pendiente | Destino |
|---|---|
| Relación Oferta ↔ Práctica (RF-M06-008) | La etapa que construya `clinical` |
| Habilitación de profesionales y espacios por oferta (RF-M03-006 paso 7) | La etapa de agenda |
| `esquema_cobro` se declara pero no se resuelve (RN-M27-006) | M16 / M18 |
| El frontend no puede saber si el usuario tiene rol de plataforma | Consola de plataforma, etapa propia |
| Fases 2 a 5 de §30.7 | Etapas posteriores |
