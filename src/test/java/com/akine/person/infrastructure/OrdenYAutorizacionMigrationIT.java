package com.akine.person.infrastructure;

import com.akine.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Lo que V44 hace cumplir contra MySQL real, y que ningun doble puede simular.
 *
 * <p>Dos categorias, y la distincion es el punto de la etapa:
 *
 * <ul>
 *   <li><b>Lo que la base SI expresa</b>: los uniques del numero —porque ahi la regla es una
 *       igualdad—, los CHECK de coherencia, y las FK RESTRICT.
 *   <li><b>Lo que la base NO puede expresar</b>: el solapamiento de intervalos. Hay un caso que
 *       comprueba que la base <b>acepta</b> dos autorizaciones aprobadas solapadas, para que quien
 *       intente "arreglarlo" con un indice descubra ahi que el indice no expresa la regla. Quien
 *       la hace cumplir es el lock, y eso lo prueba {@code AutorizacionConcurrenteIT}.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@DisplayName("Esquema de ordenes y autorizaciones (V44)")
class OrdenYAutorizacionMigrationIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("una orden no puede empezar a valer antes de su fecha de emision")
	void ck_orden_emision_previa() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> insertarOrden(fixture, "OM-1", "2027-06-01", "2027-01-01", null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_orden_emision_previa");
	}

	@Test
	@DisplayName("una orden de un solo dia se admite: la vigencia es INCLUSIVA")
	void orden_de_un_solo_dia() {
		Fixture fixture = crearFixture();

		assertThatCode(() ->
				insertarOrden(fixture, "OM-1", "2027-01-01", "2027-01-01", "2027-01-01"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el mismo numero de orden no entra dos veces vigente, y SI despues de la baja logica")
	void uk_orden_numero() {
		Fixture fixture = crearFixture();
		insertarOrden(fixture, "OM-1", "2027-01-01", "2027-01-01", null);

		assertThatThrownBy(() -> insertarOrden(fixture, "OM-1", "2027-01-01", "2027-01-01", null))
				.isInstanceOf(DataIntegrityViolationException.class);

		// El discriminador deleted_key: el numero de un documento dado de baja se puede reusar.
		jdbc.update("""
				UPDATE orden_medica SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'cargada por error'
				 WHERE organization_id = ? AND persona_id = ?
				""", fixture.organizationId(), fixture.personaId());

		assertThatCode(() -> insertarOrden(fixture, "OM-1", "2027-01-01", "2027-01-01", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("varias ordenes SIN numero conviven: los NULL no colisionan, y eso es lo que hace falta")
	void ordenes_sin_numero() {
		Fixture fixture = crearFixture();

		// Muchas ordenes en papel no traen numero impreso. Si el unique las bloqueara, el
		// mostrador no podria cargar la segunda.
		insertarOrden(fixture, null, "2027-01-01", "2027-01-01", null);
		assertThatCode(() -> insertarOrden(fixture, null, "2027-02-01", "2027-02-01", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("observar o rechazar sin motivo se rechaza desde la base")
	void ck_autorizacion_motivo() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> insertarAutorizacion(
				fixture, "AUT-1", "RECHAZADA", null, 10, "2027-01-01", "2027-12-31", false))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_autorizacion_motivo_exigido");
	}

	@Test
	@DisplayName("cero sesiones autorizadas se rechaza: sin tope se expresa con NULL, no con cero")
	void ck_autorizacion_cantidad() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> insertarAutorizacion(
				fixture, "AUT-1", "APROBADA", null, 0, "2027-01-01", "2027-12-31", false))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_autorizacion_cantidad_positiva");
	}

	@Test
	@DisplayName("el consumo no puede superar lo autorizado: la base lo impide antes de que exista el consumo")
	void ck_autorizacion_consumo() {
		Fixture fixture = crearFixture();
		insertarAutorizacion(
				fixture, "AUT-1", "APROBADA", null, 5, "2027-01-01", "2027-12-31", false);

		// Hoy nadie mueve cantidad_consumida. El CHECK esta para el dia que RF-M17-004 lo haga:
		// un decremento mal escrito choca aca en vez de dejar saldo fantasma.
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE autorizacion SET cantidad_consumida = 6
				 WHERE organization_id = ? AND numero = 'AUT-1'
				""", fixture.organizationId()))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_autorizacion_consumo_coherente");
	}

	@Test
	@DisplayName("el snapshot del convenio viaja ENTERO o no viaja: media referencia se rechaza")
	void ck_autorizacion_snapshot() {
		Fixture fixture = crearFixture();

		// Media referencia —un convenio_id sin lo que exigia aquel dia— es exactamente el puntero
		// que estas columnas existen para no ser.
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO autorizacion (organization_id, persona_id, consultorio_id, cobertura_id,
				        practica_id, numero, estado, cantidad_autorizada, vigencia_desde,
				        convenio_id, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'AUT-MEDIA', 'APROBADA', 5, '2027-01-01', 12, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.personaId(), fixture.consultorioId(),
				fixture.coberturaId(), fixture.practicaId()))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_autorizacion_snapshot_coherente");
	}

	@Test
	@DisplayName("una autorizacion SIN snapshot se admite: el mostrador puede cargar sin convenio resoluble")
	void autorizacion_sin_snapshot() {
		Fixture fixture = crearFixture();

		assertThatCode(() -> insertarAutorizacion(
				fixture, "AUT-1", "PENDIENTE", null, 5, "2027-01-01", null, false))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("LA BASE ACEPTA dos autorizaciones aprobadas solapadas: ningun indice expresa la regla")
	void la_base_acepta_el_solapamiento() {
		Fixture fixture = crearFixture();
		insertarAutorizacion(
				fixture, "AUT-1", "APROBADA", null, 10, "2027-01-01", "2027-12-31", false);

		// 01-01..12-31 y 03-01..06-30 se pisan sin compartir un solo valor de columna, y MySQL 8.4
		// no tiene exclusion constraints. Si alguien intenta "arreglar" el lock con un indice, va
		// a descubrir aca que el indice no dice nada. Quien corta es AutorizacionService bajo el
		// lock de autorizacion_persona_lock, y eso lo prueba AutorizacionConcurrenteIT.
		assertThatCode(() -> insertarAutorizacion(
				fixture, "AUT-2", "APROBADA", null, 10, "2027-03-01", "2027-06-30", false))
				.doesNotThrowAnyException();

		assertThat(contarAutorizaciones(fixture)).isEqualTo(2);
	}

	@Test
	@DisplayName("el mismo numero de autorizacion no entra dos veces vigente bajo la misma cobertura")
	void uk_autorizacion_numero() {
		Fixture fixture = crearFixture();
		insertarAutorizacion(
				fixture, "AUT-1", "PENDIENTE", null, 10, "2027-01-01", "2027-12-31", false);

		assertThatThrownBy(() -> insertarAutorizacion(
				fixture, "AUT-1", "PENDIENTE", null, 10, "2028-01-01", "2028-12-31", false))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("el borrado fisico de una orden referenciada por una autorizacion es imposible")
	void fk_restrict() {
		Fixture fixture = crearFixture();
		insertarOrden(fixture, "OM-1", "2027-01-01", "2027-01-01", null);
		Long ordenId = jdbc.queryForObject(
				"SELECT id FROM orden_medica WHERE organization_id = ? AND numero = 'OM-1'",
				Long.class, fixture.organizationId());
		insertarAutorizacion(
				fixture, "AUT-1", "PENDIENTE", ordenId, 10, "2027-01-01", "2027-12-31", false);

		// RESTRICT por defecto: en M17 las bajas son logicas, asi que la fila referenciada siempre
		// resuelve. Un borrado fisico dejaria una autorizacion apuntando a nada.
		assertThatThrownBy(() ->
				jdbc.update("DELETE FROM orden_medica WHERE id = ?", ordenId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("el candado es unico por persona y su INSERT ... ON DUPLICATE KEY es idempotente")
	void candado_idempotente() {
		Fixture fixture = crearFixture();

		for (int i = 0; i < 3; i++) {
			jdbc.update("""
					INSERT INTO autorizacion_persona_lock (organization_id, persona_id, created_at)
					VALUES (?, ?, UTC_TIMESTAMP(6))
					ON DUPLICATE KEY UPDATE id = id
					""", fixture.organizationId(), fixture.personaId());
		}

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion_persona_lock
				 WHERE organization_id = ? AND persona_id = ?
				""", Integer.class, fixture.organizationId(), fixture.personaId()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("la baja incoherente —inactiva sin motivo— se rechaza")
	void ck_baja_coherente() {
		Fixture fixture = crearFixture();
		insertarOrden(fixture, "OM-1", "2027-01-01", "2027-01-01", null);

		assertThatThrownBy(() -> jdbc.update("""
				UPDATE orden_medica SET active = 0 WHERE organization_id = ?
				""", fixture.organizationId()))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_orden_baja_coherente");
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long personaId,
			long coberturaId,
			long practicaId) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "v44-" + sufijo, ZONA);
		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "Sede " + sufijo, ZONA);
		String documento = String.valueOf(10000000 + Math.abs(sufijo.hashCode()) % 80000000);
		long personaId = insertar("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				VALUES (?, 'DNI', ?, ?, 'Sintetica', 'Paciente', 'SINTETICA', 'PACIENTE',
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		long financiadorId = insertar("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "OS-" + sufijo, "Financiador " + sufijo);
		long planId = insertar("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 1, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, financiadorId, "P-" + sufijo, "Plan " + sufijo);
		long coberturaId = insertar("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        vigencia_desde, principal, active, version, created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'PREPAGA', ?, ?, ?, 1, 0, UTC_TIMESTAMP(6),
				        '2020-01-01', 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, financiadorId, "OS-" + sufijo,
				"Financiador " + sufijo, planId, "P-" + sufijo, "Plan " + sufijo);
		long especialidadId = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "E-" + sufijo, "Especialidad " + sufijo);
		long practicaId = insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, especialidadId, "PR-" + sufijo, "Practica " + sufijo);

		return new Fixture(organizationId, consultorioId, personaId, coberturaId, practicaId);
	}

	private long insertar(String sql, Object... args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertarOrden(
			Fixture fixture, String numero, String emision, String desde, String hasta) {

		jdbc.update("""
				INSERT INTO orden_medica (organization_id, persona_id, consultorio_id, numero,
				        profesional_emisor, fecha_emision, vigencia_desde, vigencia_hasta,
				        active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'Dra. Sintetica', ?, ?, ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.personaId(), fixture.consultorioId(),
				numero, emision, desde, hasta);
	}

	@SuppressWarnings("java:S107")
	private void insertarAutorizacion(
			Fixture fixture,
			String numero,
			String estado,
			Long ordenMedicaId,
			Integer cantidad,
			String desde,
			String hasta,
			boolean conMotivo) {

		jdbc.update("""
				INSERT INTO autorizacion (organization_id, persona_id, consultorio_id, cobertura_id,
				        orden_medica_id, practica_id, numero, estado, motivo, cantidad_autorizada,
				        vigencia_desde, vigencia_hasta, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.personaId(), fixture.consultorioId(),
				fixture.coberturaId(), ordenMedicaId, fixture.practicaId(), numero, estado,
				conMotivo ? "motivo sintetico" : null, cantidad, desde, hasta);
	}

	private int contarAutorizaciones(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion WHERE organization_id = ? AND persona_id = ?
				""", Integer.class, fixture.organizationId(), fixture.personaId());
	}
}
