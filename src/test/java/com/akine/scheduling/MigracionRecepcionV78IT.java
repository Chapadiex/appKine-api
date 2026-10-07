package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La migracion {@code V78} sobre datos que existian antes de ella (AKINE E-4, DP-16).
 *
 * <p>El esquema del contexto de Spring ya esta migrado hasta el final, asi que no sirve: aca se
 * crea una base propia en el mismo contenedor, se migra hasta {@code V76} (la ultima antes de
 * {@code V78}), se siembran turnos con la forma que les dejaba 05.04 —en espera hoy, en espera de
 * la semana pasada, cancelado desde la espera y uno sin llegada— y recien entonces se aplica
 * {@code V78}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class MigracionRecepcionV78IT {

	@Autowired private MySQLContainer mysql;

	private String base;
	private JdbcTemplate root;
	private JdbcTemplate jdbc;
	private DriverManagerDataSource dataSource;

	@BeforeEach
	void crearBase() {
		base = "akine_v78_" + UUID.randomUUID().toString().substring(0, 8);
		root = new JdbcTemplate(fuente(""));
		root.execute("CREATE DATABASE " + base);
		dataSource = fuente(base);
		jdbc = new JdbcTemplate(dataSource);
	}

	@AfterEach
	void borrarBase() {
		// Es la base descartable de este test, no datos del sistema.
		root.execute("DROP DATABASE IF EXISTS " + base);
	}

	@Test
	@DisplayName("V78 pasa cada llegada a una recepcion, devuelve el turno a su estado de reserva y cierra las canceladas")
	void v78_migra_los_turnos_en_espera() {
		migrarHasta("76");
		Semilla s = sembrar();

		Instant hoy = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant semanaPasada = hoy.minus(7, ChronoUnit.DAYS);

		long enEsperaPasado = turno(s, semanaPasada, "EN_ESPERA", "CONFIRMADO", semanaPasada.minusSeconds(600), false);
		long enEsperaHoy = turno(s, hoy.plus(1, ChronoUnit.HOURS), "EN_ESPERA", null, hoy, false);
		long canceladoDesdeLaEspera = turno(s, hoy.plus(2, ChronoUnit.HOURS), "CANCELADO", "RESERVADO", hoy, true);
		long sinLlegada = turno(s, hoy.plus(3, ChronoUnit.HOURS), "RESERVADO", null, null, false);

		migrarHasta("78");

		// --- turno en espera de la semana pasada ---------------------------------------
		assertThat(jdbc.queryForMap("SELECT estado, version, llegada_en FROM turno WHERE id = ?", enEsperaPasado))
				.as("vuelve al estado del que vino, la version avanza y la columna vieja conserva el dato")
				.containsEntry("estado", "CONFIRMADO")
				.containsEntry("version", 1L)
				.containsKey("llegada_en");
		Map<String, Object> recepcionPasada = recepcionDe(enEsperaPasado);
		assertThat(recepcionPasada.get("estado"))
				.as("no se inventa el desenlace: el dato dice que llego y quedo esperando")
				.isEqualTo("EN_ESPERA");
		assertThat(instante("SELECT CAST(llegada_en AS CHAR) FROM recepcion WHERE turno_id = ?", enEsperaPasado))
				.isEqualTo(semanaPasada.minusSeconds(600));
		assertThat(((Number) recepcionPasada.get("llegada_por_cuenta_id")).longValue()).isEqualTo(s.cuentaId());
		assertThat(eventosDe(enEsperaPasado)).extracting(e -> e.get("tipo")).containsExactly("LLEGADA");
		assertThat(instante("SELECT CAST(ocurrido_en AS CHAR) FROM recepcion_evento WHERE turno_id = ?", enEsperaPasado))
				.as("el evento lleva la hora REAL de llegada, no la de la migracion")
				.isEqualTo(semanaPasada.minusSeconds(600));

		// --- turno en espera de hoy, sin estado anterior registrado -----------------------
		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?", String.class, enEsperaHoy))
				.isEqualTo("RESERVADO");
		assertThat(recepcionDe(enEsperaHoy).get("estado")).isEqualTo("EN_ESPERA");

		// --- cancelado con la persona presente ------------------------------------------
		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?", String.class, canceladoDesdeLaEspera))
				.isEqualTo("CANCELADO");
		Map<String, Object> cerrada = recepcionDe(canceladoDesdeLaEspera);
		assertThat(cerrada.get("estado")).isEqualTo("CERRADA");
		assertThat(cerrada.get("motivo_cierre")).isEqualTo("El profesional se descompuso");
		assertThat(cerrada.get("cerrada_en")).isNotNull();
		assertThat(eventosDe(canceladoDesdeLaEspera)).extracting(e -> e.get("tipo"))
				.containsExactly("LLEGADA", "CIERRE_POR_CANCELACION");

		// --- sin llegada: nada ------------------------------------------------------------
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recepcion WHERE turno_id = ?",
				Integer.class, sinLlegada)).isZero();

		// --- y el turno ya no admite la espera ---------------------------------------------
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE estado = 'EN_ESPERA'", Integer.class))
				.isZero();
		assertThatThrownBy(() -> turno(s, hoy.plus(4, ChronoUnit.HOURS), "EN_ESPERA", "RESERVADO", hoy, false))
				.as("ck_turno_estado: la espera ya no es un estado del turno")
				.hasMessageContaining("ck_turno_estado");
	}

	// =================================================================================
	// Siembra con la forma de 05.04
	// =================================================================================

	private record Semilla(long organizationId, long consultorioId, long ofertaId, long personaId, long cuentaId) {
	}

	private Semilla sembrar() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro Sintetico " + sufijo, "v78-" + sufijo);
		long org = jdbc.queryForObject("SELECT MAX(id) FROM organization", Long.class);
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "Sede Sintetica " + sufijo);
		long sede = jdbc.queryForObject("SELECT MAX(id) FROM consultorio", Long.class);
		String email = "v78-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active, version,
				                    created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		long cuenta = jdbc.queryForObject("SELECT MAX(id) FROM cuenta", Long.class);
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, 'Servicio Sintetico', 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "V78-" + sufijo.toUpperCase());
		long servicio = jdbc.queryForObject("SELECT MAX(id) FROM servicio", Long.class);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'Oferta Sintetica', 'INDIVIDUAL', 60, 1, 0, 0, 0, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 1 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, servicio);
		long oferta = jdbc.queryForObject("SELECT MAX(id) FROM oferta_servicio_consultorio", Long.class);
		jdbc.update("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, 'Sintetica', 'Paciente', 'SINTETICA', 'PACIENTE', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org);
		long persona = jdbc.queryForObject("SELECT MAX(id) FROM persona", Long.class);
		return new Semilla(org, sede, oferta, persona, cuenta);
	}

	/** Un turno con las columnas que le dejaba 05.04, incluida la llegada en la fila del turno. */
	private long turno(Semilla s, Instant inicio, String estado, String estadoAntesDeEspera,
			Instant llegada, boolean cancelado) {

		Instant ahora = Instant.now();
		jdbc.update("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id, inicio, fin,
				                   estado, confirmado_en, motivo_cancelacion, cancelado_en,
				                   cancelado_por_cuenta_id, llegada_en, llegada_por_cuenta_id,
				                   estado_antes_de_espera, reservado_por_cuenta_id, reservado_en,
				                   deleted_at, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				s.organizationId(), s.consultorioId(), s.ofertaId(), s.personaId(),
				utc(inicio), utc(inicio.plus(60, ChronoUnit.MINUTES)), estado,
				"CONFIRMADO".equals(estadoAntesDeEspera) ? utc(inicio.minus(1, ChronoUnit.DAYS)) : null,
				cancelado ? "El profesional se descompuso" : null,
				cancelado ? utc(ahora) : null,
				cancelado ? s.cuentaId() : null,
				llegada == null ? null : utc(llegada),
				llegada == null ? null : s.cuentaId(),
				estadoAntesDeEspera,
				s.cuentaId(), utc(inicio.minus(10, ChronoUnit.DAYS)),
				cancelado ? utc(ahora) : null);
		return jdbc.queryForObject("SELECT MAX(id) FROM turno", Long.class);
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private void migrarHasta(String version) {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target(version)
				.load()
				.migrate();
	}

	private Map<String, Object> recepcionDe(long turnoId) {
		return jdbc.queryForMap("SELECT * FROM recepcion WHERE turno_id = ?", turnoId);
	}

	private List<Map<String, Object>> eventosDe(long turnoId) {
		return jdbc.queryForList(
				"SELECT tipo, ocurrido_en FROM recepcion_evento WHERE turno_id = ? ORDER BY ocurrido_en, id",
				turnoId);
	}

	/**
	 * Los instantes se siembran como {@link LocalDateTime} en UTC —el driver no convierte ese tipo
	 * de zona— y se leen como texto: comparar no depende de la zona del contenedor ni de la JVM.
	 */
	private DriverManagerDataSource fuente(String nombreDeBase) {
		String url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/"
				+ nombreDeBase + "?useSSL=false&allowPublicKeyRetrieval=true";
		return new DriverManagerDataSource(url, "root", mysql.getPassword());
	}

	private static LocalDateTime utc(Instant instante) {
		return LocalDateTime.ofInstant(instante, ZoneOffset.UTC);
	}

	private Instant instante(String consulta, long turnoId) {
		String texto = jdbc.queryForObject(consulta, String.class, turnoId);
		return LocalDateTime.parse(texto.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
	}
}
