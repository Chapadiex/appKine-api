# AKINE A-9 — Puente Oferta↔Práctica (DP-11, RF-M06-008)

> Paquete **A-9** de `docs/fases/01-trabajo-en-paralelo.md` (ola 2, ruta crítica de la economía:
> A-9 → C-4 → F-4). Implementa **DP-11** de `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`. Cierra el
> ítem "Puente Oferta↔Práctica (RF-M06-008)" de `docs/fases/F2-operacion-del-consultorio.md`.
> Migración: **`V75`** (la reserva original `V66` quedó por debajo de migraciones ya mergeadas y
> Flyway corre sin `outOfOrder`; `V66` queda vacía). Contrato: **0.58.0**.

## 1. Qué se cubre y de dónde sale

| Requisito | Qué dice | Cómo se cubre |
|---|---|---|
| RF-M06-008 paso 5 | "Mantiene catálogos y relaciones separados" | La práctica sigue siendo de `resource` (M06); la relación vive en `offering` en una tabla propia. Ninguna columna nueva en `practica`, `servicio` ni `oferta_servicio_consultorio` |
| RF-M06-008 paso 6 | "Una Oferta clínica utiliza una o más Prácticas durante sus atenciones" | Tabla N:M `oferta_practica` |
| RF-M06-008, validaciones | "No crear PracticaEnAtencion por el solo hecho de asistir a un servicio" | La tabla declara qué **puede** prestarse, no qué se prestó. Qué se prestó lo siguen diciendo los tratamientos (06.04) |
| DP-11 | N:M con `organization_id`, una **principal** por oferta, baja lógica | §2 |
| DP-11 | Al devengar o consumir manda la práctica realizada; la principal sólo si la sesión cerró sin tratamientos | §5: el `spi` expone las dos lecturas; la regla la aplica el consumidor (C-4, F-4) |
| DP-11 | Una práctica realizada no habilitada en la oferta no se rechaza: es alerta | §5: el `spi` permite detectarlo; ni este paquete ni `encounter` rechazan nada |
| RF-M06-008, excepciones | Concurrencia: revalidar y no sobrescribir en silencio | Control optimista sobre la versión de la **oferta**, con force-increment (§4) |

### Lo que queda AFUERA, y por qué

- **El consumo de la principal en el devengo** (F-4) y **en el consumo de autorizaciones** (C-4).
  El `spi` queda listo; cablearlo es de esos paquetes.
- **La alerta "práctica realizada no habilitada"**. Registrar el tratamiento vive en `encounter`, que
  es de otro carril; la alerta la levanta quien devenga o consume (F-4/C-4), que es donde tiene
  consecuencia económica. El `spi` le da lo necesario.
- **`plan_item.practica_id`** y el avance del plan por práctica (RF-M11-002). DP-11 lo descartó como
  puente; el plan puede derivar la práctica de la oferta con este mismo `spi`.
- **Vigencias por fila** (`valid_from`/`valid_until`). DP-11 pide baja lógica, no ventanas. La
  pregunta histórica "qué prácticas tenía la oferta en marzo" se responde con `created_at` y
  `deleted_at`, y con la auditoría.
- **Pantalla.** A-9 es "api + web"; el frontend regenera el cliente contra 0.58.0 en su rama.

## 2. Modelo

```
oferta_practica                                   (propietario: offering)
  id, organization_id NOT NULL, consultorio_id NOT NULL, oferta_id NOT NULL, practica_id NOT NULL
  principal            TINYINT(1)  — la práctica por defecto de la oferta
  active, deleted_at, deactivation_reason, deleted_key (centinela '1970-01-01')
  principal_key        AS (CASE WHEN principal = 1 AND deleted_at IS NULL THEN 1 END) STORED
  version, created_at, updated_at

  uk_oferta_practica_vigente   UNIQUE (organization_id, oferta_id, practica_id, deleted_key)
  uk_oferta_practica_principal UNIQUE (organization_id, oferta_id, principal_key)
  ck_oferta_practica_baja_coherente      — el cuarteto, como en V28
  ck_oferta_practica_principal_vigente   — una fila dada de baja no puede ser principal
  fk_oferta_practica_{organization,consultorio,oferta,practica}
```

**"Una sola principal" la sostiene la base, no la aplicación.** `principal_key` vale `1` sólo en la
fila vigente marcada como principal y `NULL` en todas las demás; como en MySQL varios `NULL` no
colisionan en un unique, `uk_oferta_practica_principal` admite cualquier cantidad de no principales y
**una sola** principal vigente por oferta. Es el mismo truco que `deleted_key`, aplicado a otra
columna. Las filas dadas de baja que fueron principales no estorban: su `principal_key` es `NULL`, y
además el CHECK impide que una dada de baja conserve la marca.

**La baja lógica no choca con el alta de la misma práctica**: `deleted_key` de la fila dada de baja
es su instante de baja, distinto del centinela de la fila nueva.

`consultorio_id` es redundante con `oferta_id`, a propósito y por el mismo motivo que en V28: los
índices por sede no necesitan un join.

La práctica puede ser **global** (`practica.organization_id` NULL) o **propia** del tenant: el
catálogo M06 tiene las dos poblaciones (ADR-0021) y una oferta puede usar cualquiera de las que el
tenant ve. Por eso la FK va contra `practica(id)` y la visibilidad la valida la aplicación por
`resource.spi.CatalogoDirectory.findPractica`, que ya filtra "propias más globales".

## 3. API

Bajo el mismo recurso que las habilitaciones, porque es configuración de la oferta:

| Operación | Ruta | Permiso |
|---|---|---|
| `getPracticasDeOferta` | `GET /api/v1/consultorios/{consultorioId}/ofertas/{ofertaId}/practicas` | pertenencia a la organización |
| `reemplazarPracticasDeOferta` | `PUT` misma ruta | `consultorio:manage` sobre la sede (el mismo que habilitaciones) |

`PUT` recibe `{ practicaIds, practicaPrincipalId, expectedVersion }` y **reemplaza el conjunto
completo**, con el diff del lado del servidor como las habilitaciones: lo que entra y no estaba se
crea, lo que estaba y no entra se da de baja con motivo automático, lo que sigue conserva id y
versión. Si cambia la principal, la anterior pierde la marca y la nueva la gana.

Reglas de entrada:

- `practicaIds` vacía **es válida** y deja la oferta sin prácticas declaradas; entonces
  `practicaPrincipalId` tiene que ser `null`. Al revés que en habilitaciones, **vacía no significa
  "todas"**: significa que la oferta no declara qué presta (p. ej. Pilates como servicio, RF-M06-008),
  y el devengo de una sesión sin tratamientos no tendrá práctica por defecto.
- Con al menos una práctica, `practicaPrincipalId` es obligatoria y tiene que estar en la lista → si
  no, **400** `validation-error`.
- Una práctica que el tenant no ve (de otro tenant o inexistente) → **404**. Una que existe pero no
  se puede elegir hoy → **409** `practica-no-utilizable` (el mismo tipo que 06.04). Las que **ya**
  estaban y siguen pedidas no se revalidan contra el catálogo: dar de baja una práctica en M06 no
  rompe la configuración de las ofertas que la usaban (RN-M06-002).
- Oferta dada de baja → 409 `oferta-inactiva`; sede no operable → 409 `consultorio-no-operable`;
  versión vieja → 409 `conflict`.

La respuesta trae `ofertaVersion` con el mismo contrato que `HabilitacionesResponse` (0.50.0): en
la lectura, la vigente; tras un reemplazo, `leída + 1`, la que queda después del commit. **La
versión es una sola para toda la configuración de la oferta**: reemplazar prácticas mueve la misma
versión que reemplazar habilitaciones, así que una pantalla con la versión vieja de cualquiera de
las dos recibe 409. Es lo que se quiere: es la misma oferta.

Lista con filas activas **e inactivas** (con su motivo), más `practicaPrincipalId` explícito y, por
fila, `codigo`, `nombre` y `vigenteEnCatalogo` resueltos al leer.

## 4. Concurrencia

Mismo mecanismo que `OfertaHabilitacionService`: la oferta se carga con
`findWithLockByIdAndOrganizationIdAndConsultorioId` (`OPTIMISTIC_FORCE_INCREMENT`), se compara
`expectedVersion` y el reemplazo **no ensucia ninguna columna de la oferta** —recíproca de 04.02—,
así que la versión avanza exactamente una vez.

**Dos reemplazos simultáneos: el diseño original no alcanzaba, y lo mostró MySQL.** Con sólo el
force-increment, los dos pasaban la comparación (ninguno había commiteado) e insertaban filas hijas.
Cada INSERT verifica la FK a la oferta y deja un lock **compartido** sobre su fila; al commitear, el
force-increment pide el **exclusivo**, y cada transacción esperaba el compartido de la otra. InnoDB
mataba a una con `CannotAcquireLockException`, que ningún handler mapea: **500**. Lo reprodujo
`OfertaPracticaIT.reemplazo_concurrente` en la primera corrida.

Por eso la configuración de la oferta toma primero el lock **exclusivo** de su fila
(`OfertaRepositoryPort#bloquearParaConfigurar`, un `SELECT version ... FOR UPDATE`) y compara
`expectedVersion` contra esa versión. El segundo espera a que el primero commitee, lee con lock la
versión nueva —una lectura con lock ve lo último commiteado, no la foto de `REPEATABLE READ`— y
recibe el **409 de versión** de forma determinista. El force-increment se conserva: es lo que mueve
la versión. El lock vive en `AccesoALaConfiguracionDeOferta`, que comparten prácticas y
habilitaciones, así que las habilitaciones de 02.07 quedan serializadas igual: sin el lock, su
perdedor medido murió por el unique (409 genérico "conflicto de datos") y no por deadlock, pero cuál
de los dos sale depende del orden en que InnoDB otorga los locks.

**El orden de escritura importa** y es la trampa de este diseño: Hibernate ejecuta los INSERT antes
que los UPDATE al hacer flush. Si la principal pasa de una práctica existente a una nueva, el INSERT
de la nueva con `principal = 1` correría antes que el UPDATE que le saca la marca a la vieja, y
`uk_oferta_practica_principal` rechazaría el reemplazo legítimo. Por eso el servicio escribe en dos
fases: primero bajas y desmarcados **con flush**, después altas y la marca nueva.

## 5. Lo que expone el `spi` para C-4 y F-4

`offering.spi.PracticasDeOfertaDirectory` (interfaz nueva, para no romper los dobles de
`OfertaDirectory` que ya existen en los tests de otros módulos):

```java
List<PracticaDeOferta> practicasHabilitadas(long organizationId, long consultorioId, long ofertaId);
Optional<Long> practicaPrincipal(long organizationId, long consultorioId, long ofertaId);
```

`PracticaDeOferta(long practicaId, boolean principal)`. Son **lectura viva** (el estado actual de la
configuración, en el sentido de 03.03), no copia: quien necesita fijar qué se devengó copia el id en
su propio hecho. Devuelven sólo filas **activas**, acotadas por organización **y** sede (la oferta se
resuelve primero con la sede en el `WHERE`, como `OfferingOfertaDirectory`). **No filtran por estado
de la oferta**: una sesión de una oferta dada de baja ayer todavía tiene que poder devengarse con su
principal.

Cómo lo usa F-4, según DP-11:

```
practicas = sesion.practicasRealizadas()
if practicas.isEmpty():  practicas = practicaPrincipal(...).map(Set::of).orElse(Set.of())
alerta por cada p en sesion.practicasRealizadas() que no esté en practicasHabilitadas(...)
```

## 6. Auditoría

Entidad auditada: la **oferta** (`ENTITY_HABILITACION = "Oferta"`, el mismo criterio que
habilitaciones). Eventos: `OFERTA_PRACTICA_ADDED`, `OFERTA_PRACTICA_REMOVED`,
`OFERTA_PRACTICA_PRINCIPAL_CHANGED`, con `practicaId` en el detalle. Dentro de la transacción.

## 7. Design challenge (CLAUDE.md §3)

1. **Ownership.** `oferta_practica` es de `offering`: nadie más la lee ni la escribe; los demás
   preguntan por `offering.spi.PracticasDeOfertaDirectory`. `practica` sigue siendo de `resource` y
   `offering` sólo la lee por `resource.spi.CatalogoDirectory`. La FK a `practica(id)` es integridad
   referencial, no acceso: ningún código de `offering` consulta esa tabla.
2. **Ciclos.** La arista nueva es `offering → resource.spi`, que **ya existe** (`EspacioDirectory`).
   `resource` depende sólo de `organization` y `platform`, que no dependen de `offering`. Los
   consumidores futuros (`person` en C-4, `billing` en F-4) ya dependen o pueden depender de
   `offering.spi` sin ciclo: `offering` no importa ninguno de los dos. Verificado con
   `ModuleArchitectureTest`.
3. **Tenant.** `organization_id NOT NULL`, primera columna de los dos uniques y de los índices.
   Toda consulta del puerto filtra por `organization_id`. `EsquemaMultiTenantIT` la ve sin excepción.
4. **Reglas maestras.** No confunde Turno/Sesión: la tabla dice qué **puede** prestar la oferta,
   nunca qué se prestó (eso es el tratamiento, DP-05 y RF-M06-008 "no crear PracticaEnAtencion por
   asistir"). No toca obligación, cobro ni caja: el devengo sigue en `billing`.
5. **Baja lógica.** Quitar una práctica la da de baja con motivo; la fila y su historia quedan.
   Ningún DELETE.
6. **Contrato.** Aditivo: dos operaciones nuevas y dos esquemas nuevos; reusa `practica-no-utilizable`,
   `not-found`, `conflict` y `validation-error`. Minor: 0.58.0.
7. **Ruta crítica.** Los cimientos existen: oferta (02.06), habilitaciones con force-increment
   (02.07, medido contra MySQL), catálogo M06 con `spi` (02.05) y `practicasRealizadas` en el cierre
   (06.04). Este paquete no adelanta a F-4: le deja el `spi` y no lo cablea.
8. **El caso que rompe el diseño.** Dos administradores cambian a la vez la principal de la misma
   oferta, uno a la práctica A (que ya estaba) y otro a la B (nueva). Sin la escritura en dos fases,
   el reemplazo **legítimo y solitario** "cambiar la principal a una práctica nueva" fallaría siempre
   por el orden INSERT-antes-que-UPDATE de Hibernate; con ella, cada uno pasa solo. Juntos, sin el
   lock de fila del §4, se trababan en un deadlock y el perdedor recibía 500 (medido); con el lock,
   el segundo espera y recibe el 409 de versión, nunca una oferta con dos principales ni con
   ninguna. Lo prueban el unitario del orden y el IT concurrente.
   Segundo caso adverso: la práctica principal se da de baja en M06. La fila de `oferta_practica`
   sigue activa y principal, el `spi` la sigue devolviendo, y la pantalla la muestra con
   `vigenteEnCatalogo = false`: devengar una sesión con una práctica histórica es correcto
   (RN-M06-002), y elegir otra principal es decisión del centro, no un efecto lateral.

## 8. Decisiones a revisar

1. **Lista vacía = sin prácticas**, no "todas". Es lo contrario de las habilitaciones y lo dice el
   contrato con palabras. Si se prefiere que toda oferta clínica tenga al menos una práctica, es una
   regla nueva (y una migración de datos para las ofertas existentes).
2. **Una práctica no vigente en el catálogo no se puede agregar, pero sí se puede conservar.**
3. **Lectura con pertenencia a la organización**, como las habilitaciones; no hay `oferta:read`.
