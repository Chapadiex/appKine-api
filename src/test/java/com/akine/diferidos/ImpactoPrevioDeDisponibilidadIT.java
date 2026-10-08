package com.akine.diferidos;

import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La consulta previa de impacto de disponibilidad (A-11) contra MySQL real y por HTTP.
 *
 * <p>Tres cosas, en este orden de importancia: que <b>no modifica nada</b> —ni el bloque, ni las
 * excepciones, ni la auditoria—, que <b>cuenta exacto</b> —un profesional con turnos el lunes y el
 * martes, en dos bloques distintos: dar de baja el del lunes afecta UN turno, no los dos que
 * contaba la cota superior de E-1— y que <b>otro tenant no ve nada</b>.
 */
class ImpactoPrevioDeDisponibilidadIT extends BaseEscenarioDiferido {

	@Autowired private TurnoService turnoService;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("Baja, edicion y cierre de sede: cuenta exacta, sin modificar nada, aislado por tenant")
	void consulta_previa_sin_efectos() {
		Sesion sesion = altaCompleta("impacto-previo");
		Fixture fixture = fixtures.poblar(sesion.organizationId(), sesion.consultorioId(),
				sesion.cuentaId(), 1, false);
		long membership = fixture.profesionalMembershipId();
		long bloqueLunes = jdbc.queryForObject(
				"SELECT id FROM profesional_disponibilidad WHERE membership_id = ?",
				Long.class, membership);
		// Segundo bloque del MISMO profesional: el martes. Es el caso que la cota de E-1 contaba mal.
		jdbc.update("""
				INSERT INTO profesional_disponibilidad
				       (organization_id, consultorio_id, membership_id, dia_semana, hora_desde,
				        hora_hasta, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 2, '09:00', '13:00', DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", sesion.organizationId(), sesion.consultorioId(), membership);

		ZoneId zona = ZoneId.of(jdbc.queryForObject(
				"SELECT timezone FROM consultorio WHERE id = ?", String.class, sesion.consultorioId()));
		LocalDate lunes = LocalDate.now(zona).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
		TurnoView turnoLunes = reservar(fixture, fixture.personaA(), lunes, zona);
		reservar(fixture, fixture.personaB(), lunes.plusDays(1), zona);

		String rutaBloque = "/api/v1/consultorios/" + sesion.consultorioId() + "/profesionales/"
				+ membership + "/disponibilidad/" + bloqueLunes;
		Map<String, Object> bloqueAntes = jdbc.queryForMap(
				"SELECT active, version, hora_desde FROM profesional_disponibilidad WHERE id = ?",
				bloqueLunes);
		long auditoriaAntes = contarFilas("audit_event");
		long excepcionesAntes = contarFilas("disponibilidad_excepcion");

		// Baja: solo el turno del lunes queda afuera; el del martes sigue cubierto por su bloque.
		Respuesta baja = get(rutaBloque + "/impacto-de-baja", sesion.token());
		assertThat(baja.status()).as("baja: %s", baja.body()).isEqualTo(200);
		assertThat(baja.json().get("turnosAfectados").asLong()).isEqualTo(1L);
		assertThat(baja.json().get("turnos").get(0).get("turnoId").asLong()).isEqualTo(turnoLunes.id());
		assertThat(Instant.parse(baja.texto("primerTurnoAfectado"))).isEqualTo(turnoLunes.inicio());

		// Edicion que corre el inicio a las 10: el turno de las 9 queda afuera.
		Respuesta edicion = post(rutaBloque + "/impacto-de-edicion", sesion.token(),
				"{\"horaDesde\":\"10:00\"}");
		assertThat(edicion.status()).as("edicion: %s", edicion.body()).isEqualTo(200);
		assertThat(edicion.json().get("turnosAfectados").asLong()).isEqualTo(1L);

		// Edicion que amplia: nada queda afuera.
		Respuesta amplia = post(rutaBloque + "/impacto-de-edicion", sesion.token(),
				"{\"horaDesde\":\"08:00\"}");
		assertThat(amplia.json().get("turnosAfectados").asLong()).isZero();

		// Cierre de SEDE el lunes: alcanza al turno del lunes y no al del martes.
		Respuesta cierre = post("/api/v1/consultorios/" + sesion.consultorioId()
						+ "/excepciones/impacto-de-alta", sesion.token(),
				"{\"tipo\":\"CIERRE\",\"motivo\":\"AUSENCIA\",\"fechaDesde\":\"" + lunes
						+ "\",\"fechaHasta\":\"" + lunes.plusDays(1) + "\"}");
		assertThat(cierre.status()).as("cierre: %s", cierre.body()).isEqualTo(200);
		assertThat(cierre.json().get("turnosAfectados").asLong()).isEqualTo(1L);

		// Nada cambio en la base: ni el bloque, ni las excepciones, ni la auditoria.
		assertThat(jdbc.queryForMap(
				"SELECT active, version, hora_desde FROM profesional_disponibilidad WHERE id = ?",
				bloqueLunes)).isEqualTo(bloqueAntes);
		assertThat(contarFilas("audit_event")).isEqualTo(auditoriaAntes);
		assertThat(contarFilas("disponibilidad_excepcion")).isEqualTo(excepcionesAntes);
		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?", String.class,
				turnoLunes.id())).isEqualTo("RESERVADO");

		// Otro tenant, con su propio token, no ve la agenda ajena.
		Sesion ajena = altaCompleta("impacto-ajeno");
		Respuesta cruzada = get(rutaBloque + "/impacto-de-baja", ajena.token());
		assertThat(cruzada.status()).as("cruzada: %s", cruzada.body()).isIn(403, 404);
		assertThat(cruzada.body()).doesNotContain("turnosAfectados");
	}

	private TurnoView reservar(Fixture fixture, long personaId, LocalDate fecha, ZoneId zona) {
		Instant inicio = fecha.atTime(LocalTime.of(9, 0)).atZone(zona).toInstant();
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}
}
