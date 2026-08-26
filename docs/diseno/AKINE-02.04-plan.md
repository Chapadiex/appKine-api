# AKINE-02.04 — Plan de implementación

> **Para quien ejecute esto:** las tareas se hacen de a una, en orden, con su ciclo de test y
> su commit. Los pasos usan checkbox (`- [ ]`).

**Goal:** dar al sistema una fuente confiable de en qué franjas atiende cada profesional en
cada sede, resolviendo horario semanal, excepciones y feriados de forma determinista y
explicable.

**Architecture:** bloques recurrentes con vigencia en hora local + excepciones de cierre y
apertura + calendario de feriados; la disponibilidad efectiva **se calcula al leer**, nunca se
materializa. Módulo propietario `resource`, que consume `organization.spi` como ya lo hace
`EspacioService`.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · MySQL 8.4 · Flyway · JPA · ArchUnit ·
Testcontainers · JaCoCo (gate 80 %) · Angular 21.2.6 · Vitest · Playwright.

**Spec:** `docs/diseno/AKINE-02.04-disponibilidad.md` — leerla entera antes de la Tarea 1.
El plan argumenta desde ahí; las dos cosas viajan juntas.

---

## Desviación declarada del workflow

`CLAUDE.md` §4 dice "Tests primero. TDD no es opcional en este repo". **Este plan no usa
TDD**: cada tarea es implementación → test → verificación → commit.

Es una decisión explícita del usuario (preferencia registrada: código primero, tests después,
y no generar tests de más). Se anota acá para que sea visible y no parezca un olvido. La
verificación no se relaja: ninguna tarea cierra sin sus tests en verde.

**Dónde sí hay densidad de tests:** el calculador de disponibilidad efectiva (Tarea 5). Es el
corazón de la etapa y el único lugar donde un error silencioso se propaga a todo F5.

---

## Global Constraints

Copiadas del diseño y de las reglas heredadas. Aplican a **todas** las tareas.

- **Java 21 exacto**, Spring Boot **4.1.1**, MySQL **8.4**. `maven-enforcer` lo verifica.
- **Toda tabla de negocio lleva `organization_id NOT NULL`** y **todo unique e índice empieza
  por él**. Única excepción de esta etapa: `feriado` (ADR-0022).
- **Cross-tenant → 404, nunca 403.** Un 403 confirma que la entidad existe.
- **Falta de contexto → 403, nunca 401.** El interceptor del frontend borra el token ante
  cualquier 401 y entra en bucle de login.
- **Baja lógica siempre.** Ningún `DELETE` físico sobre información histórica.
- **La auditoría se escribe en la transacción del negocio**, nunca en un listener post-commit.
- **`resource` consume puertos de `domain`**, nunca repositorios de `infrastructure`. ArchUnit
  lo verifica.
- **Las excepciones del módulo se mapean en el advice del módulo**, nunca en
  `GlobalExceptionHandler`.
- **Datos sintéticos únicamente** en tests, seeds y fixtures.
- **Lecturas: `colaborador:read`. Mutaciones: `consultorio:manage`.** Sin códigos nuevos.
- **Contrato aditivo: `0.10.0 → 0.11.0`.** El backend es propietario del OpenAPI; el frontend
  regenera y fija su cliente. Nunca asumir commit atómico entre repos.
- **Spring Boot 4 rompe cosas de 3.x.** Ante duda de API, inspeccionar el jar antes de asumir.
  Ya mordió: `spring-boot-starter-webmvc` (no `-web`), `@WebMvcTest` en
  `org.springframework.boot.webmvc.test.autoconfigure`, `RestTestClient` +
  `@AutoConfigureRestTestClient`, Jackson 3.

---

## Estructura de archivos

### Backend — `com.akine.resource` (propietario)

| Archivo | Responsabilidad |
|---|---|
| `domain/BloqueDisponibilidad.java` | Entidad JPA del bloque recurrente |
| `domain/DisponibilidadExcepcion.java` | Entidad JPA de la excepción |
| `domain/Feriado.java` | Entidad JPA del feriado global |
| `domain/CalendarioSede.java` | Entidad JPA de la política de la sede |
| `domain/TipoExcepcion.java`, `domain/MotivoExcepcion.java` | Listas cerradas |
| `domain/IntervaloLocal.java` | Value object: par de `LocalTime`, aritmética de intervalos |
| `domain/FranjaEfectiva.java` | Intervalo resuelto + la regla que lo produjo y la que lo recortó |
| `domain/OrigenFranja.java` | Lista cerrada de reglas: `BLOQUE`, `APERTURA`, `CIERRE`, `FERIADO` |
| `domain/DisponibilidadEfectivaCalculator.java` | **El corazón.** Lógica pura, sin Spring, sin JPA |
| `domain/port/DisponibilidadRepositoryPorts.java` | Puertos de los cuatro repositorios |
| `domain/exception/*.java` | Cinco excepciones de dominio |
| `application/DisponibilidadService.java` | Alta, edición, baja y lectura de bloques |
| `application/ExcepcionService.java` | Alta, listado y baja de excepciones |
| `application/CalendarioService.java` | Política de la sede + feriados de la ventana |
| `application/DisponibilidadEfectivaService.java` | Orquesta el calculador con los repos y el huso |
| `infrastructure/*Repository.java` | Cuatro repos JPA |
| `infrastructure/ResourceDesvinculacionProbe.java` | Implementa `ColaboradorDesvinculacionProbe` |
| `spi/DisponibilidadImpactProbe.java` | Costura de F5, sin implementación real |
| `api/DisponibilidadController.java` | Bloques + efectiva |
| `api/ExcepcionController.java` | Excepciones |
| `api/CalendarioController.java` | Política, feriados de sede, catálogo global de feriados |
| `api/DisponibilidadProblemHandler.java` | Advice del módulo |
| `api/dto/*.java` | DTOs de request y response |

### Backend — `com.akine.organization` (solo SPI aditivo)

| Archivo | Responsabilidad |
|---|---|
| `spi/ConsultorioMembershipSnapshot.java` | Record nuevo, con `consultorioId` y `validAt` |
| `spi/MembershipDirectory.java` | Interfaz de búsqueda por id |
| `infrastructure/OrganizationMembershipDirectory.java` | Implementación |

### Migraciones

| Archivo | Contenido |
|---|---|
| `V22__m05_feriado_global.sql` | `feriado` + seed AR |
| `V23__m05_disponibilidad_profesional.sql` | Las otras tres tablas |

### Frontend — `appKine-web/src/app/features/resource/disponibilidad/`

| Archivo | Responsabilidad |
|---|---|
| `disponibilidad.routes.ts` | Rutas de la feature |
| `data/disponibilidad.store.ts` | Estado de la feature sobre el cliente generado |
| `editor-semanal/editor-semanal.component.ts` | Grilla de 7 días |
| `excepciones/excepciones-panel.component.ts` | Alta y listado de excepciones |
| `efectiva/preview-efectiva.component.ts` | Semana resuelta con la regla aplicada |
| `calendario/calendario-sede.component.ts` | Política de feriados + feriados de la ventana |

---

## Tarea 1 — ADR-0022 y migración V22: el calendario de feriados

**Files:**
- Create: `docs/adr/0022-feriados-globales-sin-organization-id.md`
- Create: `src/main/resources/db/migration/V22__m05_feriado_global.sql`
- Test: `src/test/java/com/akine/resource/infrastructure/FeriadoMigrationIT.java`

**Interfaces:**
- Produces: tabla `feriado (id, pais, fecha, nombre, tipo, created_at, updated_at)` con
  `UNIQUE (pais, fecha)`. La consumen las Tareas 4, 6 y 8.

- [ ] **Paso 1: Escribir ADR-0022**

Sigue la forma de `0021-catalogos-clinicos-globales-sin-organization-id.md`. Contenido
mínimo — contexto, decisión, alternativas, consecuencias:

> **Decisión.** `feriado` no lleva `organization_id`. Es una excepción declarada a ADR-0004,
> con el mismo fundamento que ADR-0019 (identidad global) y ADR-0021 (catálogos globales): un
> feriado nacional es un hecho del calendario público, no un dato de ningún tenant.
>
> **Alternativa descartada — una fila por organización.** Duplicaría el mismo feriado tantas
> veces como tenants haya y haría que dos centros puedan discrepar sobre si el 25 de mayo
> existe. La decisión que sí es del centro —si cierra o no— vive en `consultorio_calendario`,
> que **sí** lleva `organization_id`.
>
> **Consecuencia.** Ninguna consulta a `feriado` filtra por tenant, y por lo tanto **ninguna
> query de esta tabla puede recibir un `organizationId`**: si alguien se lo agrega, es que
> entendió mal el alcance. El aislamiento multi-tenant de esta etapa vive en las otras tres
> tablas.

- [ ] **Paso 2: Escribir V22**

```sql
-- =====================================================================================
-- AKINE-02.04 — Calendario de feriados (M05).
--
-- Trazabilidad: RF-M05-004 (registrar excepcion). ADR-0003 (Flyway unica autoridad),
-- ADR-0022 (feriados globales sin organization_id).
--
-- Propietario de la tabla: modulo `resource`.
--
-- POR QUE ESTA TABLA NO LLEVA organization_id
--
-- Un feriado nacional no es de nadie. Es el mismo caso de ADR-0021 con los catalogos
-- clinicos globales. La decision que SI es de cada centro —si cierra o no ese dia— vive en
-- `consultorio_calendario`, que lleva organization_id como cualquier tabla de negocio.
--
-- POR QUE EL SEED ENVEJECE, Y POR QUE ESTA BIEN QUE ASI SEA
--
-- Los feriados trasladables y los puentes se fijan por decreto cada anio. Ningun seed puede
-- adelantarse a eso. Por ese motivo la sede SIEMPRE puede cargar una excepcion propia sin
-- depender de que esta tabla este al dia: el seed es una comodidad, no la autoridad.
-- =====================================================================================

CREATE TABLE feriado
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,

    pais       CHAR(2)      NOT NULL DEFAULT 'AR' COMMENT 'ISO 3166-1 alfa-2. Existe desde el dia uno para que sumar otro pais no sea una migracion de datos',
    fecha      DATE         NOT NULL COMMENT 'Fecha calendario del feriado. Sin hora: un feriado es un dia, no un instante',
    nombre     VARCHAR(160) NOT NULL COMMENT 'Denominacion oficial. Se muestra tal cual en la pantalla de calendario',
    tipo       VARCHAR(32)  NOT NULL COMMENT 'Clasificacion oficial. INAMOVIBLE y TRASLADABLE son las dos que cambian el comportamiento del decreto anual',

    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,

    CONSTRAINT pk_feriado PRIMARY KEY (id),

    -- Un pais no puede tener dos feriados el mismo dia. Si dos conmemoraciones caen juntas,
    -- el nombre las junta: son un solo dia no laborable.
    CONSTRAINT uk_feriado_pais_fecha UNIQUE (pais, fecha),

    CONSTRAINT ck_feriado_tipo
        CHECK (tipo IN ('INAMOVIBLE', 'TRASLADABLE', 'PUENTE', 'NO_LABORABLE', 'RELIGIOSO'))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Feriado del calendario nacional (M05). GLOBAL, sin organization_id: ADR-0022. Que un feriado cierre o no una sede lo decide consultorio_calendario, no esta tabla';
```

Seguido del seed. Cargar **2026 y 2027** de Argentina, con los inamovibles reales y los
trasladables en la fecha que efectivamente rigió/rige. Formato:

```sql
INSERT INTO feriado (pais, fecha, nombre, tipo, created_at, updated_at) VALUES
 ('AR', '2026-01-01', 'Año Nuevo', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-03-24', 'Día Nacional de la Memoria por la Verdad y la Justicia', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-04-02', 'Día del Veterano y de los Caídos en la Guerra de Malvinas', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-05-01', 'Día del Trabajador', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-05-25', 'Día de la Revolución de Mayo', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-06-20', 'Paso a la Inmortalidad del General Manuel Belgrano', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-07-09', 'Día de la Independencia', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-12-08', 'Inmaculada Concepción de María', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-12-25', 'Navidad', 'INAMOVIBLE', NOW(6), NOW(6));
```

Completar con los trasladables (17 de junio, 17 de agosto, 12 de octubre, 20 de noviembre),
Carnaval y Viernes Santo del año correspondiente, y repetir el bloque para 2027.

> **Verificar las fechas antes de escribirlas.** Un seed con fechas inventadas es peor que no
> tener seed: nadie las va a revisar después y el sistema va a cerrar centros el día
> equivocado.

- [ ] **Paso 3: Escribir el test de integración**

```java
@SpringBootTest
@Testcontainers
class FeriadoMigrationIT {

	@Test
	void la_tabla_no_tiene_organization_id() {
		// ADR-0022 es una excepcion declarada: si alguien "arregla" la tabla agregandole
		// organization_id, este test se lo dice antes de que el seed se duplique por tenant.
	}

	@Test
	void el_unique_impide_dos_feriados_el_mismo_dia_del_mismo_pais() { }

	@Test
	void el_seed_carga_los_feriados_inamovibles_de_2026_y_2027() { }

	@Test
	void el_check_de_tipo_rechaza_un_valor_libre() { }
}
```

- [ ] **Paso 4: Correr y verificar**

```
./mvnw -Dtest=FeriadoMigrationIT test
```

Esperado: 4 en verde. Flyway aplica V22 sin error sobre una base limpia.

- [ ] **Paso 5: Commit**

```bash
git add docs/adr/0022-feriados-globales-sin-organization-id.md \
        src/main/resources/db/migration/V22__m05_feriado_global.sql \
        src/test/java/com/akine/resource/infrastructure/FeriadoMigrationIT.java
git commit -m "feat: calendario de feriados global con seed argentino (AKINE-02.04)"
```

---

## Tarea 2 — Migración V23: las tres tablas con tenant

**Files:**
- Create: `src/main/resources/db/migration/V23__m05_disponibilidad_profesional.sql`
- Test: `src/test/java/com/akine/resource/infrastructure/DisponibilidadMigrationIT.java`

**Interfaces:**
- Consumes: `feriado` (Tarea 1), `organization`, `consultorio`, `membership`.
- Produces: `consultorio_calendario`, `profesional_disponibilidad`,
  `disponibilidad_excepcion`. Las consumen las Tareas 4 y 6.

- [ ] **Paso 1: Escribir V23**

Cabecera con la misma forma que V19 y V21: trazabilidad, ADRs, propietario, y las decisiones
no obvias explicadas. Las tres tablas, con el DDL exacto de las secciones 2.2, 2.3 y 2.4 del
diseño.

En la cabecera **tienen que quedar escritas estas tres cosas**, porque son las que alguien va
a querer "arreglar" sin entender:

```
-- POR QUE consultorio_calendario ES UNA TABLA Y NO UNA COLUMNA EN consultorio
--
-- Lo natural seria `consultorio.cierra_por_feriado`. Esa tabla es del modulo `organization`
-- y este modulo es `resource`: escribirla rompe la propiedad de tablas que ArchUnit y el
-- design challenge sostienen. La tabla propia cuesta una fila por sede y mantiene la regla.
--
-- Ademas cumple un segundo rol, y por eso se crea a demanda y nunca se borra: es la fila
-- sobre la que se toma el FOR UPDATE que serializa los writes de disponibilidad de la sede.
-- Ver la cabecera de DisponibilidadService.
--
-- POR QUE hora_hasta ADMITE '24:00:00' Y NO SE PERMITE CRUZAR MEDIANOCHE
--
-- MySQL acepta '24:00:00' en una columna TIME (el rango es -838:59:59..838:59:59). Un bloque
-- nocturno se carga como DOS bloques: lunes 22:00-24:00 y martes 00:00-02:00.
--
-- Si se permitiera 22:00-02:00 en una sola fila, `hora_desde < hora_hasta` seria falso para
-- una fila valida, y toda la aritmetica de solapamiento —que es comparacion de extremos—
-- dejaria de funcionar en silencio. El costo de prohibirlo es una fila extra; el costo de
-- permitirlo es que el calculo mienta sin fallar.
--
-- POR QUE NO HAY UNIQUE QUE IMPIDA EL SOLAPAMIENTO
--
-- Haria falta una exclusion constraint, y MySQL 8.4 no las tiene: son de PostgreSQL. Los
-- indices unicos parciales tampoco existen. El solapamiento se valida en aplicacion, y por
-- eso necesita el lock de consultorio_calendario: sin el, dos altas concurrentes insertan
-- dos bloques que se pisan y ninguna de las dos ve a la otra.
```

- [ ] **Paso 2: Escribir el test de integración**

```java
@SpringBootTest
@Testcontainers
class DisponibilidadMigrationIT {

	@Test
	void las_tres_tablas_llevan_organization_id_not_null() { }

	@Test
	void todos_los_indices_de_las_tres_tablas_empiezan_por_organization_id() { }

	@Test
	void el_check_rechaza_hora_hasta_menor_o_igual_que_hora_desde() { }

	@Test
	void el_check_acepta_hora_hasta_24_00_00() { }

	@Test
	void el_check_rechaza_dia_semana_fuera_de_1_a_7() { }

	@Test
	void el_check_de_baja_coherente_rechaza_active_0_sin_deleted_at() { }

	@Test
	void el_check_de_excepcion_rechaza_hora_desde_sin_hora_hasta() { }

	@Test
	void una_excepcion_de_sede_admite_membership_id_null() { }

	@Test
	void consultorio_calendario_es_unico_por_sede() { }
}
```

- [ ] **Paso 3: Correr y verificar**

```
./mvnw -Dtest=DisponibilidadMigrationIT test
```

Esperado: 9 en verde.

- [ ] **Paso 4: Commit**

```bash
git add src/main/resources/db/migration/V23__m05_disponibilidad_profesional.sql \
        src/test/java/com/akine/resource/infrastructure/DisponibilidadMigrationIT.java
git commit -m "feat: tablas de disponibilidad profesional, excepciones y calendario de sede (AKINE-02.04)"
```

---

## Tarea 3 — SPI de membership en `organization`

**Files:**
- Create: `src/main/java/com/akine/organization/spi/ConsultorioMembershipSnapshot.java`
- Create: `src/main/java/com/akine/organization/spi/MembershipDirectory.java`
- Create: `src/main/java/com/akine/organization/infrastructure/OrganizationMembershipDirectory.java`
- Test: `src/test/java/com/akine/organization/infrastructure/OrganizationMembershipDirectoryTest.java`

**Interfaces:**
- Produces: `MembershipDirectory.find(long, long) -> Optional<ConsultorioMembershipSnapshot>`.
  La consumen las Tareas 7 y 8.

- [ ] **Paso 1: Escribir el record y la interfaz**

```java
package com.akine.organization.spi;

import java.time.Instant;

/**
 * Vista de una membership para los modulos que necesitan saber DONDE y CUANDO vale.
 *
 * <p>Existe aparte de {@link MembershipSnapshot} y no como una ampliacion suya por un motivo
 * concreto: aquel record ya lo consume {@code identity}, y agregarle componentes rompe todos
 * sus constructores. Un record nuevo no rompe nada.
 *
 * @param consultorioId sede del vinculo, o {@code null} si el alcance es la organizacion
 *                      entera. {@code null} significa <b>alcance</b>, no dato faltante:
 *                      la misma convencion de {@code membership} (V10) y de
 *                      {@code colaborador_invitacion} (V21)
 */
public record ConsultorioMembershipSnapshot(
		long membershipId,
		long accountId,
		long organizationId,
		Long consultorioId,
		String roleCode,
		Instant validFrom,
		Instant validUntil,
		boolean active) {

	/** Vigencia Y baja logica, igual que {@link MembershipSnapshot#validAt(Instant)}. */
	public boolean validAt(Instant momento) {
		if (!active || momento.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || momento.isBefore(validUntil);
	}

	/** {@code true} si el vinculo habilita en esa sede: la propia, o alcance organizacion. */
	public boolean cubreConsultorio(long consultorioId) {
		return this.consultorioId == null || this.consultorioId == consultorioId;
	}
}
```

```java
package com.akine.organization.spi;

import java.util.Optional;

/**
 * Busqueda de una membership por id dentro de un tenant.
 *
 * <p>Filtra SIEMPRE por {@code organizationId}: una membership de otro tenant no resuelve, y
 * quien la pidio recibe 404, nunca 403.
 */
public interface MembershipDirectory {

	Optional<ConsultorioMembershipSnapshot> find(long organizationId, long membershipId);
}
```

- [ ] **Paso 2: Implementar en `organization/infrastructure`**

Adaptador sobre el repositorio de memberships que ya existe. Mapea la entidad al record.
Sigue la forma de `ResourceEspacioDirectory` / `ResourceCatalogoDirectory`.

- [ ] **Paso 3: Escribir los tests unitarios**

```java
class OrganizationMembershipDirectoryTest {

	@Test
	void no_resuelve_una_membership_de_otro_tenant() { }

	@Test
	void alcance_organizacion_cubre_cualquier_sede_del_tenant() { }

	@Test
	void alcance_consultorio_no_cubre_otra_sede() { }

	@Test
	void valid_at_es_false_despues_de_valid_until() { }

	@Test
	void valid_at_es_false_si_la_membership_esta_dada_de_baja() { }
}
```

- [ ] **Paso 4: Correr los tests y ArchUnit**

```
./mvnw -Dtest=OrganizationMembershipDirectoryTest,ModuleArchitectureTest test
```

Esperado: todo en verde. ArchUnit sin cambios: el SPI es aditivo y la dirección de la flecha
no cambia.

- [ ] **Paso 5: Commit**

```bash
git add src/main/java/com/akine/organization/spi/ConsultorioMembershipSnapshot.java \
        src/main/java/com/akine/organization/spi/MembershipDirectory.java \
        src/main/java/com/akine/organization/infrastructure/OrganizationMembershipDirectory.java \
        src/test/java/com/akine/organization/infrastructure/OrganizationMembershipDirectoryTest.java
git commit -m "feat: SPI de busqueda de membership por id con alcance de sede (AKINE-02.04)"
```

---

## Tarea 4 — Dominio: entidades y value objects

**Files:**
- Create: `src/main/java/com/akine/resource/domain/BloqueDisponibilidad.java`
- Create: `src/main/java/com/akine/resource/domain/DisponibilidadExcepcion.java`
- Create: `src/main/java/com/akine/resource/domain/Feriado.java`
- Create: `src/main/java/com/akine/resource/domain/CalendarioSede.java`
- Create: `src/main/java/com/akine/resource/domain/TipoExcepcion.java`
- Create: `src/main/java/com/akine/resource/domain/MotivoExcepcion.java`
- Create: `src/main/java/com/akine/resource/domain/OrigenFranja.java`
- Create: `src/main/java/com/akine/resource/domain/IntervaloLocal.java`
- Create: `src/main/java/com/akine/resource/domain/FranjaEfectiva.java`
- Test: `src/test/java/com/akine/resource/domain/IntervaloLocalTest.java`

**Interfaces:**
- Consumes: las tablas de las Tareas 1 y 2.
- Produces: `IntervaloLocal` con `solapaCon`, `union`, `restar`, `contiene`;
  `FranjaEfectiva(IntervaloLocal intervalo, OrigenFranja origen, OrigenFranja recortadoPor,
  Long reglaId)`. Los consume la Tarea 5.

- [ ] **Paso 1: Escribir `IntervaloLocal`**

Es el value object del que depende todo el cálculo. Sin Spring, sin JPA, sin fechas: solo
horas locales.

```java
package com.akine.resource.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Intervalo de horas locales dentro de un mismo dia. Extremo superior EXCLUSIVO.
 *
 * <h2>Por que la medianoche final es un caso especial</h2>
 *
 * <p>{@link LocalTime} no puede representar las 24:00: su maximo es 23:59:59.999999999. Pero
 * un bloque que va "hasta la medianoche" es completamente normal, y la base lo guarda como
 * {@code '24:00:00'}.
 *
 * <p>Adentro de esta clase, el fin de dia se representa con {@link #FIN_DE_DIA}, que es
 * {@code LocalTime.MAX}, y la conversion desde y hacia la base la hace el mapper. Comparar
 * {@code LocalTime.MAX} funciona porque es estrictamente mayor que cualquier hora real; lo
 * unico que no hay que hacer es sumarle nada.
 */
public record IntervaloLocal(LocalTime desde, LocalTime hasta) {

	public static final LocalTime FIN_DE_DIA = LocalTime.MAX;

	public IntervaloLocal {
		if (!hasta.isAfter(desde)) {
			throw new IllegalArgumentException(
					"Un intervalo termina despues de empezar: " + desde + " -> " + hasta);
		}
	}

	public boolean solapaCon(IntervaloLocal otro) {
		return desde.isBefore(otro.hasta) && otro.desde.isBefore(hasta);
	}

	public boolean contiene(IntervaloLocal otro) {
		return !desde.isAfter(otro.desde) && !hasta.isBefore(otro.hasta);
	}

	/**
	 * Resta {@code otro} de este intervalo. Devuelve 0, 1 o 2 intervalos.
	 *
	 * <p>Dos es el caso que importa y el que se olvida: un cierre en el medio de un bloque
	 * —almuerzo, reunion— parte la franja en dos y ambas mitades siguen siendo atencion.
	 */
	public List<IntervaloLocal> restar(IntervaloLocal otro) {
		if (!solapaCon(otro)) {
			return List.of(this);
		}
		List<IntervaloLocal> resto = new ArrayList<>(2);
		if (desde.isBefore(otro.desde)) {
			resto.add(new IntervaloLocal(desde, otro.desde));
		}
		if (otro.hasta.isBefore(hasta)) {
			resto.add(new IntervaloLocal(otro.hasta, hasta));
		}
		return resto;
	}

	/** Union solo si se tocan o se solapan; si no, no hay un unico intervalo que los cubra. */
	public boolean esContiguoCon(IntervaloLocal otro) {
		return solapaCon(otro) || hasta.equals(otro.desde) || otro.hasta.equals(desde);
	}
}
```

- [ ] **Paso 2: Escribir los enums y `FranjaEfectiva`**

```java
public enum TipoExcepcion { CIERRE, APERTURA }

public enum MotivoExcepcion { AUSENCIA, LICENCIA, FERIADO, BLOQUEO, AMPLIACION, OTRO }

/** Que regla produjo o recorto una franja. Es la mitad "explicable" del criterio de aceptacion. */
public enum OrigenFranja { BLOQUE, APERTURA, CIERRE, FERIADO }
```

```java
/**
 * Una franja de atencion ya resuelta, con la trazabilidad de como quedo asi.
 *
 * <p>{@code origen} y {@code recortadoPor} no son decoracion: el criterio de aceptacion de la
 * etapa pide que la disponibilidad efectiva <b>explique que regla la afecta</b>. Sin estos
 * dos campos el CA no se puede declarar cubierto, y la pantalla no puede decirle al admin por
 * que un martes quedo vacio.
 *
 * @param recortadoPor {@code null} si nada la recorto
 * @param reglaId      id de la fila que la produjo, para que la UI pueda linkearla
 */
public record FranjaEfectiva(
		IntervaloLocal intervalo,
		OrigenFranja origen,
		OrigenFranja recortadoPor,
		Long reglaId) {
}
```

- [ ] **Paso 3: Escribir las cuatro entidades JPA**

Siguen la forma de `Espacio`: `@Entity`, `@Version` para el bloqueo optimista, `@Column` con
nombres explícitos, y los mismos campos de baja lógica. El mapper de `hora_hasta` traduce
`'24:00:00'` ↔ `IntervaloLocal.FIN_DE_DIA` con un `AttributeConverter`.

- [ ] **Paso 4: Escribir los tests de `IntervaloLocal`**

```java
class IntervaloLocalTest {

	@Test
	void rechaza_un_intervalo_que_termina_antes_de_empezar() { }

	@Test
	void rechaza_un_intervalo_de_duracion_cero() { }

	@Test
	void dos_intervalos_que_se_tocan_en_un_extremo_no_solapan() {
		// 09:00-12:00 y 12:00-15:00 son contiguos, no solapados. El extremo superior es
		// exclusivo: si esto diera true, dos bloques legitimos de manana y tarde serian
		// rechazados como conflicto.
	}

	@Test
	void restar_un_cierre_del_medio_devuelve_dos_intervalos() {
		// 09:00-18:00 menos 13:00-14:00 -> [09:00-13:00, 14:00-18:00]
	}

	@Test
	void restar_un_cierre_que_tapa_todo_devuelve_vacio() { }

	@Test
	void restar_un_cierre_que_no_solapa_devuelve_el_original() { }

	@Test
	void restar_un_cierre_que_empieza_antes_recorta_solo_el_final() { }

	@Test
	void un_intervalo_hasta_fin_de_dia_es_valido() { }
}
```

- [ ] **Paso 5: Correr y commitear**

```
./mvnw -Dtest=IntervaloLocalTest test
```

```bash
git add src/main/java/com/akine/resource/domain/ src/test/java/com/akine/resource/domain/IntervaloLocalTest.java
git commit -m "feat: dominio de disponibilidad profesional y aritmetica de intervalos (AKINE-02.04)"
```

---

## Tarea 5 — El calculador de disponibilidad efectiva

> **Esta es la tarea que importa.** Todo lo demás es plomería alrededor. Un error acá se
> propaga silencioso a todo el motor de slots de F5. Es el único lugar del plan donde los
> tests son densos a propósito.

**Files:**
- Create: `src/main/java/com/akine/resource/domain/DisponibilidadEfectivaCalculator.java`
- Test: `src/test/java/com/akine/resource/domain/DisponibilidadEfectivaCalculatorTest.java`

**Interfaces:**
- Consumes: `IntervaloLocal`, `FranjaEfectiva`, `OrigenFranja` (Tarea 4).
- Produces:

```java
Map<LocalDate, List<FranjaEfectiva>> calcular(
        LocalDate desde, LocalDate hasta,          // hasta EXCLUSIVO
        List<BloqueDisponibilidad> bloques,
        List<DisponibilidadExcepcion> excepciones,
        Set<LocalDate> feriadosQueCierran)
```

La consume la Tarea 8 (`DisponibilidadEfectivaService`).

- [ ] **Paso 1: Implementar el calculador**

Lógica pura: sin Spring, sin repositorios, sin `Instant`. Recibe todo resuelto y devuelve el
mapa. Que sea pura es lo que la hace testeable de verdad.

El orden de las cuatro etapas por cada fecha, y **no es intercambiable**:

```java
/**
 * Resuelve la disponibilidad efectiva dia por dia.
 *
 * <h2>El orden de las etapas lo fija RN-M05-002</h2>
 *
 * <p>"Las excepciones prevalecen sobre el horario base". Por eso CIERRE se aplica ULTIMO y
 * puede recortar incluso lo que abrio una APERTURA. Invertir las dos ultimas etapas haria
 * que una apertura tape una ausencia, que es exactamente al reves de lo que la regla dice.
 *
 * <p>El resultado no depende del orden de insercion de las filas: para una misma entrada,
 * la salida es siempre la misma. Eso es la mitad "determinista" del criterio de aceptacion;
 * la otra mitad —"explica que regla la afecta"— son origen y recortadoPor de cada franja.
 *
 * <h2>Que NO hace esta clase</h2>
 *
 * <p>No convierte a Instant y no conoce el huso de la sede: trabaja en hora local de punta a
 * punta. La conversion la hace {@code DisponibilidadEfectivaService}, y esta separada a
 * proposito para que el horario de verano no se mezcle con la aritmetica de intervalos.
 */
```

Pseudocódigo de la iteración, por cada fecha `F` en `[desde, hasta)`:

```
1. BASE     = bloques con dia_semana == F.getDayOfWeek().getValue()
              y vigencia_desde <= F < (vigencia_hasta o infinito)
              -> FranjaEfectiva(intervalo, BLOQUE, null, bloque.id)

2. aperturas = excepciones TipoExcepcion.APERTURA que cubren F
               (fecha_desde <= F < fecha_hasta), de sede y de profesional
   ABIERTO  = BASE + aperturas
              -> FranjaEfectiva(intervalo, APERTURA, null, excepcion.id)
              donde hora_desde/hora_hasta null significa el dia entero

3. si F en feriadosQueCierran Y no hay ninguna apertura explicita para F:
        devolver lista vacia para F, y registrar la razon como FERIADO
   (una apertura explicita en un feriado es exactamente como un centro declara
    que ese dia atiende)

4. cierres  = excepciones TipoExcepcion.CIERRE que cubren F, de sede y de profesional
   por cada cierre, restar su intervalo de cada franja de ABIERTO,
   marcando recortadoPor = CIERRE en lo que sobrevive
```

- [ ] **Paso 2: Escribir los tests**

```java
class DisponibilidadEfectivaCalculatorTest {

	// --- horario base ---
	@Test void un_bloque_del_lunes_no_aparece_el_martes() { }
	@Test void un_bloque_cuya_vigencia_empieza_despues_no_aparece() { }
	@Test void un_bloque_cuya_vigencia_termino_no_aparece() { }
	@Test void vigencia_hasta_es_exclusivo() { }
	@Test void vigencia_hasta_null_significa_sin_fin() { }
	@Test void dos_bloques_el_mismo_dia_aparecen_los_dos() { }
	@Test void un_bloque_hasta_medianoche_llega_a_fin_de_dia() { }

	// --- cierres ---
	@Test void un_cierre_de_dia_completo_deja_el_dia_vacio() { }
	@Test void un_cierre_parcial_del_medio_parte_la_franja_en_dos() { }
	@Test void un_cierre_de_la_sede_afecta_a_todos_los_profesionales() { }
	@Test void un_cierre_del_profesional_no_afecta_a_otro() { }
	@Test void un_cierre_de_varios_dias_cubre_los_dias_del_medio() { }
	@Test void un_cierre_que_no_solapa_deja_la_franja_intacta() { }

	// --- aperturas ---
	@Test void una_apertura_agrega_una_franja_donde_no_habia_bloque() { }
	@Test void una_apertura_de_dia_completo_abre_el_dia_entero() { }

	// --- la regla que decide el orden (RN-M05-002) ---
	@Test void un_cierre_recorta_lo_que_abrio_una_apertura() {
		// El caso que fija el orden. Si esto falla, las etapas 2 y 4 estan invertidas.
	}

	// --- feriados ---
	@Test void un_feriado_que_cierra_deja_el_dia_vacio_aunque_haya_bloque() { }
	@Test void un_feriado_no_cierra_si_la_sede_no_cierra_por_feriado() { }
	@Test void una_apertura_explicita_hace_atender_en_un_feriado() { }

	// --- trazabilidad: la mitad "explicable" del CA ---
	@Test void cada_franja_declara_la_regla_que_la_produjo() { }
	@Test void una_franja_recortada_declara_que_la_recorto() { }
	@Test void un_dia_vaciado_por_feriado_lo_declara() { }

	// --- determinismo ---
	@Test void el_resultado_no_depende_del_orden_de_las_excepciones_de_entrada() { }

	// --- bordes ---
	@Test void una_ventana_de_un_solo_dia_devuelve_un_solo_dia() { }
	@Test void hasta_es_exclusivo_en_la_ventana() { }
	@Test void sin_bloques_ni_excepciones_devuelve_todos_los_dias_vacios() { }
}
```

- [ ] **Paso 3: Correr y verificar**

```
./mvnw -Dtest=DisponibilidadEfectivaCalculatorTest test
```

Esperado: 26 en verde.

- [ ] **Paso 4: Commit**

```bash
git add src/main/java/com/akine/resource/domain/DisponibilidadEfectivaCalculator.java \
        src/test/java/com/akine/resource/domain/DisponibilidadEfectivaCalculatorTest.java
git commit -m "feat: calculador determinista de disponibilidad efectiva con trazabilidad de reglas (AKINE-02.04)"
```

---

## Tarea 6 — Puertos y repositorios JPA

**Files:**
- Create: `src/main/java/com/akine/resource/domain/port/DisponibilidadRepositoryPorts.java`
- Create: `src/main/java/com/akine/resource/infrastructure/BloqueDisponibilidadRepository.java`
- Create: `src/main/java/com/akine/resource/infrastructure/DisponibilidadExcepcionRepository.java`
- Create: `src/main/java/com/akine/resource/infrastructure/FeriadoRepository.java`
- Create: `src/main/java/com/akine/resource/infrastructure/CalendarioSedeRepository.java`

**Interfaces:**
- Produces (los métodos que consumen las Tareas 7 y 8):

```java
// BloqueDisponibilidadRepositoryPort
List<BloqueDisponibilidad> findVigentesEn(Long organizationId, Long consultorioId,
        Long membershipId, LocalDate desde, LocalDate hasta);
List<BloqueDisponibilidad> findActivosDe(Long organizationId, Long consultorioId, Long membershipId);
Optional<BloqueDisponibilidad> findByIdScoped(Long id, Long organizationId, Long consultorioId);
BloqueDisponibilidad save(BloqueDisponibilidad bloque);

// DisponibilidadExcepcionRepositoryPort
List<DisponibilidadExcepcion> findQueCubren(Long organizationId, Long consultorioId,
        Long membershipId, LocalDate desde, LocalDate hasta);   // incluye las de sede
Optional<DisponibilidadExcepcion> findByIdScoped(Long id, Long organizationId, Long consultorioId);
DisponibilidadExcepcion save(DisponibilidadExcepcion excepcion);

// FeriadoRepositoryPort   — SIN organizationId: ADR-0022
List<Feriado> findByPaisAndFechaBetween(String pais, LocalDate desde, LocalDate hasta);

// CalendarioSedeRepositoryPort
Optional<CalendarioSede> findByScope(Long organizationId, Long consultorioId);
Optional<CalendarioSede> lockByScope(Long organizationId, Long consultorioId);  // FOR UPDATE
CalendarioSede save(CalendarioSede calendario);
```

- [ ] **Paso 1: Escribir los puertos en `domain/port`**

Un archivo con las cuatro interfaces, siguiendo la forma de `CatalogoRepositoryPorts`.

`FeriadoRepositoryPort` lleva este javadoc, porque es la única consulta del módulo sin tenant:

```java
/**
 * Acceso al calendario de feriados. <b>Sin {@code organizationId}, y no es un olvido:</b> la
 * tabla es global por ADR-0022. Si alguien agrega el parametro, entendio mal el alcance —
 * la decision por tenant vive en {@code CalendarioSede}, no aca.
 */
```

- [ ] **Paso 2: Escribir los repositorios JPA**

`lockByScope` va como **consulta nativa**, igual que `findByIdForUpdate` en
`EspacioRepository` y por el mismo motivo declarado ahí: la cláusula que decide la
correctitud tiene que estar a la vista de quien lee la consulta.

```java
	/**
	 * Lock exclusivo sobre la fila de calendario de la sede.
	 *
	 * <p>Es el lock que serializa TODOS los writes de disponibilidad de esa sede. No bloquea
	 * los bloques: bloquear filas que existen no impide que otra transaccion INSERTE una fila
	 * nueva en el hueco, que es justamente el caso que hay que evitar.
	 *
	 * <p>La fila siempre existe porque se crea a demanda antes de tomar el lock.
	 */
	@Query(value = """
			SELECT * FROM consultorio_calendario
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			 FOR UPDATE
			""", nativeQuery = true)
	Optional<CalendarioSede> lockByScope(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);
```

- [ ] **Paso 3: Correr ArchUnit**

```
./mvnw -Dtest=ModuleArchitectureTest test
```

Esperado: verde. Verifica que `application` no importe `infrastructure`.

- [ ] **Paso 4: Commit**

```bash
git add src/main/java/com/akine/resource/domain/port/ src/main/java/com/akine/resource/infrastructure/
git commit -m "feat: puertos y repositorios de disponibilidad, excepciones, feriados y calendario (AKINE-02.04)"
```

---

## Tarea 7 — `DisponibilidadService`: alta, edición y baja de bloques

**Files:**
- Create: `src/main/java/com/akine/resource/application/DisponibilidadService.java`
- Create: `src/main/java/com/akine/resource/application/BloqueAltaCommand.java`
- Create: `src/main/java/com/akine/resource/application/BloqueEdicionCommand.java`
- Create: `src/main/java/com/akine/resource/application/BloqueView.java`
- Create: `src/main/java/com/akine/resource/domain/exception/BloqueSolapadoException.java`
- Create: `src/main/java/com/akine/resource/domain/exception/BloqueNotAccessibleException.java`
- Create: `src/main/java/com/akine/resource/domain/exception/ProfesionalNoVinculadoException.java`
- Modify: `src/main/java/com/akine/resource/domain/PermissionCodes.java`
- Test: `src/test/java/com/akine/resource/application/DisponibilidadServiceTest.java`

**Interfaces:**
- Consumes: `MembershipDirectory` (Tarea 3), los puertos (Tarea 6), `ConsultorioDirectory`,
  `PermissionGuard`, `AuditTrail`.
- Produces: `crear`, `editar`, `dar de baja`, `listar` — los consume la Tarea 10.

- [ ] **Paso 1: Agregar el código de permiso de lectura**

`PermissionCodes` del módulo suma la constante de `colaborador:read`. **No se crea ningún
código nuevo**: los dos que usa esta etapa ya están en la matriz §5 y aprobados.

- [ ] **Paso 2: Implementar el servicio**

Cabecera con la misma forma que `EspacioService`. Tiene que dejar escritas estas tres cosas:

```java
/**
 * Administracion de la disponibilidad semanal de un profesional en una sede (M05).
 *
 * <h2>Las dos autorizaciones, y por que son distintas</h2>
 *
 * <p>Mutaciones: {@code consultorio:manage} con la sede como alcance. La matriz seccion 6 se
 * lo niega a {@code PROFESIONAL} y {@code ADMINISTRATIVO}, y de ahi sale —sin inventar ningun
 * codigo— la politica confirmada: el profesional NO edita su disponibilidad.
 *
 * <p>Lecturas: {@code colaborador:read}, que la matriz seccion 6 le da a {@code PROFESIONAL} y
 * {@code ADMINISTRATIVO} con alcance Consultorio. La disponibilidad de un profesional es
 * informacion de un colaborador, asi que el codigo aplica sin estirarlo. <b>No se usa
 * {@code espacio:read}</b>: ese cubre la disponibilidad del ESPACIO FISICO (M04), que es otra
 * cosa.
 *
 * <p><b>El orden de las comprobaciones no es cosmetico.</b> Primero pertenencia, despues
 * permiso: un tenant ajeno sale por 404 —un 403 confirmaria que existe— y el evaluador de
 * permisos responde 403.
 *
 * <h2>El lock, y por que no esta donde uno lo pondria</h2>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints, asi que el solapamiento se valida en
 * aplicacion. Un {@code SELECT ... FOR UPDATE} sobre los bloques existentes NO alcanza:
 * bloquear filas que existen no impide que otra transaccion inserte una fila nueva en el
 * hueco, y dos altas concurrentes terminan pisandose sin que ninguna vea a la otra.
 *
 * <p>Por eso el lock se toma sobre la fila de {@code consultorio_calendario} de la sede, que
 * siempre existe. Serializa los writes de disponibilidad por sede, que es aceptable: editar
 * horarios es una accion administrativa de baja frecuencia, no un camino caliente.
 *
 * <p><b>El lock se toma al PRINCIPIO de la transaccion, antes de leer ningun bloque.</b> Leer
 * primero en modo compartido y bloquear despues es una escalada S-&gt;X: con dos transacciones
 * en el mismo camino no es una espera, es un deadlock.
 *
 * <h2>Idempotencia (CA-M05-003-05)</h2>
 *
 * <p>Sin {@code Idempotency-Key}. Un alta que coincide EXACTO con un bloque activo existente
 * —misma membership, dia, horas y vigencia— devuelve ese bloque, no un duplicado y no un 409.
 * Un alta que solapa SIN coincidir devuelve 409. Un reintento de red cae siempre en el primer
 * caso, que es lo que el criterio pide.
 */
@Service
public class DisponibilidadService {
```

Firma de las operaciones:

```java
@Transactional
public BloqueView crear(OperatingActor actor, long consultorioId, long membershipId,
        BloqueAltaCommand command);

@Transactional
public BloqueView editar(OperatingActor actor, long consultorioId, long membershipId,
        long bloqueId, BloqueEdicionCommand command);

@Transactional
public void darDeBaja(OperatingActor actor, long consultorioId, long membershipId,
        long bloqueId, String motivo);

@Transactional(readOnly = true)
public List<BloqueView> listar(OperatingActor actor, long consultorioId, long membershipId);
```

Secuencia de `crear`, en este orden exacto:

```
1. resolver la sede (ConsultorioDirectory)      -> 404 si es de otro tenant
2. exigir consultorio:manage con alcance sede   -> 403
3. resolver la membership (MembershipDirectory) -> 404 si no es del tenant
   y verificar que cubra esta sede              -> ProfesionalNoVinculadoException
4. crear el calendario de la sede si no existe
5. lockByScope(...)   <-- ANTES de leer bloques
6. leer los bloques activos de esa membership en ese dia
7. si hay coincidencia exacta -> devolver ese bloque (idempotencia)
   si hay solapamiento        -> BloqueSolapadoException (409)
8. guardar
9. auditar EN LA MISMA TRANSACCION
```

- [ ] **Paso 3: Escribir los tests unitarios**

```java
class DisponibilidadServiceTest {

	@Test void una_sede_de_otro_tenant_da_404_y_no_403() { }
	@Test void sin_consultorio_manage_el_alta_da_403() { }
	@Test void un_profesional_no_puede_crear_su_propio_bloque() { }
	@Test void con_colaborador_read_puede_listar() { }
	@Test void una_membership_que_no_cubre_la_sede_es_rechazada() { }
	@Test void un_alta_exactamente_igual_devuelve_el_bloque_existente() { }
	@Test void un_alta_que_solapa_sin_coincidir_da_409() { }
	@Test void dos_bloques_contiguos_del_mismo_dia_son_validos() { }
	@Test void el_lock_se_toma_antes_de_leer_los_bloques() { }
	@Test void la_edicion_con_version_vieja_da_409_concurrent_modification() { }
	@Test void la_baja_exige_motivo() { }
	@Test void la_baja_es_logica_y_conserva_la_fila() { }
	@Test void la_auditoria_se_escribe_en_la_misma_transaccion() { }
}
```

- [ ] **Paso 4: Correr y commitear**

```
./mvnw -Dtest=DisponibilidadServiceTest test
```

```bash
git add src/main/java/com/akine/resource/application/ src/main/java/com/akine/resource/domain/exception/ \
        src/test/java/com/akine/resource/application/DisponibilidadServiceTest.java
git commit -m "feat: alta, edicion y baja de bloques de disponibilidad con deteccion de solapamiento (AKINE-02.04)"
```

---

## Tarea 8 — Excepciones, calendario y disponibilidad efectiva

**Files:**
- Create: `src/main/java/com/akine/resource/application/ExcepcionService.java`
- Create: `src/main/java/com/akine/resource/application/CalendarioService.java`
- Create: `src/main/java/com/akine/resource/application/DisponibilidadEfectivaService.java`
- Create: `src/main/java/com/akine/resource/application/ExcepcionAltaCommand.java`
- Create: `src/main/java/com/akine/resource/application/ExcepcionView.java`
- Create: `src/main/java/com/akine/resource/application/DisponibilidadEfectivaView.java`
- Create: `src/main/java/com/akine/resource/domain/exception/VentanaDemasiadoAmpliaException.java`
- Test: `src/test/java/com/akine/resource/application/DisponibilidadEfectivaServiceTest.java`

**Interfaces:**
- Consumes: `DisponibilidadEfectivaCalculator` (Tarea 5), los puertos (Tarea 6),
  `MembershipDirectory` (Tarea 3), `ConsultorioDirectory`.
- Produces:

```java
DisponibilidadEfectivaView efectiva(OperatingActor actor, long consultorioId,
        long membershipId, LocalDate desde, LocalDate hasta);

public record DisponibilidadEfectivaView(
        long membershipId, long consultorioId, String timezone,
        List<DiaEfectivo> dias) { }

public record DiaEfectivo(LocalDate fecha, boolean esFeriado, String feriadoNombre,
        List<FranjaResuelta> franjas) { }

/** Los Instant ya convertidos, mas la trazabilidad de la regla. */
public record FranjaResuelta(Instant desde, Instant hasta,
        String origen, String recortadoPor, Long reglaId) { }
```

Los consume la Tarea 10.

- [ ] **Paso 1: Implementar `DisponibilidadEfectivaService`**

Es el único lugar del módulo que conoce el huso. Secuencia:

```
1. sede + timezone (ConsultorioDirectory; timezone es NOT NULL desde V18)
2. validar la ventana: hasta > desde, y (hasta - desde) <= VENTANA_MAXIMA_DIAS
3. membership: si no esta vigente en la ventana -> disponibilidad vacia, NO es error
4. cargar bloques, excepciones y feriados de la ventana
5. feriadosQueCierran = feriados de la ventana SI calendario.cierraPorFeriado
6. calculator.calcular(...)
7. convertir cada IntervaloLocal a Instant con ZoneId(timezone)
```

El tope de ventana con su motivo escrito:

```java
	/**
	 * Tope de la ventana consultable.
	 *
	 * <p>Sin tope, un {@code desde=1970} sobre un tenant grande es un scan completo y un
	 * problema de disponibilidad del servicio, no una consulta. Es el mismo razonamiento que
	 * la matriz de permisos aplica a los listados con rango.
	 */
	private static final int VENTANA_MAXIMA_DIAS = 366;
```

La conversión local → `Instant`, con los dos casos de DST resueltos y explicados:

```java
	/**
	 * Convierte una hora local de la sede al instante UTC correspondiente.
	 *
	 * <p><b>Nunca usar {@code LocalDateTime.toInstant(ZoneOffset)} con un offset fijo.</b> Eso
	 * funciona hasta el primer cambio de horario de verano y despues devuelve instantes
	 * corridos una hora, sin fallar. Argentina hoy no aplica DST, pero
	 * {@code consultorio.timezone} admite cualquier huso.
	 *
	 * <p>Los dos casos que {@link ZonedDateTime#of} resuelve, y como:
	 * <ul>
	 *   <li><b>Hueco (adelanto):</b> la hora local no existe. Se corre hacia adelante la
	 *       duracion del salto.</li>
	 *   <li><b>Solapamiento (atraso):</b> la hora local ocurre dos veces. Se toma el
	 *       <b>primer</b> offset, que es el criterio por defecto y el que hace que la manana
	 *       no se duplique.</li>
	 * </ul>
	 */
	private Instant aInstante(LocalDate fecha, LocalTime hora, ZoneId zona) {
		return ZonedDateTime.of(fecha, hora, zona).toInstant();
	}
```

- [ ] **Paso 2: Implementar `ExcepcionService` y `CalendarioService`**

`ExcepcionService`: alta, listado y baja lógica. Misma autorización que la Tarea 7 —
`consultorio:manage` para mutar, `colaborador:read` para leer. Una excepción con
`membershipId == null` es de sede y afecta a todos.

`CalendarioService`: leer y editar `cierraPorFeriado`, listar los feriados de la ventana,
y crear la fila de calendario a demanda con los valores por defecto.

- [ ] **Paso 3: Escribir los tests**

```java
class DisponibilidadEfectivaServiceTest {

	@Test void una_ventana_mayor_al_tope_da_400() { }
	@Test void hasta_menor_o_igual_que_desde_da_400() { }
	@Test void una_membership_desvinculada_devuelve_vacio_y_no_error() { }
	@Test void una_membership_desvinculada_no_borra_sus_bloques() { }
	@Test void las_horas_locales_se_convierten_con_el_huso_de_la_sede() { }
	@Test void un_huso_con_dst_resuelve_el_hueco_corriendo_hacia_adelante() { }
	@Test void un_huso_con_dst_resuelve_el_solapamiento_con_el_primer_offset() { }
	@Test void un_feriado_no_cierra_si_la_sede_tiene_cierra_por_feriado_false() { }
	@Test void la_respuesta_incluye_el_nombre_del_feriado_del_dia() { }
	@Test void cada_franja_viaja_con_su_origen_y_su_regla() { }
}
```

- [ ] **Paso 4: Correr y commitear**

```
./mvnw -Dtest=DisponibilidadEfectivaServiceTest test
```

```bash
git add src/main/java/com/akine/resource/application/
git add src/test/java/com/akine/resource/application/DisponibilidadEfectivaServiceTest.java
git commit -m "feat: disponibilidad efectiva con huso de la sede, excepciones y calendario (AKINE-02.04)"
```

---

## Tarea 9 — Las dos costuras hacia F5

**Files:**
- Create: `src/main/java/com/akine/resource/spi/DisponibilidadImpactProbe.java`
- Create: `src/main/java/com/akine/resource/infrastructure/ResourceDesvinculacionProbe.java`
- Modify: `docs/tests-diferidos.md`
- Test: `src/test/java/com/akine/resource/infrastructure/ResourceDesvinculacionProbeTest.java`

**Interfaces:**
- Produces: `DisponibilidadImpactProbe.turnosEn(...)` — lo implementará `scheduling` en F5.
- Consumes: `ColaboradorDesvinculacionProbe` de `organization.spi`, que ya existe.

- [ ] **Paso 1: Declarar la costura**

```java
package com.akine.resource.spi;

/**
 * Turnos futuros afectados por un cambio de disponibilidad (RF-M05-005, RN-M05-004).
 *
 * <h2>Que responde hoy, y que NO — dicho antes de que alguien lo asuma</h2>
 *
 * <p><b>Hoy devuelve siempre cero, y no es un bug.</b> Los turnos son del modulo
 * {@code scheduling} (F5, M12), que no existe. La forma de sumar esa mitad esta declarada
 * aca y no cambia el contrato: cuando haya una implementacion, {@code turnosAfectados} deja
 * de ser cero y la pantalla de edicion empieza a mostrar el conflicto.
 *
 * <p>Es exactamente el mismo patron que {@code EspacioOccupancyProbe} en 02.02, y por el
 * mismo motivo: una pantalla que interprete "cero conflictos" como "se puede cambiar sin
 * consecuencias" va a dejar turnos huerfanos en cuanto exista la agenda, y el bug no va a
 * parecer de esta etapa.
 */
public interface DisponibilidadImpactProbe {

	record Impacto(long turnosAfectados, Instant primero) {
		public static Impacto ninguno() { return new Impacto(0L, null); }
		public boolean hayAlgo() { return turnosAfectados > 0; }
	}

	Impacto turnosEn(long organizationId, long consultorioId, long membershipId,
			Instant desde, Instant hasta);
}
```

- [ ] **Paso 2: Implementar `ColaboradorDesvinculacionProbe` desde `resource`**

Cuenta los bloques activos y las excepciones futuras de esa membership y los devuelve como
`Impacto`, para que la pantalla de desvinculación muestre qué queda colgando.

- [ ] **Paso 3: Anotar la deuda en `docs/tests-diferidos.md`**

Entrada nueva, con la forma que ya usa el archivo:

> **RN-M05-004 — los turnos futuros afectados quedan visibles para resolución.**
> Origen: AKINE-02.04. Destino: **F5** (`scheduling`, M12).
> No se puede ejecutar: no existe ningún turno que pueda estar afectado. La costura está
> declarada (`resource.spi.DisponibilidadImpactProbe`) y hoy devuelve cero por construcción.
> Al implementarla, este escenario se ejecuta sin cambiar el contrato.

- [ ] **Paso 4: Correr y commitear**

```
./mvnw -Dtest=ResourceDesvinculacionProbeTest test
```

```bash
git add src/main/java/com/akine/resource/spi/DisponibilidadImpactProbe.java \
        src/main/java/com/akine/resource/infrastructure/ResourceDesvinculacionProbe.java \
        docs/tests-diferidos.md \
        src/test/java/com/akine/resource/infrastructure/ResourceDesvinculacionProbeTest.java
git commit -m "feat: costura de impacto en turnos y aviso de disponibilidad al desvincular (AKINE-02.04)"
```

---

## Tarea 10 — API: controllers, DTOs y advice

**Files:**
- Create: `src/main/java/com/akine/resource/api/DisponibilidadController.java`
- Create: `src/main/java/com/akine/resource/api/ExcepcionController.java`
- Create: `src/main/java/com/akine/resource/api/CalendarioController.java`
- Create: `src/main/java/com/akine/resource/api/DisponibilidadProblemHandler.java`
- Create: `src/main/java/com/akine/resource/api/dto/` (9 DTOs)
- Test: `src/test/java/com/akine/resource/api/DisponibilidadControllerTest.java`

**Interfaces:**
- Consumes: los servicios de las Tareas 7 y 8.
- Produces: los 11 endpoints de §6 del diseño.

- [ ] **Paso 1: Escribir los DTOs**

`CreateBloqueRequest`, `UpdateBloqueRequest`, `DeactivateBloqueRequest`, `BloqueResponse`,
`CreateExcepcionRequest`, `ExcepcionResponse`, `DisponibilidadEfectivaResponse`,
`CalendarioSedeResponse`, `UpdateCalendarioRequest`, `FeriadoResponse`.

Con `@Schema` en cada campo, como los DTOs de 02.02 y 02.05.
`DisponibilidadEfectivaResponse` lleva un javadoc de clase que dice **explícitamente** que
`franja.origen` es la explicación de la regla y que ese campo es el criterio de aceptación de
la etapa, no un extra.

- [ ] **Paso 2: Escribir los tres controllers**

Los 11 endpoints de §6, con `@Operation` y las respuestas documentadas: 200, 201, 400, 403,
404, 409. `DELETE` recibe motivo obligatorio en el body.

- [ ] **Paso 3: Escribir el advice del módulo**

`DisponibilidadProblemHandler` mapea las excepciones de dominio a Problem Details:

| Excepción | HTTP | `type` |
|---|---|---|
| `BloqueSolapadoException` | 409 | `bloque-solapado` |
| `BloqueNotAccessibleException` | 404 | `not-found` |
| `ProfesionalNoVinculadoException` | 409 | `profesional-no-vinculado` |
| `VentanaDemasiadoAmpliaException` | 400 | `ventana-demasiado-amplia` |
| `OptimisticLockingFailureException` | 409 | `concurrent-modification` |

**En el advice del módulo, nunca en `GlobalExceptionHandler`**: meterlo ahí crea el ciclo
`platform.api → resource.domain` que ArchUnit rechaza.

- [ ] **Paso 4: Escribir los tests de slice web**

```java
@WebMvcTest(DisponibilidadController.class)
class DisponibilidadControllerTest {

	@Test void el_alta_devuelve_201_con_location() { }
	@Test void un_alta_idempotente_devuelve_200_y_no_201() { }
	@Test void un_solapamiento_devuelve_409_como_problem_detail() { }
	@Test void una_sede_ajena_devuelve_404_y_no_403() { }
	@Test void sin_contexto_devuelve_403_y_no_401() { }
	@Test void una_ventana_sin_desde_ni_hasta_devuelve_400() { }
	@Test void la_efectiva_incluye_origen_en_cada_franja() { }
	@Test void la_baja_sin_motivo_devuelve_400() { }
}
```

> **Spring Boot 4:** `@WebMvcTest` vive en `org.springframework.boot.webmvc.test.autoconfigure`.

- [ ] **Paso 5: Correr y commitear**

```
./mvnw -Dtest=DisponibilidadControllerTest test
```

```bash
git add src/main/java/com/akine/resource/api/ src/test/java/com/akine/resource/api/
git commit -m "feat: API de disponibilidad, excepciones y calendario de sede (AKINE-02.04)"
```

---

## Tarea 11 — Contrato OpenAPI 0.11.0

**Files:**
- Modify: `openapi/akine-api.yaml`
- Test: el contract test que ya existe (verifica que no haya drift)

- [ ] **Paso 1: Bump de versión**

`version: 0.10.0` → `0.11.0`. **Bump menor**: el cambio es puramente aditivo, ningún endpoint
existente cambia de forma. Sin ventana de compatibilidad.

- [ ] **Paso 2: Agregar los 11 paths y sus schemas**

Los de §6 del diseño, con los mismos nombres de campo que los DTOs de la Tarea 10.

- [ ] **Paso 3: Correr el contract test**

```
./mvnw -Dtest='*Contract*' test
```

Esperado: verde, **cero drift** entre el YAML y los mappings del código. Si falla, el YAML y
los controllers discrepan: se arregla el YAML, nunca se afloja el test.

- [ ] **Paso 4: Commit**

```bash
git add openapi/akine-api.yaml
git commit -m "feat: publicar contrato 0.11.0 con la superficie de disponibilidad (AKINE-02.04)"
```

---

## Tarea 12 — Integración contra MySQL real

**Files:**
- Create: `src/test/java/com/akine/resource/DisponibilidadIT.java`

- [ ] **Paso 1: Escribir los escenarios**

```java
@SpringBootTest
@Testcontainers
class DisponibilidadIT {

	@Test
	void dos_altas_concurrentes_solapadas_no_pasan_las_dos() {
		// EL test de esta tarea. Dos hilos, misma membership, mismo dia, horarios que se
		// pisan, barrera para que arranquen juntos. Uno guarda, el otro recibe 409.
		//
		// Si este test pasa con el lock comentado, el lock esta en el lugar equivocado y el
		// test no esta probando lo que dice probar.
	}

	@Test void un_bloque_de_otro_tenant_no_resuelve_ni_siquiera_con_el_id_correcto() { }
	@Test void la_baja_logica_conserva_la_fila_y_su_historia() { }
	@Test void desvincular_no_borra_la_disponibilidad_y_la_efectiva_queda_vacia() { }
	@Test void el_feriado_y_la_politica_de_la_sede_se_combinan_como_dice_el_diseno() { }
	@Test void la_auditoria_queda_escrita_en_la_misma_transaccion_del_alta() { }
}
```

- [ ] **Paso 2: Correr la suite completa y los gates**

```
./mvnw verify
```

Esperado: toda la suite en verde (1379+ unitarias, 94+ integración) y **cobertura ≥ 80 %**
de instrucción y de rama.

- [ ] **Paso 3: `/simplify`**

Obligatorio por `CLAUDE.md` §2. Reuso, simplificación, eficiencia. No busca bugs.

- [ ] **Paso 4: `/code-review`**

Sin hallazgos abiertos antes de seguir.

- [ ] **Paso 5: Commit**

```bash
git add src/test/java/com/akine/resource/DisponibilidadIT.java
git commit -m "test: integracion de disponibilidad contra MySQL real, con solapamiento concurrente (AKINE-02.04)"
```

---

## Tarea 13 — Frontend: regenerar el cliente en 0.11.0

**Repo: `appKine-web`. Misma rama que el backend (`akine-01.02-identidad`).**

**Files:**
- Modify: la configuración del generador y el cliente generado.

- [ ] **Paso 1: Fijar la versión y regenerar**

Contra el contrato **0.11.0** publicado por la Tarea 11. **Prohibidos los DTO manuales
duplicados.**

- [ ] **Paso 2: Verificar que compile y que la suite siga verde**

```
npm run build && npm test
```

- [ ] **Paso 3: Commit**

```bash
git add .
git commit -m "chore: regenerar el cliente contra el contrato 0.11.0"
```

---

## Tarea 14 — Frontend: editor semanal

**Files:**
- Create: `src/app/features/resource/disponibilidad/disponibilidad.routes.ts`
- Create: `src/app/features/resource/disponibilidad/data/disponibilidad.store.ts`
- Create: `src/app/features/resource/disponibilidad/editor-semanal/editor-semanal.component.ts`
- Test: `editor-semanal.component.spec.ts`

- [ ] **Paso 1: Store y componente**

Grilla de 7 días con los bloques de cada día y su vigencia. Alta, edición y baja contra los
endpoints de bloques. El 409 de solapamiento se muestra **señalando los dos bloques que
chocan**, no como un toast genérico: el admin tiene que poder ver qué corregir.

- [ ] **Paso 2: Tests unitarios**

```
un_409_de_solapamiento_muestra_los_bloques_en_conflicto
la_baja_pide_motivo_antes_de_enviar
un_bloque_hasta_medianoche_se_muestra_como_24_00
```

Con `HttpTestingController`, como el resto de las features.

- [ ] **Paso 3: Correr, auditar accesibilidad y commitear**

```
npm test
```

Auditoría axe sobre el componente, como en 02.02 y 02.05.

```bash
git add src/app/features/resource/disponibilidad/
git commit -m "feat: editor semanal de disponibilidad profesional (AKINE-02.04)"
```

---

## Tarea 15 — Frontend: panel de excepciones y calendario de la sede

**Files:**
- Create: `.../excepciones/excepciones-panel.component.ts`
- Create: `.../calendario/calendario-sede.component.ts`
- Test: los dos `.spec.ts`

- [ ] **Paso 1: Panel de excepciones**

Alta de cierre y de apertura, rango de fechas, día completo o franja, alcance sede o
profesional. Listado de las vigentes con su motivo.

- [ ] **Paso 2: Calendario de la sede**

Toggle de `cierraPorFeriado` y listado de los feriados de la ventana. Al apagarlo, decir en
la pantalla qué implica: **la sede pasa a atender los feriados salvo cierre explícito**.

- [ ] **Paso 3: Tests, axe y commit**

```bash
git commit -m "feat: excepciones de disponibilidad y politica de feriados de la sede (AKINE-02.04)"
```

---

## Tarea 16 — Frontend: preview de disponibilidad efectiva

**Files:**
- Create: `.../efectiva/preview-efectiva.component.ts`
- Test: `preview-efectiva.component.spec.ts`

> **Esta pantalla es el criterio de aceptación hecho visible.** Sin ella, "la disponibilidad
> efectiva explica qué regla la afecta" queda en el JSON y nadie lo ve.

- [ ] **Paso 1: Componente**

Una semana resuelta. Cada franja muestra **qué regla la produjo** y, si fue recortada, **cuál
la recortó**. Un día vacío por feriado dice el nombre del feriado, no queda en blanco.

- [ ] **Paso 2: Tests**

```
cada_franja_muestra_la_regla_que_la_produjo
una_franja_recortada_muestra_que_la_recorto
un_dia_vaciado_por_feriado_muestra_el_nombre_del_feriado
un_dia_sin_disponibilidad_explica_por_que
```

- [ ] **Paso 3: Commit**

```bash
git commit -m "feat: preview de disponibilidad efectiva con la regla aplicada (AKINE-02.04)"
```

---

## Tarea 17 — Frontend: modo lectura y rutas

**Files:**
- Modify: `disponibilidad.routes.ts`
- Modify: los cuatro componentes
- Test: `disponibilidad-lectura.spec.ts`

- [ ] **Paso 1: Modo lectura**

Con `colaborador:read` pero sin `consultorio:manage`, la misma pantalla **sin acciones**. La
autorización real la aplica el backend; el frontend solo evita ofrecer botones que van a dar
403.

- [ ] **Paso 2: Rutas y guard**

Montadas bajo el prefijo que ya usan las features de `resource`.

> **La trampa de los enlaces rotos de 01.02–02.03 fue exactamente esto:** el backend emitía
> un prefijo y el frontend montaba otro, y el usuario caía en el 404 del comodín. Verificar el
> prefijo, y fijarlo con un test.

- [ ] **Paso 3: Tests y commit**

```bash
git commit -m "feat: modo lectura de disponibilidad para el rol PROFESIONAL (AKINE-02.04)"
```

---

## Tarea 18 — E2E y cierre de etapa

**Files:**
- Create: `e2e/disponibilidad.spec.ts`
- Modify: `../docs/AKINE_IMPLEMENTATION_PLAN.md` (registro de cierre)
- Modify: `../CLAUDE.md` (estado del workspace)
- Modify: `CLAUDE.md` §7 del backend — **hoy dice que el próximo paso es 01.03, y está mal**

- [ ] **Paso 1: E2E contra el stack real**

Un escenario: cargar disponibilidad, meter un cierre que la recorta, ver el preview
explicando la regla, e intentar un bloque solapado y ver el 409.

```
cd appKine-api && docker compose up -d
cd appKine-api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd appKine-web && npm start
```

- [ ] **Paso 2: QA manual contra la base**

Bloqueante por `CLAUDE.md` §6. Reglas innegociables:

- Validar la persistencia **contra la DB directamente** después de cada write. Un 200 no
  prueba que el dato quedó bien guardado.
- Verificar aislamiento: un token del tenant B no ve la disponibilidad del tenant A.
- **No maquillar la DB** para forzar estados. Si un flujo no llega al estado esperado, se
  registra como hallazgo.
- Si el frontend tiene Local API Switch, **revertirlo siempre** al terminar.

- [ ] **Paso 3: `verification-before-completion`**

Evidencia real antes de decir "listo": salida de `./mvnw verify`, salida de `npm test`, y el
resultado del QA manual. Nada de afirmaciones sin output.

- [ ] **Paso 4: Escribir el registro de cierre**

En la sección final de `docs/AKINE_IMPLEMENTATION_PLAN.md`, con la forma de los anteriores.
**Tiene que decir explícitamente** qué quedó sin cubrir:

- RN-M05-004 (turnos futuros al desvincular) → F5, ya anotado en `docs/tests-diferidos.md`.
- `DisponibilidadImpactProbe` sin implementación real → F5.
- El seed de feriados envejece: los trasladables se fijan por decreto cada año.

> **02.02 se commiteó sin registro de cierre y nadie puede reconstruir sus decisiones.** No
> repetir eso acá.

- [ ] **Paso 5: Actualizar los dos `CLAUDE.md`**

El del workspace: 02.04 cerrada, próximo paso **02.06**. El del backend: §7 sigue diciendo que
el próximo paso es 01.03, lo cual dejó de ser cierto hace cinco etapas.

- [ ] **Paso 6: Commit final**

```bash
git commit -m "docs: registro de cierre de AKINE-02.04 y estado actualizado"
```

---

## Autorrevisión del plan

**Cobertura de la spec.** Las once secciones del diseño tienen tarea:
§2.1 → T1 · §2.2–2.4 → T2 · §3 SPI organization → T3 · §3 SPI resource → T9 ·
§2.3–2.4 dominio → T4 · §4 algoritmo → T5 · §4 husos → T8 · §5 concurrencia → T7 ·
§5 idempotencia → T7 · §6 contrato → T10, T11 · §7 frontend → T14–T17 ·
§8 tests → T5, T12, T18 · §9 design challenge → T2, T3, T6 · §10 pendientes → T9, T18 ·
§11 nomenclatura → T4.

**Sin placeholders.** Ningún "TBD", ningún "similar a la tarea N", ningún "agregar validación
apropiada". Los dos lugares donde el plan dice "completar" son el seed de feriados (T1) y los
DTOs (T10), y en ambos casos está enumerado exactamente qué.

**Consistencia de tipos.** `IntervaloLocal`, `FranjaEfectiva`, `OrigenFranja` se definen en T4
y se usan con esos nombres en T5, T8 y T10. `ConsultorioMembershipSnapshot` y
`MembershipDirectory` se definen en T3 y se consumen en T7 y T8. `lockByScope` se declara en
T6 y se usa en T7. `DisponibilidadImpactProbe.Impacto` se define en T9 y no se consume en esta
etapa a propósito.

**Un hueco que el plan no cierra y hay que decirlo:** el seed de feriados de T1 tiene que
verificarse contra el calendario oficial antes de escribirse. El plan no puede traer esas
fechas garantizadas, y un seed con fechas inventadas cierra centros el día equivocado.
