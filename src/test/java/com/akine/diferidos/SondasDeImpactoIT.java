package com.akine.diferidos;

import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.infrastructure.DisponibilidadImpactoSobreTurnos;
import com.akine.scheduling.infrastructure.EspacioOcupadoPorTurnos;
import com.akine.scheduling.infrastructure.ProfesionalConTurnosPendientes;
import com.akine.scheduling.infrastructure.SedeConTurnosPendientes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpRequest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las cuatro sondas de impacto de F2 contra turnos reales (paquete E-1).
 *
 * <h2>Que se prueba</h2>
 *
 * <p>Cada etapa de F2 dejo una sonda para preguntar "esto afecta turnos futuros?" y F5 cerro sin
 * implementarlas: hasta este cambio se podia dar de baja una sede o un box con la agenda llena y
 * recibir 200. Estos escenarios recorren el camino real —HTTP, token, MySQL— y miran dos cosas en
 * cada caso: que con un turno pendiente la operacion responda lo que el modulo dueño ya habia
 * reservado en el contrato, y que sin turnos pendientes (cancelados, o ausentes en el pasado) la
 * operacion entre.
 *
 * <p>Dos sondas <b>bloquean</b> —sede y espacio, 409— y dos <b>informan</b> —desvinculacion y
 * cambio de disponibilidad—: RN-M05-004 pide que esos turnos queden visibles, no que la operacion
 * se impida. Por eso esas dos se verifican por lo que la respuesta muestra, no por un 409.
 *
 * <p>Los turnos se reservan por {@code TurnoService}, no se insertan a mano: asi el espacio lo
 * asigna el revalidador real. Lo unico que se toca por SQL es la fecha del turno ausente, porque
 * no hay forma de reservar en el pasado.
 */
class SondasDeImpactoIT extends BaseEscenarioDiferido {

	private static final String PROBLEMS = "https://akine.app/problems/";

	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private SedeConTurnosPendientes sondaSede;
	@Autowired private EspacioOcupadoPorTurnos sondaEspacio;
	@Autowired private ProfesionalConTurnosPendientes sondaProfesional;
	@Autowired private DisponibilidadImpactoSobreTurnos sondaDisponibilidad;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	// =================================================================================
	// Sede — ConsultorioDeactivationProbe, bloquea
	// =================================================================================

	@Test
	@DisplayName("La sede con turnos pendientes no se da de baja (409); cancelados y ausentes "
			+ "pasados no la frenan")
	void sede_con_turnos_futuros_responde_409() {
		Sesion sesion = altaCompleta("sondas-sede");
		habilitarMasDeUnaSede(sesion);
		Respuesta otra = crearSede(sesion, "Sede Respaldo " + UUID.randomUUID());
		assertThat(otra.status()).as("segunda sede: %s", otra.body()).isEqualTo(201);

		Fixture fixture = poblar(sesion, 1, false);
		TurnoView aCancelar = reservar(fixture, fixture.personaA(), hora(sesion, 9));
		TurnoView aEnvejecer = reservar(fixture, fixture.personaB(), hora(sesion, 10));

		String ruta = "/api/v1/organizations/" + sesion.organizationId()
				+ "/consultorios/" + sesion.consultorioId() + "/deactivate";
		Respuesta bloqueada = post(ruta, sesion.token(), "{\"reason\":\"Cierre sintetico\"}");

		assertThat(bloqueada.status()).as("baja con turnos: %s", bloqueada.body()).isEqualTo(409);
		assertThat(bloqueada.texto("type")).isEqualTo(PROBLEMS + "consultorio-has-active-references");
		assertThat(bloqueada.texto("referenceType")).isEqualTo("turnos-futuros");
		assertThat(bloqueada.json().get("referenceCount").asLong()).isEqualTo(2L);
		assertProblemaLimpio(bloqueada);
		assertThat(activa("consultorio", sesion.consultorioId()))
				.as("el rechazo tiene que verse en la BASE").isTrue();

		cancelar(fixture, aCancelar);
		envejecerComoAusente(aEnvejecer.id());

		Respuesta baja = post(ruta, sesion.token(), "{\"reason\":\"Cierre sintetico\"}");
		assertThat(baja.status()).as("baja sin turnos pendientes: %s", baja.body()).isEqualTo(200);
		assertThat(activa("consultorio", sesion.consultorioId())).isFalse();
	}

	// =================================================================================
	// Espacio — EspacioOccupancyProbe, bloquea la baja y la reduccion de capacidad
	// =================================================================================

	@Test
	@DisplayName("El box con un turno pendiente no se da de baja (409); cancelado el turno, entra")
	void espacio_con_turno_futuro_responde_409() {
		Sesion sesion = altaCompleta("sondas-box");
		long espacioId = crearEspacio(sesion, "Box Sondas", "BOX", 1);
		Fixture fixture = poblar(sesion, 1, true);

		TurnoView turno = reservar(fixture, fixture.personaA(), hora(sesion, 9));
		assertThat(turno.espacioId()).as("el revalidador asigno el box").isEqualTo(espacioId);

		String ruta = rutaEspacio(sesion, espacioId) + "/deactivate";
		Respuesta bloqueada = post(ruta, sesion.token(), "{\"reason\":\"Refaccion\"}");

		assertThat(bloqueada.status()).as("baja con turno: %s", bloqueada.body()).isEqualTo(409);
		assertThat(bloqueada.texto("type")).isEqualTo(PROBLEMS + "espacio-has-active-references");
		assertThat(bloqueada.texto("referenceType")).isEqualTo("turnos-futuros");
		assertThat(bloqueada.json().get("referenceCount").asLong()).isEqualTo(1L);
		assertThat(activa("espacio", espacioId)).isTrue();

		cancelar(fixture, turno);

		Respuesta baja = post(ruta, sesion.token(), "{\"reason\":\"Refaccion\"}");
		assertThat(baja.status()).as("baja sin turnos: %s", baja.body()).isEqualTo(200);
		assertThat(activa("espacio", espacioId)).isFalse();
	}

	/**
	 * La reduccion de capacidad mira el PICO simultaneo, no el total: dos personas en la misma
	 * franja grupal ocupan dos lugares, y una tercera en otra franja no suma.
	 *
	 * <p>Ejerce de paso el defecto que este cambio corrige en {@code RevalidadorDeSlot}: la segunda
	 * inscripcion a una franja grupal que exige espacio era rechazada con {@code recurso-ocupado}
	 * porque el box ya tenia "un turno que se cruza" —el de su propio grupo—.
	 */
	@Test
	@DisplayName("Bajar la capacidad por debajo del pico de turnos responde 409; hasta el pico, entra")
	void capacidad_debajo_del_pico_responde_409() {
		Sesion sesion = altaCompleta("sondas-pico");
		long espacioId = crearEspacio(sesion, "Gimnasio Sondas", "SALA_GRUPAL", 3);
		Fixture fixture = poblar(sesion, 3, true);

		TurnoView primero = reservar(fixture, fixture.personaA(), hora(sesion, 9));
		TurnoView segundo = reservar(fixture, fixture.personaB(), hora(sesion, 9));
		TurnoView otraFranja = reservar(fixture, fixture.personaC(), hora(sesion, 11));
		assertThat(segundo.espacioId())
				.as("la segunda persona del grupo comparte el box de la primera")
				.isEqualTo(primero.espacioId()).isEqualTo(espacioId);
		assertThat(otraFranja.espacioId()).isEqualTo(espacioId);

		Respuesta rechazada = patch(rutaEspacio(sesion, espacioId), sesion.token(),
				"{\"capacidad\":1,\"version\":0}");
		assertThat(rechazada.status()).as("reduccion a 1: %s", rechazada.body()).isEqualTo(409);
		assertThat(rechazada.texto("type")).isEqualTo(PROBLEMS + "espacio-capacity-below-occupancy");
		assertThat(rechazada.json().get("currentOccupancy").asLong())
				.as("pico, no total: 2 a las 9 y 1 a las 11").isEqualTo(2L);
		assertThat(capacidad(espacioId)).isEqualTo(3);

		Respuesta aceptada = patch(rutaEspacio(sesion, espacioId), sesion.token(),
				"{\"capacidad\":2,\"version\":0}");
		assertThat(aceptada.status()).as("reduccion al pico: %s", aceptada.body()).isEqualTo(200);
		assertThat(capacidad(espacioId)).isEqualTo(2);
	}

	// =================================================================================
	// Colaborador — ColaboradorDesvinculacionProbe, informa
	// =================================================================================

	@Test
	@DisplayName("La desvinculacion muestra los turnos pendientes del profesional, no la frena, y "
			+ "los deja en la auditoria")
	void desvinculacion_informa_los_turnos() {
		Sesion sesion = altaCompleta("sondas-colab");
		Fixture fixture = poblar(sesion, 1, false);
		String base = "/api/v1/organizations/" + sesion.organizationId()
				+ "/memberships/" + fixture.profesionalMembershipId();

		TurnoView temprano = reservar(fixture, fixture.personaA(), hora(sesion, 9));
		reservar(fixture, fixture.personaB(), hora(sesion, 11));
		TurnoView cancelado = reservar(fixture, fixture.personaC(), hora(sesion, 10));
		cancelar(fixture, cancelado);

		Respuesta impacto = get(base + "/desvinculacion-impacto", sesion.token());
		assertThat(impacto.status()).as("impacto: %s", impacto.body()).isEqualTo(200);
		assertThat(impacto.texto("tipo"))
				.as("los turnos van antes que los bloques de disponibilidad del profesional")
				.isEqualTo("turnos");
		assertThat(impacto.json().get("count").asLong()).as("el cancelado no cuenta").isEqualTo(2L);
		assertThat(Instant.parse(impacto.texto("desde"))).isEqualTo(temprano.inicio());

		Respuesta revocacion = post(base + "/revoke", sesion.token(),
				"{\"reason\":\"Renuncia sintetica\"}");
		assertThat(revocacion.status())
				.as("informa, no bloquea (RN-M05-004): %s", revocacion.body()).isEqualTo(200);

		String detalles = jdbc.queryForObject("""
				SELECT CAST(details AS CHAR) FROM audit_event
				 WHERE event_type = 'MEMBERSHIP_REVOKED' AND entity_id = ?
				 ORDER BY id DESC LIMIT 1
				""", String.class, fixture.profesionalMembershipId());
		assertThat(detalles).contains("\"pendienteTipo\": \"turnos\"")
				.contains("\"pendienteCount\": \"2\"");
	}

	// =================================================================================
	// Disponibilidad — DisponibilidadImpactProbe, informa
	// =================================================================================

	@Test
	@DisplayName("La baja de un bloque de disponibilidad informa los turnos pendientes que caian ahi")
	void baja_de_bloque_informa_los_turnos() {
		Sesion sesion = altaCompleta("sondas-dispo");
		Fixture fixture = poblar(sesion, 1, false);
		long bloqueId = jdbc.queryForObject(
				"SELECT id FROM profesional_disponibilidad WHERE membership_id = ?",
				Long.class, fixture.profesionalMembershipId());

		TurnoView pendiente = reservar(fixture, fixture.personaA(), hora(sesion, 9));
		TurnoView cancelado = reservar(fixture, fixture.personaB(), hora(sesion, 10));
		cancelar(fixture, cancelado);

		HttpRequest baja = HttpRequest.newBuilder(uri("/api/v1/consultorios/" + sesion.consultorioId()
						+ "/profesionales/" + fixture.profesionalMembershipId()
						+ "/disponibilidad/" + bloqueId))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + sesion.token())
				.method("DELETE", HttpRequest.BodyPublishers.ofString(
						"{\"reason\":\"Deja de atender los lunes\"}"))
				.build();
		Respuesta respuesta = ejecutar(baja);

		assertThat(respuesta.status()).as("informa, no bloquea: %s", respuesta.body()).isEqualTo(200);
		assertThat(respuesta.json().get("turnosAfectados").asLong())
				.as("el cancelado no cuenta").isEqualTo(1L);
		assertThat(Instant.parse(respuesta.texto("primerTurnoAfectado")))
				.isEqualTo(pendiente.inicio());
	}

	// =================================================================================
	// Tenant: ninguna sonda cuenta turnos de otra organizacion
	// =================================================================================

	@Test
	@DisplayName("Ninguna sonda cuenta los turnos de otra organizacion aunque se le pase un id ajeno")
	void otro_tenant_no_cuenta() {
		Fixture ajeno = fixtures.crear(1);
		Instant inicio = proximoLunes(ZoneId.of(AgendaFixtures.ZONA)).atTime(9, 0)
				.atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();
		reservar(ajeno, ajeno.personaA(), inicio);
		Fixture propio = fixtures.crear(1);

		Instant ahora = Instant.now();
		Instant horizonte = ahora.plus(Duration.ofDays(90));
		long orgPropia = propio.organizationId();

		// Control: con la organizacion correcta, la sonda SI ve el turno. Sin esto, un cero de
		// abajo podria venir de una consulta rota y no del predicado de tenant.
		assertThat(sondaSede.activeReferencesOn(
				ajeno.organizationId(), ajeno.consultorioId(), ahora).count()).isEqualTo(1L);

		assertThat(sondaSede.activeReferencesOn(orgPropia, ajeno.consultorioId(), ahora).bloquean())
				.isFalse();
		assertThat(sondaProfesional.pendingWorkOn(
				orgPropia, ajeno.profesionalMembershipId(), 0L, ahora).hayAlgo()).isFalse();
		assertThat(sondaDisponibilidad.turnosEn(orgPropia, ajeno.consultorioId(),
				ajeno.profesionalMembershipId(), ahora, horizonte).hayAlgo()).isFalse();
		long espacioCualquiera = 1L;
		assertThat(sondaEspacio.peakOccupancyFrom(orgPropia, espacioCualquiera, ahora).hayAlguna())
				.isFalse();
	}

	// =================================================================================
	// Ayudantes
	// =================================================================================

	private Fixture poblar(Sesion sesion, int capacidad, boolean requiereEspacio) {
		return fixtures.poblar(sesion.organizationId(), sesion.consultorioId(), sesion.cuentaId(),
				capacidad, requiereEspacio);
	}

	private TurnoView reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}

	private void cancelar(Fixture fixture, TurnoView turno) {
		cicloService.cancelar(fixture.actor(), fixture.consultorioId(), turno.id(),
				"El paciente aviso que no viene", turno.version());
	}

	/**
	 * Lleva el turno un mes atras y lo marca ausente. Es la unica forma de tener un turno pasado:
	 * la reserva no acepta fechas pasadas, y con razon.
	 */
	private void envejecerComoAusente(long turnoId) {
		int filas = jdbc.update("""
				UPDATE turno
				   SET inicio = DATE_SUB(inicio, INTERVAL 60 DAY),
				       fin = DATE_SUB(fin, INTERVAL 60 DAY),
				       estado = 'AUSENTE',
				       ausente_en = UTC_TIMESTAMP(6)
				 WHERE id = ?
				""", turnoId);
		assertThat(filas).isEqualTo(1);
	}

	/** El lunes de la semana que viene en la zona de la sede: siempre futuro, nunca una fecha fija. */
	private Instant hora(Sesion sesion, int horaLocal) {
		ZoneId zona = ZoneId.of(jdbc.queryForObject(
				"SELECT timezone FROM consultorio WHERE id = ?", String.class, sesion.consultorioId()));
		return proximoLunes(zona).atTime(horaLocal, 0).atZone(zona).toInstant();
	}

	private static LocalDate proximoLunes(ZoneId zona) {
		return LocalDate.now(zona).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
	}

	private void habilitarMasDeUnaSede(Sesion sesion) {
		Sesion plataforma = altaCompleta("plataforma");
		sembrarRolDePlataforma(plataforma.cuentaId());
		Respuesta cambio = post(
				"/api/v1/organizations/" + sesion.organizationId() + "/subscription/plan-changes",
				plataforma.token(),
				"{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":0}");
		assertThat(cambio.status()).as("plan sin tope de sedes: %s", cambio.body()).isEqualTo(200);
	}

	private Respuesta crearSede(Sesion sesion, String nombre) {
		return post(
				"/api/v1/organizations/" + sesion.organizationId() + "/consultorios",
				sesion.token(),
				"{\"name\":\"" + nombre + "\",\"timezone\":\"America/Argentina/Ushuaia\"}",
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
	}

	private String rutaEspacio(Sesion sesion, long espacioId) {
		return "/api/v1/organizations/" + sesion.organizationId()
				+ "/consultorios/" + sesion.consultorioId() + "/espacios/" + espacioId;
	}

	private long crearEspacio(Sesion sesion, String nombre, String tipo, int capacidad) {
		Respuesta alta = post("/api/v1/organizations/" + sesion.organizationId()
						+ "/consultorios/" + sesion.consultorioId() + "/espacios",
				sesion.token(),
				"{\"name\":\"" + nombre + "\",\"tipo\":\"" + tipo + "\",\"capacidad\":" + capacidad + "}");
		assertThat(alta.status()).as("alta del espacio: %s", alta.body()).isEqualTo(201);
		return alta.json().get("id").asLong();
	}

	private boolean activa(String tabla, long id) {
		Long activa = jdbc.queryForObject(
				"SELECT active FROM " + tabla + " WHERE id = ?", Long.class, id);
		return activa != null && activa == 1L;
	}

	private int capacidad(long espacioId) {
		return jdbc.queryForObject("SELECT capacidad FROM espacio WHERE id = ?", Integer.class, espacioId);
	}
}
