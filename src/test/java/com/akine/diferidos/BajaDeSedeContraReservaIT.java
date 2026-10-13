package com.akine.diferidos;

import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.domain.exception.SedeDadaDeBajaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.akine.scheduling.AgendaFixtures.causaEs;
import static com.akine.scheduling.AgendaFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * La carrera residual entre la baja de una sede y una reserva (decision pendiente 8b del
 * {@code CLAUDE.md}).
 *
 * <p>La baja toma el lock del tenant y le pregunta a {@code SedeConTurnosPendientes}; la reserva
 * toma el de {@code agenda_sede}. Hasta este cambio la reserva validaba la sede FUERA de su lock y
 * la baja leia la sede sin lock: una reserva podia leer la sede activa despues de que la sonda de la
 * baja contara cero turnos, y las dos commiteaban. Quedaba un turno vivo en una sede de baja.
 *
 * <p>Ahora se encuentran en la fila de {@code consultorio}: la baja la toma en exclusivo antes de
 * la sonda y la reserva la relee en compartido bajo su lock de agenda. Dos escenarios:
 *
 * <ul>
 *   <li><b>Determinista:</b> una transaccion a mano hace lo que la baja hace hasta el commit —lock
 *       exclusivo y {@code active = 0}— y la reserva arranca con ella abierta. Tiene que quedarse
 *       esperando y, al commitear la baja, rechazarse. Con el codigo anterior la reserva no
 *       esperaba nada y entraba.</li>
 *   <li><b>Carrera real:</b> baja por HTTP y reserva por servicio con una barrera, varias rondas
 *       con una sede nueva cada una (la primera reserva de la sede crea ademas su
 *       {@code agenda_sede}). Gane quien gane, nunca queda un turno vivo en una sede de baja.</li>
 * </ul>
 */
class BajaDeSedeContraReservaIT extends BaseEscenarioDiferido {

	private static final int RONDAS = 6;

	@Autowired private TurnoService turnoService;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("Una reserva que llega con la baja de la sede en curso la espera y se rechaza con 409")
	void la_reserva_espera_a_la_baja_y_se_rechaza() throws Exception {
		Sesion sesion = altaCompleta("carrera-sede-det");
		habilitarMasDeUnaSede(sesion);
		long sedeId = crearSede(sesion);
		Fixture fixture = fixtures.poblar(
				sesion.organizationId(), sedeId, sesion.cuentaId(), 1, false);

		try (Connection baja = dataSource.getConnection()) {
			baja.setAutoCommit(false);
			// Lo que hace ConsultorioService.deactivate entre el paso 3 y el commit.
			ejecutar(baja, "SELECT id FROM consultorio WHERE id = ? FOR UPDATE", sedeId);
			ejecutar(baja, "UPDATE consultorio SET active = 0 WHERE id = ?", sedeId);

			CompletableFuture<Object> reserva = CompletableFuture.supplyAsync(() -> reservar(
					fixture, fixture.personaA(), proximoLunesALas(9)));

			Thread.sleep(1_500);
			assertThat(reserva)
					.as("con la baja abierta la reserva tiene que esperar el lock de la sede")
					.isNotDone();

			baja.commit();

			assertThat(reserva)
					.failsWithin(30, TimeUnit.SECONDS)
					.withThrowableThat()
					.havingRootCause()
					.isInstanceOf(SedeDadaDeBajaException.class);
		}

		assertThat(turnosVivos(sedeId)).as("ningun turno en la sede dada de baja").isZero();
	}

	@Test
	@DisplayName("Baja de sede y reserva simultaneas: nunca queda un turno vivo en una sede dada de baja")
	void baja_y_reserva_simultaneas_no_dejan_turno_en_sede_de_baja() {
		Sesion sesion = altaCompleta("carrera-sede");
		habilitarMasDeUnaSede(sesion);

		List<String> desenlaces = new ArrayList<>();
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			long sedeId = crearSede(sesion);
			Fixture fixture = fixtures.poblar(
					sesion.organizationId(), sedeId, sesion.cuentaId(), 1, false);
			String ruta = "/api/v1/organizations/" + sesion.organizationId()
					+ "/consultorios/" + sedeId + "/deactivate";

			List<Desenlace<Object>> resultado = enParalelo(List.<Callable<Object>>of(
					() -> post(ruta, sesion.token(), "{\"reason\":\"Cierre sintetico\"}"),
					() -> reservar(fixture, fixture.personaA(), proximoLunesALas(9))));

			Respuesta respuestaBaja = (Respuesta) resultado.get(0).valor();
			Desenlace<Object> reserva = resultado.get(1);
			boolean sedeActiva = activa(sedeId);
			long vivos = turnosVivos(sedeId);
			desenlaces.add("baja=" + respuestaBaja.status() + " reserva="
					+ (reserva.fallo() ? reserva.error().getClass().getSimpleName() : "ok"));

			assertThat(!sedeActiva && vivos > 0)
					.as("ronda %d: turno vivo en sede de baja. activa=%s vivos=%d (%s)",
							ronda, sedeActiva, vivos, desenlaces)
					.isFalse();
			if (sedeActiva) {
				assertThat(respuestaBaja.status()).as("ronda %d: %s", ronda, respuestaBaja.body())
						.isEqualTo(409);
			} else {
				assertThat(respuestaBaja.status()).isEqualTo(200);
				assertThat(reserva.fallo()).as("ronda %d: la reserva tuvo que perder", ronda).isTrue();
				assertThat(causaEs(reserva.error(), SedeDadaDeBajaException.class))
						.as("ronda %d: y perder con consultorio-inactive: %s", ronda, reserva.error())
						.isTrue();
			}
		}
	}

	// =================================================================================
	// Ayudantes
	// =================================================================================

	private Object reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}

	/** El fixture siembra disponibilidad los lunes en la zona de {@link AgendaFixtures#ZONA}. */
	private static Instant proximoLunesALas(int hora) {
		ZoneId zona = ZoneId.of(AgendaFixtures.ZONA);
		LocalDate lunes = LocalDate.now(zona).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
		return lunes.atTime(hora, 0).atZone(zona).toInstant();
	}

	private static void ejecutar(Connection conexion, String sql, long id) throws Exception {
		try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
			sentencia.setLong(1, id);
			sentencia.execute();
		}
	}

	private long crearSede(Sesion sesion) {
		Respuesta alta = post(
				"/api/v1/organizations/" + sesion.organizationId() + "/consultorios",
				sesion.token(),
				"{\"name\":\"Sede Carrera " + UUID.randomUUID() + "\",\"timezone\":\""
						+ AgendaFixtures.ZONA + "\"}",
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
		assertThat(alta.status()).as("alta de sede: %s", alta.body()).isEqualTo(201);
		return alta.json().get("id").asLong();
	}

	/** El plan por defecto admite una sede; la baja exige que quede otra activa. */
	private void habilitarMasDeUnaSede(Sesion sesion) {
		Sesion plataforma = altaCompleta("plataforma");
		sembrarRolDePlataforma(plataforma.cuentaId());
		Respuesta cambio = post(
				"/api/v1/organizations/" + sesion.organizationId() + "/subscription/plan-changes",
				plataforma.token(),
				"{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":0}");
		assertThat(cambio.status()).as("plan sin tope de sedes: %s", cambio.body()).isEqualTo(200);
	}

	private boolean activa(long sedeId) {
		Long activa = jdbc.queryForObject(
				"SELECT active FROM consultorio WHERE id = ?", Long.class, sedeId);
		return activa != null && activa == 1L;
	}

	private long turnosVivos(long sedeId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM turno WHERE consultorio_id = ? AND deleted_at IS NULL",
				Long.class, sedeId);
	}
}
