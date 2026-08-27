# AKINE-02.06 — Plan de implementación

> **Para quien ejecute esto:** las tareas se hacen de a una, en orden, con su ciclo de test y su
> commit.

**Goal:** separar qué existe como concepto (`Servicio`) de cómo lo presta un centro concreto
(`OfertaServicioConsultorio`), que es la regla maestra 14 y la Fase 1 de las cinco de §30.7.

**Architecture:** módulo nuevo `offering`, propietario de las dos tablas. `servicio` es global sin
`organization_id`; `oferta_servicio_consultorio` lleva tenant y sede. `offering` consume
`organization.spi` y `platform.spi.audit`, y **nadie lo consume a él** todavía.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · MySQL 8.4 · Flyway · JPA · ArchUnit · Testcontainers ·
JaCoCo · Angular 21.2.6 · Vitest.

**Spec:** `docs/diseno/AKINE-02.06-servicio-y-oferta.md` — leerla entera antes de la Tarea 1.

---

## Cómo se trabaja acá — lecciones de AKINE-02.04, no repetirlas

**No TDD.** Cada tarea es implementación → test → verificación → commit. Es decisión explícita del
usuario, anotada para que no parezca un olvido: el `CLAUDE.md` §4 dice lo contrario. La verificación
no se relaja: ninguna tarea cierra sin sus tests en verde y su salida pegada.

**UN SOLO BUILD POR REPOSITORIO A LA VEZ.** Maven no aísla `target/` entre procesos y npm tampoco
aísla su salida. En 02.04, dos builds simultáneos produjeron **un rojo falso** —una clase compilada
rancia que hacía fallar un test con el fuente verificablemente limpio— y **borraron un refactor en
curso**. Si ves un fallo que no corresponde al código que estás mirando, reintentá una vez antes de
creerle.

**Los tests se verifican por mutación.** Un test verde no prueba que proteja algo. Rompé a propósito
la conducta que dice cuidar, confirmá que falla **ese** test y no otro, restaurá, y pegá lo que
viste. Así se descubrió en 02.04 que el test de determinismo del calculador **pasaba igual con los
comparadores borrados**.

**Contar tests sumando los XML de surefire da de más.** Las clases `@Nested` emiten un XML por clase
anidada más uno del contenedor. Eso produjo un falso `Tests run: 0` y un conteo inflado en dos
tareas distintas. **Vale el número que imprime Maven.**

**Los nombres de archivo de este plan están verificados contra el repo.** Si alguno no existe,
decilo en vez de improvisar: tres briefs de 02.04 nombraron archivos inventados.

---

## Global Constraints

- **Java 21 exacto**, Spring Boot **4.1.1**, MySQL **8.4**.
- **Toda tabla de negocio lleva `organization_id NOT NULL`** y todo índice empieza por él. Única
  excepción de esta etapa: `servicio` — **ADR-0023**, que además consolida las cuatro anteriores.
- **Cross-tenant → 404, nunca 403.** Un 403 confirma que la entidad existe.
- **Falta de contexto → 403, nunca 401.** El interceptor del frontend borra el token ante un 401.
- **Baja lógica siempre.** Ningún `DELETE` físico.
- **La auditoría se escribe en la transacción del negocio.** `AuditTrail.record` es
  `Propagation.MANDATORY`: un listener post-commit no puede llamarlo, y así debe seguir.
- **`application` consume puertos en `domain`**, nunca repositorios de `infrastructure`.
- **Las excepciones del módulo se mapean en el advice del módulo**, nunca en `GlobalExceptionHandler`.
- **El `type` del 409 por concurrencia es `conflict`.** `OptimisticLockingFailureException` plano lo
  mapea el handler global. No repetir la inexactitud de 02.02 y 02.05.
- **La versión del contrato vive en TRES lugares:** `info.version` del yaml, `akine.contract.version`
  del `pom.xml`, y la misma propiedad en `application.yml`. Bumpear uno solo rompe el build.
- **Datos sintéticos únicamente.**
- **Sin códigos de permiso nuevos.**
- Spring Boot 4 rompe cosas de 3.x: `spring-boot-starter-webmvc`, `@WebMvcTest` en
  `org.springframework.boot.webmvc.test.autoconfigure`, Jackson 3, `MySQLContainer` no genérico.

---

## Tarea 1 — ADR-0023 consolidadora y migración V24

**Files:**
- Create: `docs/adr/0023-tablas-globales-sin-organization-id.md`
- Modify: `docs/adr/0019`, `0020`, `0021`, `0022` — marcarlos **superseded by ADR-0023**
- Create: `src/main/resources/db/migration/V24__m27_servicio_y_oferta.sql`
- Test: `src/test/java/com/akine/offering/infrastructure/ServicioYOfertaMigrationIT.java`

**Produces:** las dos tablas. Las consumen las Tareas 2 y 3.

- [ ] **Paso 1: escribir ADR-0023, que consolida**

ADR-0020 y ADR-0021 dicen textualmente que la cuarta excepción a ADR-0004 **no escribe otro ADR
incremental sino que consolida los anteriores en uno que los supersede**. ADR-0022 fue esa cuarta y
no lo hizo — su línea 117 reconoce la deuda. `servicio` es la quinta y **acá se paga**.

ADR-0023 tiene que:
- declarar **en un solo lugar el criterio**: qué hace que una tabla sea legítimamente global. Hoy hay
  que leer cinco documentos juntos para deducirlo.
- listar las cinco tablas o familias con su motivo: identidad (0019), rol de plataforma (0020),
  catálogos clínicos (0021), feriados (0022), y `servicio`.
- marcar los cuatro anteriores como superseded, y decir en cada uno que su contenido vive ahora acá.
- decir qué **no** califica, que es lo que evita la sexta excepción por analogía floja.

- [ ] **Paso 2: escribir V24**

Cabecera con la forma de V19/V21/V23: trazabilidad, ADRs, propietario, y las decisiones no obvias
explicadas. El DDL exacto está en §3 del diseño. Explicar en la cabecera, como mínimo:

```
-- POR QUE `servicio` NO LLEVA organization_id NI owner_key
--
-- No hay dos poblaciones que discriminar. `especialidad` y `practica` (V20) llevan owner_key
-- porque conviven conceptos globales y conceptos propios de un tenant en la misma tabla, y en
-- MySQL varios NULL no colisionan en un unique. Un Servicio es siempre global: RN-M27-001 lo
-- dice y la seccion 30.6 no lista organizationId entre sus campos. La configuracion propia de
-- cada centro no va aca, va en la Oferta.
--
-- POR QUE `oferta` NO SE PARECE AL CATALOGO SINO A `espacio`
--
-- organization_id NOT NULL: toda oferta pertenece a una sede real. No existe "oferta global".
-- Por eso no lleva owner_key —no tiene el problema que owner_key resuelve— y si lleva el
-- centinela deleted_key, para poder reusar un nombre comercial despues de una baja logica.
--
-- POR QUE GRUPAL EXIGE capacidad > 1 Y NO > 0
--
-- RF-M27-003 dice "mayor a cero". Una oferta grupal de capacidad 1 es una individual mal
-- rotulada, y el motor de inscripciones de F2 la tratatia como un grupo de una persona.
-- DECISION REVISABLE: aflojarlo a > 0 es cambiar este CHECK y nada mas.
--
-- POR QUE precio_base Y moneda VIAJAN JUNTOS
--
-- Un precio sin moneda no es un precio, y este SaaS va a operar en mas de un pais.
```

- [ ] **Paso 3: escribir el IT de migración**

```java
@Test void servicio_no_tiene_organization_id() { }
@Test void servicio_no_tiene_owner_key() { }
@Test void oferta_lleva_organization_id_not_null() { }
@Test void todos_los_indices_declarados_de_oferta_empiezan_por_organization_id() { }
@Test void una_oferta_grupal_de_capacidad_uno_es_rechazada() { }
@Test void una_oferta_individual_de_capacidad_uno_es_aceptada() { }
@Test void un_precio_sin_moneda_es_rechazado() { }
@Test void una_moneda_sin_precio_es_rechazada() { }
@Test void vigencia_hasta_igual_a_vigencia_desde_es_rechazada() { }
@Test void dos_ofertas_activas_de_la_misma_sede_no_pueden_compartir_nombre_comercial() { }
@Test void el_nombre_comercial_se_puede_reusar_despues_de_una_baja() { }
```

> El test de índices **excluye la PRIMARY KEY** y **los índices de soporte que InnoDB crea solo
> para las FK**. No son violaciones: son índices que nadie declaró. Se filtran consultando
> `information_schema.table_constraints`, no por prefijo de nombre — así lo dejó 02.04, y es más
> fuerte que confiar en la convención.

- [ ] **Paso 4: correr y commitear**

```
./mvnw -o -Dtest=ServicioYOfertaMigrationIT test
```

---

## Tarea 2 — Dominio del módulo `offering`

**Files:** `src/main/java/com/akine/offering/domain/` — `Servicio`, `OfertaServicioConsultorio`,
`Naturaleza`, `Modalidad`, `EsquemaCobro`, `PermissionCodes`, y `domain/exception/`.
**Test:** `src/test/java/com/akine/offering/domain/OfertaTest.java`

**Consumes:** las tablas de la Tarea 1.
**Produces:** las entidades. Las consumen las Tareas 3 a 5.

- [ ] **Paso 1: entidades**

Siguen la forma de `com.akine.resource.domain.Espacio`: `@Entity`, `@Version`, `@Column` explícito,
baja lógica con motivo, javadoc que dice **por qué**. Mapean el esquema **existente** — Flyway es la
única autoridad, nada de generación.

- [ ] **Paso 2: la regla que esta etapa existe para proteger**

`Servicio` expone `modalidadDefault`, `requiereCasoClinicoDefault` y `generaRegistroClinicoDefault`.
**Son propuesta inicial, no regla.** El javadoc tiene que decirlo con el motivo: RF-M06-006 —*"los
defaults no reemplazan la configuración concreta de cada Oferta"*— y RN-M06-005 —la naturaleza
*"sirve para clasificación y no debe imponer por sí sola comportamiento clínico"*—.

Y la regla maestra 15, que es estructural: **ninguna decisión puede depender del nombre**. No debe
existir un solo `if` sobre `nombre` o `codigo` en todo el módulo. RN-M06-006 lo dice con nombres
propios: *"no deben existir condicionales funcionales por nombres como Pilates, RPG, Yoga u
Osteopatía"*.

- [ ] **Paso 3: tests**

```java
@Test void una_oferta_no_hereda_los_defaults_del_servicio_al_editarse() { }
@Test void cambiar_un_default_del_servicio_no_toca_las_ofertas_existentes() { }
@Test void la_baja_exige_motivo() { }
@Test void la_baja_es_logica_y_conserva_la_fila() { }
@Test void una_vigencia_invertida_es_rechazada() { }
```

Los dos primeros son **CA-M03-007-06 y CA-M06-006-06** hechos test: *"modificar el intervalo o
capacidad por defecto no altera retroactivamente ofertas existentes"*.

---

## Tarea 3 — Puertos y repositorios

**Files:** `offering/domain/port/OfferingRepositoryPorts.java`,
`offering/infrastructure/ServicioRepository.java`, `OfertaRepository.java`.

Modelo: `resource/domain/port/CatalogoRepositoryPorts.java` y
`resource/infrastructure/EspacioRepository.java`.

**Lo que decide la correctitud acá:**

- **`ServicioRepositoryPort` NO recibe `organizationId`.** La tabla es global. Su javadoc tiene que
  decir que la ausencia es una **decisión (ADR-0023) y no un olvido**, y que agregar el parámetro
  significa haber entendido mal el alcance. Es exactamente lo que hizo `FeriadoRepositoryPort` en
  02.04 y frenó a más de uno.
- **`OfertaRepositoryPort` filtra SIEMPRE por `organizationId`**, y por `consultorioId` donde el
  alcance sea la sede. Una fila de otro tenant no resuelve nunca.

- [ ] Correr `./mvnw -o -Dtest=ModuleArchitectureTest test` — ArchUnit trabaja por slices sobre
`com.akine`, así que el módulo nuevo queda cubierto **sin tocar el test**. Si falla, la dirección de
alguna dependencia está mal; no se toca la regla.

---

## Tarea 4 — `ServicioService`: el catálogo global

**Files:** `offering/application/ServicioService.java`, commands y views,
`domain/exception/ServicioCodeTakenException.java`, `ServicioNameTakenException.java`,
`ServicioNotAccessibleException.java`, `ServicioInactivoException.java`.
**Test:** `src/test/java/com/akine/offering/application/ServicioServiceTest.java`

**Autorización: rol de plataforma.** Leer cómo lo resuelve `resource/application/CatalogoService.java`
para el catálogo global y seguir ese patrón — **no inventar un mecanismo nuevo**.

> **Hueco heredado que esta tarea NO cierra, y hay que anotarlo en el javadoc:** hoy ningún endpoint
> le dice al frontend si quien mira tiene rol de plataforma. La pantalla de administración del
> catálogo arrastra el mismo problema que dejó abierto RF-M06-005 en 02.05.

- [ ] **Tests**

```java
@Test void sin_rol_de_plataforma_el_alta_da_403() { }
@Test void un_codigo_repetido_entre_servicios_vigentes_da_409() { }
@Test void un_codigo_de_un_servicio_dado_de_baja_se_puede_reusar() { }
@Test void la_baja_no_cascadea_sobre_las_ofertas_que_lo_referencian() { }
@Test void la_auditoria_se_escribe_en_la_misma_transaccion() { }
```

El cuarto es **el caso que rompe el diseño** (§7.8 del diseño): dar de baja un servicio global con
ofertas activas en varios centros. Las ofertas vigentes **siguen operando**; lo que se impide es
crear ofertas nuevas sobre un servicio inactivo (RF-M27-002).

---

## Tarea 5 — `OfertaService`: la configuración de cada sede

**Files:** `offering/application/OfertaService.java`, commands y views,
`domain/exception/OfertaNotAccessibleException.java`, `OfertaNameTakenException.java`,
`ServicioNoOfertableException.java`.
**Test:** `src/test/java/com/akine/offering/application/OfertaServiceTest.java`

**Modelo:** `resource/application/EspacioService.java` — su cabecera, su orden de autorización, su
manejo de excepciones.

**El orden de autorización no es cosmético.** Pertenencia y tenant **primero**, permiso **después**.
Una sede de otro tenant sale 404 —un 403 confirmaría que existe y bastaría recorrer ids—; dentro del
propio tenant decide el permiso, y ahí un 403 no filtra nada.

Mutaciones con `consultorio:manage`. Lecturas por pertenencia. **Sin códigos nuevos.**

- [ ] **Tests**

```java
@Test void una_sede_de_otro_tenant_da_404_y_no_403() { }
@Test void sin_consultorio_manage_el_alta_da_403() { }
@Test void no_se_puede_crear_una_oferta_sobre_un_servicio_inactivo() { }
@Test void dos_sedes_pueden_ofertar_el_mismo_servicio_con_configuraciones_distintas() { }
@Test void una_oferta_grupal_exige_capacidad_mayor_que_uno() { }
@Test void la_edicion_con_version_vieja_da_409_con_type_conflict() { }
@Test void la_baja_es_logica_y_conserva_la_fila() { }
```

El cuarto es **CA-M03-006-06 y CA-M27-003-06** hechos test — *"dos consultorios pueden ofrecer
Pilates Reformer con duración y capacidad diferentes"*—, y es literalmente el objetivo de la etapa.
Si ese test no existe, la etapa no probó lo que dice hacer.

---

## Tarea 6 — API y advice

**Files:** `offering/api/ServicioController.java`, `OfertaController.java`,
`OfferingProblemHandler.java`, `offering/api/dto/`.
**Test:** `src/test/java/com/akine/offering/api/OfertaControllerTest.java`,
`ServicioControllerTest.java`

Los ocho endpoints de §5 del diseño. Modelo: `resource/api/EspacioController.java` y
`EspacioProblemHandler.java`.

**Todas las excepciones de dominio se mapean, sin excepción.** Una sin entrada en el advice es un
500 con stacktrace. Recorré `offering/domain/exception/` **en disco** y cruzá contra el advice; no
confíes en esta lista ni en ninguna otra.

El advice vive **en este módulo**. Ponerlo en `GlobalExceptionHandler` crea un ciclo
`platform.api → offering.domain` que ArchUnit rechaza.

**El 409 por concurrencia es `conflict`.** No `concurrent-modification`.

- [ ] `./mvnw -o -Dtest=OfertaControllerTest,ServicioControllerTest,ModuleArchitectureTest test`

---

## Tarea 7 — Contrato OpenAPI 0.12.0

**Files:** `openapi/akine-api.yaml`, `pom.xml`, `src/main/resources/application.yml`.

**La versión vive en tres lugares.** Bumpear el yaml solo rompe la verificación de consistencia del
propio build. Los tres a 0.12.0.

Bump **menor**: aditivo puro.

**Fuente de verdad: el código.** Leé los dos controllers y sus DTOs. El contract test verifica drift
contra los mappings reales: si discrepan, **se corrige el yaml**, jamás se afloja el test.

**Regenerá desde las anotaciones en una sola pasada** (`-Dakine.contract.update=true`) en vez de
transcribir a mano. En 02.04, publicar a mano y corregir después dejó el contract test rojo.

- [ ] `./mvnw -o -Dtest='*Contract*' test` — **verde, cero drift.**

---

## Tarea 8 — Integración contra MySQL real

**Files:** `src/test/java/com/akine/offering/OfertaIT.java`

**Escenarios:**

```java
@Test void dos_sedes_ofertan_el_mismo_servicio_con_configuraciones_distintas() { }
@Test void una_oferta_de_otro_tenant_no_resuelve_ni_con_el_id_correcto() { }
@Test void dar_de_baja_el_servicio_global_no_apaga_las_ofertas_vigentes() { }
@Test void la_baja_logica_conserva_la_fila_y_su_historia() { }
@Test void la_auditoria_queda_escrita_en_la_misma_transaccion_del_alta() { }
```

**Cuidado con las fechas.** En 02.04, dos escenarios quedaron atados al calendario y **iban a empezar
a fallar solos** en una semana. Si el fixture siembra una vigencia relativa al reloj y el escenario
usa fechas fijas, se cruzan. Sembrá lejos en el pasado y desacoplá la clase del calendario.

**Si necesitás concurrencia**, el precedente real de este repo es `com.akine.diferidos.Concurrencia`
con `MembershipConcurrenteIT` y `LimiteDePlanConcurrenteIT` — **no** existe ningún
`EspaciosConcurrenteIT`.

- [ ] `./mvnw -o verify` — suite completa más los gates de JaCoCo.

> **La cobertura de RAMA del backend está en 78,39 % y `pom.xml` NO la gatea** — solo `LINE` e
> `INSTRUCTION`. El build pasa legítimamente. Reportá el número real; **no bajes ningún gate y no
> agregues el de rama**, que hoy rompería el build y es decisión pendiente del usuario.

- [ ] `/simplify` y `/code-review`.

---

## Tarea 9 — Frontend: regenerar el cliente en 0.12.0

**Repo `appKine-web`, misma rama.**

```
npm run api:generate
```

Después **editar a mano** `contractVersion` en **`src/environments/environment.ts` Y
`environment.prod.ts`** — no hay automatización — y verificar con `npm run api:check`.

**Nunca editar `src/app/api/generated/`.** ESLint y Prettier lo excluyen porque el pipeline lo
regenera y difea; un cambio a mano rompe el gate en cada corrida.

Reportá si el generador emite warnings y si algún modelo existente cambió de forma: el bump se
declaró aditivo y un cambio contradice esa declaración.

---

## Tarea 10 — Frontend: catálogo de servicios

**Files:** `src/app/features/offering/` — rutas, `CatalogoDeServiciosPage`.

**Colisión léxica, leerla dos veces.** Este repo ya tiene
`features/resource/models/situacion-de-servicio.ts`, donde "servicio" significa **si un espacio
físico está operativo** (M04). Nada nuevo se llama `ServicioPage` a secas. La feature es
`features/offering/`, la clase es `CatalogoDeServiciosPage`, el selector
`app-catalogo-de-servicios-page`.

Solo lectura salvo rol de plataforma — que **el frontend hoy no puede saber**. Mostrá el catálogo y
ofrecé las acciones de administración de forma que un 403 sea legible, no un error crudo.

Convenciones y trampas verificadas: `.superpowers/sdd/AKINE-02.04-plan/frontend-recon.md`. Las de
siempre: `HttpTestingController`, matchers en `core/testing/rutas-api.ts`, `TIMEOUT_AXE` en todo
`it()` que audite accesibilidad, y **un `computed()` jamás lee `control.value`** —no tiene
dependencias de signal, cachea para siempre y la pantalla miente sin fallar—.

---

## Tarea 11 — Frontend: ofertas de la sede

**Files:** `src/app/features/offering/pages/ofertas/` — `OfertasDeLaSedePage`.

Es **la pantalla que un administrador usa todos los días**. Alta, edición y baja de ofertas, con el
servicio elegido del catálogo global.

Cuando el admin elige un servicio, los `*_default` del catálogo llegan como **propuesta editable**,
no como valores impuestos. Que la pantalla lo diga: un campo prellenado que el usuario no sabe que
puede cambiar es un default disfrazado de regla.

**Pineá las rutas de la feature**, como hicieron 02.04 y la tarea de deuda: `Router` real sobre el
árbol real, navegar, asertar el componente resuelto, y una aserción negativa contra el comodín. Y
contraprobalo rompiendo un path.

`npm run test:ci` gatea 80 % en las cuatro métricas y el repo está en ~81,8 % de rama. Corré
`test:ci`, no `test`, y mirá el número antes de commitear.

---

## Tarea 12 — Cierre

- [ ] Registro de cierre en `docs/AKINE_IMPLEMENTATION_PLAN.md`, con la forma de los anteriores.
- [ ] Actualizar los dos `CLAUDE.md` y `docs/PROJECT_MAP.md`.
- [ ] Anotar lo que la etapa deja abierto (§8 del diseño), **incluida la contradicción del plan**:
      su sección de 02.06 instruye modificar `resource` y esta etapa creó `offering`, siguiendo
      `AGENT.md`. Que quede escrito para que la próxima no lo relea como instrucción vigente.
- [ ] Declarar con precisión qué se verificó y qué no. Si no corrieron E2E ni QA manual contra la
      base, **decirlo** — el §6 del `CLAUDE.md` lo declara bloqueante para deploy, y ya hay cuatro
      etapas cerradas con esa deuda.

---

## Autorrevisión del plan

**Cobertura del diseño:** §1 decisiones → T1, T4, T5 · §2 deuda de ADR → T1 · §3 modelo → T1, T2 ·
§4 módulo y dependencias → T3 · §5 contrato → T6, T7 · §6 frontend → T9–T11 · §7 design challenge →
T1, T3, T4 · §8 pendientes → T12.

**Sin placeholders.** Ningún "similar a la tarea N", ningún "agregar validación apropiada".

**Nombres verificados contra el repo**, no inventados: `com.akine.diferidos.Concurrencia`,
`MembershipConcurrenteIT`, `CatalogoService`, `EspacioService`, `EspacioProblemHandler`,
`CatalogoRepositoryPorts`, `situacion-de-servicio.ts`.

**Riesgo anotado:** el módulo `offering` es nuevo y ArchUnit lo cubre por slices sin cambios — pero
si alguna dependencia queda mal orientada, el fallo va a aparecer en `ModuleArchitectureTest` de la
Tarea 3 y **la respuesta correcta casi nunca es relajar la regla**, sino corregir la dirección.
