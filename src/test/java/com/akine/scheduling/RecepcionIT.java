package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.EventoDeTurnoView;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoDelDiaView;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recepcion y check-in (M13, AKINE-05.04 reducida) contra MySQL real.
 *
 * <h2>Por que estos escenarios necesitan una base y no dobles</h2>
 *
 * <p>Tres de los cuatro dependen de cosas que <b>solo existen en la base</b>:
 *
 * <ul>
 *   <li>el {@code CHECK} de {@code V39}, que exige que un turno EN_ESPERA tenga hora de llegada y
 *       que uno que no lo esta no la tenga. Un doble del repositorio acepta cualquier combinacion,
 *       incluida la que la base rechaza;</li>
 *   <li>el recorte del dia en la <b>zona de la sede</b>: que un turno de las 09:00 locales caiga
 *       dentro del dia pedido depende de la conversion real que hace la consulta, y esa conversion
 *       es la unica parte del calculo que no esta en Java;</li>
 *   <li>que el listado <b>incluya los cancelados</b>, que es una decision de la consulta JPQL —no
 *       lleva el filtro de baja logica que llevan todas las demas— y no del servicio.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class RecepcionIT {

	/** Ver el javadoc de la constante homonima en {@code TurnoConcurrenteIT}. */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);

	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private RecepcionService recepcionService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("el check-in deja el turno en espera con la hora del servidor, y la fila lo refleja")
	void el_check_in_registra_la_llegada() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));

		Instant antes = Instant.now();
		TurnoView enEspera = cicloService.registrarLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id());
		Instant despues = Instant.now();

		assertThat(enEspera.estado()).isEqualTo("EN_ESPERA");
		assertThat(enEspera.llegadaEn())
				.as("la hora la pone el servidor: tiene que caer dentro de la ventana de la llamada")
				.isBetween(antes, despues);

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT estado, llegada_en, llegada_por_cuenta_id, estado_antes_de_espera "
						+ "FROM turno WHERE id = ?", turno.id());
		assertThat(fila.get("estado")).isEqualTo("EN_ESPERA");
		assertThat(fila.get("llegada_en")).isNotNull();
		assertThat(fila.get("llegada_por_cuenta_id"))
				.as("siempre hay un responsable: la llegada es evidencia administrativa")
				.isNotNull();
		assertThat(fila.get("estado_antes_de_espera"))
				.as("se guarda de donde vino para poder deshacer sin inventar una confirmacion")
				.isEqualTo("RESERVADO");

		List<EventoDeTurnoView> historial = cicloService.historial(
				fixture.actor(), fixture.consultorioId(), turno.id());
		assertThat(historial).extracting(EventoDeTurnoView::tipo)
				.containsExactly("RESERVA", "LLEGADA");
	}

	/**
	 * El doble click en el mostrador es el caso normal, no un error.
	 *
	 * <p>Lo que se afirma no es solo el codigo de respuesta: la hora <b>no se mueve</b> y no
	 * aparece un segundo evento. Un check-in que reescribe la hora en cada click convertiria la
	 * evidencia administrativa en "la ultima vez que alguien apreto el boton".
	 */
	@Test
	@DisplayName("marcar la llegada dos veces no mueve la hora ni duplica el evento")
	void el_check_in_es_idempotente() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));

		cicloService.registrarLlegada(fixture.actor(), fixture.consultorioId(), turno.id());
		Object guardadaTrasElPrimero = horaGuardada(turno.id());

		cicloService.registrarLlegada(fixture.actor(), fixture.consultorioId(), turno.id());

		// Se compara lo GUARDADO antes y despues del segundo click, no las dos respuestas.
		//
		// No es un rodeo: la respuesta del primer check-in sale de la entidad en memoria, con la
		// precision de nanosegundos de Instant.now(), y la del segundo sale de la fila, que MySQL
		// guardo en DATETIME(6) REDONDEANDO a microsegundos. Las dos difieren en digitos que la
		// base nunca guardo, asi que compararlas mediria la precision del reloj y no la
		// idempotencia. Lo que la etapa promete es que la hora almacenada no se mueve.
		assertThat(horaGuardada(turno.id()))
				.as("el segundo click no toca la hora guardada")
				.isEqualTo(guardadaTrasElPrimero);
		assertThat(cicloService.historial(fixture.actor(), fixture.consultorioId(), turno.id()))
				.extracting(EventoDeTurnoView::tipo)
				.as("el segundo click no registra nada")
				.containsExactly("RESERVA", "LLEGADA");
	}

	/**
	 * Deshacer limpia la hora, y el CHECK de V39 es lo que lo vuelve obligatorio.
	 *
	 * <p>Si el codigo dejara la hora puesta al revertir, la fila diria "no esta en espera pero
	 * llego a las 09:12" y la base la rechazaria. Es el tipo de invariante que un doble no puede
	 * sostener.
	 */
	@Test
	@DisplayName("deshacer el check-in vuelve al estado anterior y borra la hora de llegada")
	void deshacer_el_check_in_limpia_la_llegada() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		TurnoView confirmado = turnoService.confirmar(
				fixture.actor(), fixture.consultorioId(), turno.id());
		assertThat(confirmado.estado()).isEqualTo("CONFIRMADO");

		cicloService.registrarLlegada(fixture.actor(), fixture.consultorioId(), turno.id());
		TurnoView revertido = cicloService.deshacerLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id());

		assertThat(revertido.estado())
				.as("vuelve a CONFIRMADO y no a RESERVADO: se confirmo de verdad antes del check-in")
				.isEqualTo("CONFIRMADO");
		assertThat(revertido.llegadaEn()).isNull();

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT llegada_en, estado_antes_de_espera FROM turno WHERE id = ?", turno.id());
		assertThat(fila.get("llegada_en")).isNull();
		assertThat(fila.get("estado_antes_de_espera")).isNull();

		// El rastro de que ocurrio queda en el historial, que es lo unico append-only.
		assertThat(cicloService.historial(fixture.actor(), fixture.consultorioId(), turno.id()))
				.extracting(EventoDeTurnoView::tipo)
				.containsExactly("RESERVA", "CONFIRMACION", "LLEGADA", "LLEGADA_DESHECHA");

		assertThatThrownBy(() -> cicloService.deshacerLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id()))
				.as("deshacer lo ya deshecho es 409, no 200 en silencio")
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
	}

	/**
	 * La agenda del dia, que es la pantalla entera de la recepcion.
	 *
	 * <p>Afirma las dos decisiones que no son obvias: que los <b>cancelados vienen igual</b> —con
	 * su motivo— y que el paciente llega <b>resuelto con nombre</b>, que es lo que distingue esta
	 * lectura de la de un turno suelto.
	 */
	@Test
	@DisplayName("la agenda del dia trae los turnos con el paciente resuelto, cancelados incluidos")
	void la_agenda_del_dia_incluye_los_cancelados() {
		Fixture fixture = fixtures.crear(1);
		TurnoView deLasNueve = reservar(fixture, fixture.personaA(), hora(9));
		TurnoView deLasDiez = reservar(fixture, fixture.personaB(), hora(10));

		cicloService.cancelar(fixture.actor(), fixture.consultorioId(), deLasDiez.id(),
				"El profesional se enfermo", deLasDiez.version());
		cicloService.registrarLlegada(fixture.actor(), fixture.consultorioId(), deLasNueve.id());

		List<TurnoDelDiaView> agenda = recepcionService.delDia(
				fixture.actor(), fixture.consultorioId(), LUNES);

		assertThat(agenda)
				.as("los dos turnos del dia, el cancelado incluido")
				.hasSize(2);
		assertThat(agenda).extracting(TurnoDelDiaView::id)
				.as("ordenados por hora")
				.containsExactly(deLasNueve.id(), deLasDiez.id());

		TurnoDelDiaView primero = agenda.get(0);
		assertThat(primero.estado()).isEqualTo("EN_ESPERA");
		assertThat(primero.llegadaEn()).isNotNull();
		assertThat(primero.personaNombre())
				.as("el nombre viaja resuelto: una lista con 'persona #5' no sirve para llamar a nadie")
				.isNotBlank()
				.doesNotContain("#");
		assertThat(primero.ofertaNombre()).isNotBlank();

		TurnoDelDiaView segundo = agenda.get(1);
		assertThat(segundo.estado()).isEqualTo("CANCELADO");
		assertThat(segundo.motivoCancelacion())
				.as("con su motivo: es lo que hace util verlos")
				.isEqualTo("El profesional se enfermo");

		// Un dia sin turnos no es un error, es una lista vacia.
		assertThat(recepcionService.delDia(
				fixture.actor(), fixture.consultorioId(), LUNES.plusDays(1)))
				.isEmpty();
	}

	/**
	 * Un paciente que llego y al que el centro no va a poder atender tiene que poder cancelarse.
	 *
	 * <p>Obligar a deshacer antes el check-in seria peor que permitirlo: borraria la evidencia de
	 * que la persona vino, que es justamente lo que la recepcion existe para registrar.
	 */
	@Test
	@DisplayName("un turno EN_ESPERA se puede cancelar sin deshacer antes la llegada")
	void el_paciente_que_llego_y_no_se_pudo_atender() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		TurnoView enEspera = cicloService.registrarLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id());

		TurnoView cancelado = cicloService.cancelar(
				fixture.actor(), fixture.consultorioId(), turno.id(),
				"El profesional se descompuso", enEspera.version());

		assertThat(cancelado.estado()).isEqualTo("CANCELADO");
		assertThat(jdbc.queryForMap("SELECT llegada_en FROM turno WHERE id = ?", turno.id())
				.get("llegada_en"))
				.as("la llegada NO se borra al cancelar: consta que el paciente vino")
				.isNotNull();
	}

	/** La hora de llegada tal como quedo en la fila, que es la unica que el sistema promete. */
	private Object horaGuardada(long turnoId) {
		return jdbc.queryForMap("SELECT llegada_en FROM turno WHERE id = ?", turnoId)
				.get("llegada_en");
	}

	private TurnoView reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}

	private static Instant hora(int hora) {
		return LUNES.atTime(hora, 0).atZone(ZONA).toInstant();
	}
}
