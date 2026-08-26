package com.akine.resource.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.akine.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V23 contra MySQL real (AKINE-02.04, tarea 2): {@code consultorio_calendario},
 * {@code profesional_disponibilidad} y {@code disponibilidad_excepcion}.
 *
 * <p>Igual que {@link FeriadoMigrationIT}, estas tres tablas todavia no tienen servicio,
 * controller ni permiso propio: eso llega en tareas posteriores de la etapa. Lo unico que
 * existe hoy es el ESQUEMA, y se verifica insertando directo contra una base real.
 *
 * <p>El test de indices sigue el RULING DEL CONTROLADOR R3 del brief: la PRIMARY KEY es
 * {@code (id)} en las tres tablas y queda EXCLUIDA de la verificacion. Lo que se comprueba es
 * que todo UNIQUE y todo INDEX secundario empiece por {@code organization_id}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DisponibilidadMigrationIT {

	private static final List<String> TABLAS = List.of(
			"consultorio_calendario", "profesional_disponibilidad", "disponibilidad_excepcion");

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	// =================================================================================
	// organization_id NOT NULL y alcance tenant de indices/uniques
	// =================================================================================

	@Test
	@DisplayName("las tres tablas llevan organization_id NOT NULL")
	void las_tres_tablas_llevan_organization_id_not_null() {
		for (String tabla : TABLAS) {
			String esNullable = jdbc().queryForObject("""
					SELECT is_nullable FROM information_schema.columns
					 WHERE table_schema = DATABASE()
					   AND table_name = ?
					   AND column_name = 'organization_id'
					""", String.class, tabla);

			assertThat(esNullable)
					.as("%s.organization_id tiene que ser NOT NULL: toda tabla de negocio lo "
							+ "lleva (AGENT.md seccion 5)", tabla)
					.isEqualTo("NO");
		}
	}

	@Test
	@DisplayName("todos los indices secundarios y uniques de las tres tablas empiezan por organization_id")
	void todos_los_indices_de_las_tres_tablas_empiezan_por_organization_id() {
		for (String tabla : TABLAS) {
			// RULING R3, cerrado en la tarea 12: la PRIMARY KEY es (id) y queda excluida a
			// proposito. Los indices de soporte de FK tambien, pero ya NO por el prefijo `fk_`
			// de su nombre.
			//
			// Por que cambio: InnoDB crea solo un indice por cada FK cuya columna referenciada
			// no sea la mas a la izquierda de ningun otro indice, y lo nombra como la constraint.
			// Filtrar por `NOT LIKE 'fk\_%'` funcionaba, pero se apoyaba en la CONVENCION DE
			// NOMBRES de V1: el dia que alguien declare un indice diseñado llamado `fk_algo`, o
			// que una FK se llame de otra forma, el filtro empieza a tapar o a destapar cosas en
			// silencio — y este test existe justamente para que un indice sin alcance de tenant
			// no pase inadvertido.
			//
			// Ahora se pregunta por lo que el indice ES y no por como se llama: se excluyen los
			// que coinciden con una FOREIGN KEY DECLARADA en table_constraints. Es un hecho del
			// catalogo, no un acuerdo de nomenclatura. Lo que queda bajo verificacion son los
			// indices y uniques DISEÑADOS por la aplicacion.
			List<Map<String, Object>> primeraColumnaPorIndice = jdbc().queryForList("""
					SELECT s.index_name, s.column_name
					  FROM information_schema.statistics s
					 WHERE s.table_schema = DATABASE()
					   AND s.table_name = ?
					   AND s.index_name <> 'PRIMARY'
					   AND s.seq_in_index = 1
					   AND NOT EXISTS (
					       SELECT 1 FROM information_schema.table_constraints tc
					        WHERE tc.table_schema = s.table_schema
					          AND tc.table_name = s.table_name
					          AND tc.constraint_name = s.index_name
					          AND tc.constraint_type = 'FOREIGN KEY')
					""", tabla);

			assertThat(primeraColumnaPorIndice)
					.as("%s tiene que tener al menos un indice o unique diseñado ademas de la PK",
							tabla)
					.isNotEmpty();

			for (Map<String, Object> fila : primeraColumnaPorIndice) {
				assertThat(fila.get("column_name"))
						.as("%s.%s no empieza por organization_id: un unique o indice sin la "
								+ "columna de tenant es un bug de aislamiento (AGENT.md seccion 5)",
								tabla, fila.get("index_name"))
						.isEqualTo("organization_id");
			}
		}
	}

	// =================================================================================
	// hora_hasta: exclusiva, admite 24:00:00, no cruza medianoche
	// =================================================================================

	@Test
	@DisplayName("el CHECK rechaza hora_hasta menor o igual que hora_desde")
	void el_check_rechaza_hora_hasta_menor_o_igual_que_hora_desde() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> insertarBloque(fixture, 1, "10:00:00", "09:00:00"))
				.as("hora_hasta <= hora_desde tiene que violar ck_profesional_disponibilidad_horario")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_profesional_disponibilidad_horario");

		// El borde de la igualdad, que es lo que el nombre del test promete y hasta la tarea 12
		// no verificaba. No es un caso de laboratorio: hora_hasta es EXCLUSIVA, asi que un bloque
		// de 09:00 a 09:00 es una franja de duracion cero. Si el CHECK dijera ">=" en vez de ">",
		// esa fila entraria, no ofreceria ni un turno y no romperia nada — un horario cargado que
		// simplemente no existe.
		assertThatThrownBy(() -> insertarBloque(fixture, 1, "09:00:00", "09:00:00"))
				.as("hora_hasta == hora_desde es una franja vacia y tambien tiene que violar "
						+ "ck_profesional_disponibilidad_horario")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_profesional_disponibilidad_horario");
	}

	@Test
	@DisplayName("el CHECK acepta hora_hasta 24:00:00")
	void el_check_acepta_hora_hasta_24_00_00() {
		Fixture fixture = crearFixture();

		// No debe lanzar: 24:00:00 es el fin de un bloque nocturno cargado como dos filas
		// (ver la cabecera de V23), y MySQL lo admite en una columna TIME.
		insertarBloque(fixture, 1, "22:00:00", "24:00:00");

		Long total = jdbc().queryForObject("""
				SELECT COUNT(*) FROM profesional_disponibilidad
				 WHERE organization_id = ? AND membership_id = ? AND hora_hasta = '24:00:00'
				""", Long.class, fixture.organizationId(), fixture.membershipId());
		assertThat(total).isEqualTo(1L);
	}

	// =================================================================================
	// dia_semana: ISO-8601, 1 a 7
	// =================================================================================

	@Test
	@DisplayName("el CHECK rechaza dia_semana fuera de 1 a 7")
	void el_check_rechaza_dia_semana_fuera_de_1_a_7() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> insertarBloque(fixture, 8, "09:00:00", "10:00:00"))
				.as("dia_semana = 8 tiene que violar ck_profesional_disponibilidad_dia_semana")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_profesional_disponibilidad_dia_semana");

		assertThatThrownBy(() -> insertarBloque(fixture, 0, "09:00:00", "10:00:00"))
				.as("dia_semana = 0 tampoco es un ISO-8601 valido")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_profesional_disponibilidad_dia_semana");
	}

	// =================================================================================
	// Baja logica coherente
	// =================================================================================

	@Test
	@DisplayName("el CHECK de baja coherente rechaza active = 0 sin deleted_at")
	void el_check_de_baja_coherente_rechaza_active_0_sin_deleted_at() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO profesional_disponibilidad
				    (organization_id, consultorio_id, membership_id, dia_semana, hora_desde,
				     hora_hasta, vigencia_desde, active, deleted_at, deactivation_reason,
				     version, created_at, updated_at)
				VALUES (?, ?, ?, 1, '09:00:00', '10:00:00', CURRENT_DATE(), 0, NULL, NULL,
				        0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.membershipId()))
				.as("active = 0 con deleted_at NULL tiene que violar "
						+ "ck_profesional_disponibilidad_baja_coherente")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_profesional_disponibilidad_baja_coherente");
	}

	/**
	 * El gemelo de {@code ck_profesional_disponibilidad_baja_coherente} sobre la otra tabla.
	 *
	 * <p>Existia desde V23 y <b>ningun test lo ejercitaba</b>: la tarea 2 cubrio el CHECK de los
	 * bloques y dio por hecho el de las excepciones porque es el mismo patron. Es justo el tipo de
	 * constraint que se cae en una migracion futura sin que nadie lo note, y lo que protege es que
	 * {@code active}, {@code deleted_at} y {@code deactivation_reason} se muevan juntos: una
	 * excepcion con {@code active = 0} y sin motivo es una baja que no responde por que.
	 */
	@Test
	@DisplayName("el CHECK de baja coherente de la excepcion rechaza active = 0 sin deleted_at")
	void el_check_de_baja_coherente_de_la_excepcion_rechaza_active_0_sin_deleted_at() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO disponibilidad_excepcion
				    (organization_id, consultorio_id, membership_id, tipo, motivo,
				     fecha_desde, fecha_hasta, hora_desde, hora_hasta, active, deleted_at,
				     deactivation_reason, version, created_at, updated_at)
				VALUES (?, ?, ?, 'CIERRE', 'AUSENCIA', CURRENT_DATE(),
				        DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY), NULL, NULL,
				        0, NULL, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.membershipId()))
				.as("active = 0 con deleted_at NULL tiene que violar "
						+ "ck_disponibilidad_excepcion_baja_coherente")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_disponibilidad_excepcion_baja_coherente");
	}

	// =================================================================================
	// disponibilidad_excepcion: hora_desde y hora_hasta viajan juntas
	// =================================================================================

	@Test
	@DisplayName("el CHECK de excepcion rechaza hora_desde sin hora_hasta")
	void el_check_de_excepcion_rechaza_hora_desde_sin_hora_hasta() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO disponibilidad_excepcion
				    (organization_id, consultorio_id, membership_id, tipo, motivo,
				     fecha_desde, fecha_hasta, hora_desde, hora_hasta, active, version,
				     created_at, updated_at)
				VALUES (?, ?, ?, 'CIERRE', 'AUSENCIA', CURRENT_DATE(),
				        DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY), '09:00:00', NULL,
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.membershipId()))
				.as("hora_desde sin hora_hasta tiene que violar ck_disponibilidad_excepcion_horario")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_disponibilidad_excepcion_horario");
	}

	// =================================================================================
	// disponibilidad_excepcion: membership_id NULL = alcance sede
	// =================================================================================

	@Test
	@DisplayName("una excepcion de sede admite membership_id NULL")
	void una_excepcion_de_sede_admite_membership_id_null() {
		Fixture fixture = crearFixture();

		// membership_id NULL: alcance SEDE ENTERA, mismo significado que en membership (V10)
		// y en colaborador_invitacion (V21). No tiene que lanzar.
		jdbc().update("""
				INSERT INTO disponibilidad_excepcion
				    (organization_id, consultorio_id, membership_id, tipo, motivo,
				     fecha_desde, fecha_hasta, hora_desde, hora_hasta, active, version,
				     created_at, updated_at)
				VALUES (?, ?, NULL, 'CIERRE', 'FERIADO', CURRENT_DATE(),
				        DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY), NULL, NULL,
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId());

		Long total = jdbc().queryForObject("""
				SELECT COUNT(*) FROM disponibilidad_excepcion
				 WHERE organization_id = ? AND consultorio_id = ? AND membership_id IS NULL
				""", Long.class, fixture.organizationId(), fixture.consultorioId());
		assertThat(total).isEqualTo(1L);
	}

	// =================================================================================
	// consultorio_calendario: una fila por sede
	// =================================================================================

	@Test
	@DisplayName("consultorio_calendario es unico por sede")
	void consultorio_calendario_es_unico_por_sede() {
		Fixture fixture = crearFixture();

		insertarCalendario(fixture);

		assertThatThrownBy(() -> insertarCalendario(fixture))
				.as("uk_consultorio_calendario_sede tiene que impedir una segunda fila de "
						+ "politica para la misma sede")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// Fixture — organization, consultorio, cuenta y membership sinteticos
	// =================================================================================

	private record Fixture(long organizationId, long consultorioId, long membershipId) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);
		long accountId = insertarCuenta(sufijo);
		long membershipId = insertarMembership(organizationId, consultorioId, accountId);

		return new Fixture(organizationId, consultorioId, membershipId);
	}

	private long insertarOrganization(String sufijo) {
		String slug = "disponibilidad-" + sufijo;
		jdbc().update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Disponibilidad Sintetica " + sufijo, slug);
		return jdbc().queryForObject(
				"SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String sufijo) {
		jdbc().update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "Sede Sintetica " + sufijo);
		return jdbc().queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, "Sede Sintetica " + sufijo);
	}

	private long insertarCuenta(String sufijo) {
		String email = "disponibilidad-" + sufijo + "@ejemplo.test";
		jdbc().update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'Disponibilidad', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc().queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	private long insertarMembership(long organizationId, long consultorioId, long accountId) {
		jdbc().update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId);
		return jdbc().queryForObject("""
				SELECT id FROM membership
				 WHERE organization_id = ? AND consultorio_id = ? AND account_id = ?
				""", Long.class, organizationId, consultorioId, accountId);
	}

	private void insertarBloque(Fixture fixture, int diaSemana, String horaDesde, String horaHasta) {
		jdbc().update("""
				INSERT INTO profesional_disponibilidad
				    (organization_id, consultorio_id, membership_id, dia_semana, hora_desde,
				     hora_hasta, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, CURRENT_DATE(), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.membershipId(),
				diaSemana, horaDesde, horaHasta);
	}

	private void insertarCalendario(Fixture fixture) {
		jdbc().update("""
				INSERT INTO consultorio_calendario
				    (organization_id, consultorio_id, pais, cierra_por_feriado, version,
				     created_at, updated_at)
				VALUES (?, ?, 'AR', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId());
	}
}
