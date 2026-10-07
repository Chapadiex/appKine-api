package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.ElegibilidadAdministrativaDirectory;
import com.akine.person.spi.ReferenciaCongelada;
import com.akine.person.spi.VeredictoDeElegibilidad;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaDelDiaView;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.CicloDeRecepcionService.ResultadoDeLlegada;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.EventoDeRecepcionView;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.RecepcionView;
import com.akine.scheduling.application.ReprogramacionCommand;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoDelDiaView;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.exception.RecepcionNotAccessibleException;
import com.akine.scheduling.domain.exception.TransicionDeRecepcionNoPermitidaException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Recepcion con maquina de estados propia (M13, AKINE E-4, DP-16) contra MySQL real.
 *
 * <p>Lo que solo la base puede decir: los CHECK de {@code V78} (observada con observacion,
 * particular con motivo, cerrada con hora de cierre), el unique de una sola recepcion vigente por
 * turno, el {@code FOR UPDATE} y la version forzada del turno, y que el turno no cambie de estado.
 *
 * <p>Los tres {@code spi} de otros modulos que la validacion consulta —practica de la oferta,
 * coberturas aplicables y elegibilidad— son dobles: lo que se prueba aca es la recepcion, y lo que
 * decide un convenio o una cobertura ya tiene su IT en su modulo ({@code OfertaPracticaIT},
 * {@code CoberturasAplicablesDirectoryIT}). Sin configurar, responden "sin practica" y "sin
 * coberturas", que es el camino que lleva a Particular.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class RecepcionIT {

	/** Ver el javadoc de la constante homonima en {@code TurnoConcurrenteIT}. */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);

	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);
	private static final long PRACTICA = 33L;

	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private CicloDeRecepcionService recepcion;
	@Autowired private RecepcionService agendaDelDia;
	@Autowired private SesionService sesiones;
	@Autowired private JdbcTemplate jdbc;

	@MockitoBean private PracticasDeOfertaDirectory practicas;
	@MockitoBean private CoberturasAplicablesDirectory coberturas;
	@MockitoBean private ElegibilidadAdministrativaDirectory elegibilidad;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	// =================================================================================
	// Llegada
	// =================================================================================

	@Test
	@DisplayName("el check-in abre la recepcion en LLEGO con la hora del servidor, sin tocar el estado del turno")
	void el_check_in_abre_la_recepcion() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));

		Instant antes = Instant.now();
		ResultadoDeLlegada llegada = recepcion.registrarLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id());
		Instant despues = Instant.now();

		assertThat(llegada.creada()).isTrue();
		assertThat(llegada.recepcion().estado()).isEqualTo("LLEGO");
		assertThat(llegada.recepcion().llegadaEn())
				.as("la hora la pone el servidor")
				.isBetween(antes, despues);

		Map<String, Object> filaTurno = jdbc.queryForMap(
				"SELECT estado, version FROM turno WHERE id = ?", turno.id());
		assertThat(filaTurno.get("estado"))
				.as("DP-16: el turno es la reserva y no pasa a ningun estado de espera")
				.isEqualTo("RESERVADO");
		assertThat(((Number) filaTurno.get("version")).longValue())
				.as("la version del turno avanza UNA vez: es lo que hace perder a una cancelacion "
						+ "concurrente que leyo el turno antes de la llegada")
				.isEqualTo(turno.version() + 1);
		assertThat(llegada.turno().version())
				.as("y la respuesta ya trae la version nueva")
				.isEqualTo(turno.version() + 1);

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT estado, llegada_por_cuenta_id, organization_id FROM recepcion WHERE turno_id = ?",
				turno.id());
		assertThat(fila.get("estado")).isEqualTo("LLEGO");
		assertThat(((Number) fila.get("llegada_por_cuenta_id")).longValue())
				.isEqualTo(fixture.actor().accountId());
		assertThat(((Number) fila.get("organization_id")).longValue()).isEqualTo(fixture.organizationId());

		assertThat(historial(fixture, turno.id())).extracting(EventoDeRecepcionView::tipo)
				.containsExactly("LLEGADA");
	}

	@Test
	@DisplayName("marcar la llegada dos veces devuelve la misma recepcion sin mover la hora ni la version")
	void el_check_in_es_idempotente() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));

		ResultadoDeLlegada primero = recepcion.registrarLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id());
		Object horaGuardada = horaDeLlegadaGuardada(turno.id());
		long versionTurno = versionDelTurno(turno.id());

		ResultadoDeLlegada segundo = recepcion.registrarLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id());

		assertThat(segundo.creada()).isFalse();
		assertThat(segundo.recepcion().id()).isEqualTo(primero.recepcion().id());
		// Lo GUARDADO, no las respuestas: MySQL redondea DATETIME(6) (registro de 05.04).
		assertThat(horaDeLlegadaGuardada(turno.id())).isEqualTo(horaGuardada);
		assertThat(versionDelTurno(turno.id()))
				.as("el segundo click no fuerza otra version: no cambio nada")
				.isEqualTo(versionTurno);
		assertThat(historial(fixture, turno.id())).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recepcion WHERE turno_id = ?",
				Integer.class, turno.id())).isEqualTo(1);
	}

	// =================================================================================
	// Validacion y camino Particular
	// =================================================================================

	@Test
	@DisplayName("sin practica: OBSERVADA; Particular con motivo; espera y llamado, con un evento por transicion")
	void camino_particular_completo() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		RecepcionView abierta = llegar(fixture, turno);

		RecepcionView observada = recepcion.validar(
				fixture.actor(), fixture.consultorioId(), turno.id(), null, abierta.version());
		assertThat(observada.estado()).isEqualTo("OBSERVADA");
		assertThat(observada.observacion()).startsWith("OFERTA_SIN_PRACTICA");
		assertThat(observada.modalidad()).isNull();

		RecepcionView particular = recepcion.atenderComoParticular(
				fixture.actor(), fixture.consultorioId(), turno.id(),
				"La oferta no tiene convenio; el paciente abona", observada.version());
		assertThat(particular.estado()).isEqualTo("VALIDADA");
		assertThat(particular.modalidad()).isEqualTo("PARTICULAR");
		assertThat(particular.motivoParticular()).isEqualTo("La oferta no tiene convenio; el paciente abona");
		assertThat(particular.observacion())
				.as("la observacion se conserva: es la razon de la decision")
				.startsWith("OFERTA_SIN_PRACTICA");

		RecepcionView enEspera = recepcion.pasarAEspera(
				fixture.actor(), fixture.consultorioId(), turno.id(), particular.version());
		assertThat(enEspera.estado()).isEqualTo("EN_ESPERA");
		assertThat(enEspera.enEsperaDesde()).isNotNull();

		RecepcionView llamada = recepcion.llamar(
				fixture.actor(), fixture.consultorioId(), turno.id(), enEspera.version());
		assertThat(llamada.estado()).isEqualTo("LLAMADA");
		assertThat(llamada.llamadaEn()).isNotNull();

		List<EventoDeRecepcionView> eventos = historial(fixture, turno.id());
		assertThat(eventos).extracting(EventoDeRecepcionView::tipo)
				.containsExactly("LLEGADA", "VALIDACION", "PARTICULAR", "ESPERA", "LLAMADO");
		assertThat(eventos).extracting(EventoDeRecepcionView::estadoAnterior)
				.containsExactly(null, "LLEGO", "OBSERVADA", "VALIDADA", "EN_ESPERA");
		assertThat(eventos).extracting(EventoDeRecepcionView::actorCuentaId)
				.containsOnly(fixture.actor().accountId());
		assertThat(eventos.get(2).motivo()).isEqualTo("La oferta no tiene convenio; el paciente abona");

		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?", String.class, turno.id()))
				.as("nada de la recepcion mueve el turno")
				.isEqualTo("RESERVADO");
		assertThat(jdbc.queryForMap(
				"SELECT modalidad, motivo_particular FROM recepcion WHERE turno_id = ?", turno.id()))
				.containsEntry("modalidad", "PARTICULAR")
				.containsEntry("motivo_particular", "La oferta no tiene convenio; el paciente abona");
	}

	@Test
	@DisplayName("cobertura aplicable y elegible: VALIDADA con el snapshot de practica, cobertura y convenio")
	void validada_con_cobertura() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		conPractica(fixture);
		conCobertura(fixture, 412L);
		given(elegibilidad.evaluar(eq(fixture.organizationId()), eq(fixture.consultorioId()),
				eq(fixture.personaA()), eq(412L), eq(PRACTICA), eq(LUNES)))
				.willReturn(new VeredictoDeElegibilidad(true, null, 9L, List.of()));
		RecepcionView abierta = llegar(fixture, turno);

		RecepcionView validada = recepcion.validar(
				fixture.actor(), fixture.consultorioId(), turno.id(), null, abierta.version());

		assertThat(validada.estado()).isEqualTo("VALIDADA");
		assertThat(validada.modalidad()).isEqualTo("COBERTURA");
		assertThat(jdbc.queryForMap(
				"SELECT practica_id, cobertura_id, convenio_id, observacion FROM recepcion WHERE turno_id = ?",
				turno.id()))
				.containsEntry("practica_id", PRACTICA)
				.containsEntry("cobertura_id", 412L)
				.containsEntry("convenio_id", 9L)
				.containsEntry("observacion", null);
	}

	@Test
	@DisplayName("documentacion incompleta: OBSERVADA con el detalle; revalidar cuando trae la orden la deja VALIDADA")
	void observada_y_revalidada() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		conPractica(fixture);
		conCobertura(fixture, 412L);
		given(elegibilidad.evaluar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), eq(LUNES)))
				.willReturn(new VeredictoDeElegibilidad(false, null, 9L,
						List.of("ORDEN: El convenio exige orden medica.")));
		RecepcionView abierta = llegar(fixture, turno);

		RecepcionView observada = recepcion.validar(
				fixture.actor(), fixture.consultorioId(), turno.id(), null, abierta.version());
		assertThat(observada.estado()).isEqualTo("OBSERVADA");
		assertThat(observada.observacion())
				.isEqualTo("DOCUMENTACION_INCOMPLETA: ORDEN: El convenio exige orden medica.");

		given(elegibilidad.evaluar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), eq(LUNES)))
				.willReturn(new VeredictoDeElegibilidad(true, null, 9L, List.of()));
		RecepcionView revalidada = recepcion.validar(
				fixture.actor(), fixture.consultorioId(), turno.id(), null, observada.version());

		assertThat(revalidada.estado()).isEqualTo("VALIDADA");
		assertThat(revalidada.observacion()).isNull();
		assertThat(historial(fixture, turno.id())).extracting(EventoDeRecepcionView::estadoNuevo)
				.containsExactly("LLEGO", "OBSERVADA", "VALIDADA");
	}

	@Test
	@DisplayName("una observada pasa a espera sin resolverse: la observacion advierte, no bloquea")
	void la_observacion_no_bloquea() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		conPractica(fixture);
		RecepcionView abierta = llegar(fixture, turno);

		assertThatThrownBy(() -> recepcion.pasarAEspera(
				fixture.actor(), fixture.consultorioId(), turno.id(), abierta.version()))
				.as("desde LLEGO no: falta resolver como se atiende")
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);

		RecepcionView observada = recepcion.validar(
				fixture.actor(), fixture.consultorioId(), turno.id(), null, abierta.version());
		assertThat(observada.observacion()).startsWith("SIN_COBERTURA_APLICABLE");

		RecepcionView enEspera = recepcion.pasarAEspera(
				fixture.actor(), fixture.consultorioId(), turno.id(), observada.version());
		assertThat(enEspera.estado()).isEqualTo("EN_ESPERA");
		assertThat(enEspera.observacion()).startsWith("SIN_COBERTURA_APLICABLE");
		assertThat(enEspera.modalidad()).isNull();
	}

	@Test
	@DisplayName("una version vieja de la recepcion es 409 y no pisa la transicion de otro")
	void version_vieja() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		RecepcionView abierta = llegar(fixture, turno);
		recepcion.atenderComoParticular(fixture.actor(), fixture.consultorioId(), turno.id(),
				"Paga en el mostrador", abierta.version());

		assertThatThrownBy(() -> recepcion.atenderComoParticular(fixture.actor(),
				fixture.consultorioId(), turno.id(), "Otro motivo", abierta.version()))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	// =================================================================================
	// Anulacion y cancelacion del turno
	// =================================================================================

	@Test
	@DisplayName("anular deja la fila ANULADA, un check-in posterior abre otra, y anular sin abierta es 409")
	void anular_y_volver_a_llegar() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		RecepcionView primera = llegar(fixture, turno);

		RecepcionView anulada = recepcion.anular(fixture.actor(), fixture.consultorioId(), turno.id(),
				"Turno equivocado", primera.version());
		assertThat(anulada.estado()).isEqualTo("ANULADA");
		assertThat(anulada.cerradaEn()).isNotNull();

		assertThatThrownBy(() -> recepcion.anular(fixture.actor(), fixture.consultorioId(),
				turno.id(), null, anulada.version()))
				.as("anular lo ya anulado es 409, no 200 en silencio")
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);
		assertThatThrownBy(() -> recepcion.ver(fixture.actor(), fixture.consultorioId(), turno.id()))
				.as("una anulada no es vigente")
				.isInstanceOf(RecepcionNotAccessibleException.class);

		RecepcionView segunda = llegar(fixture, turno);
		assertThat(segunda.id()).isNotEqualTo(primera.id());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recepcion WHERE turno_id = ?",
				Integer.class, turno.id())).isEqualTo(2);
		assertThat(historial(fixture, turno.id())).extracting(EventoDeRecepcionView::tipo)
				.containsExactly("LLEGADA", "ANULACION", "LLEGADA");
	}

	@Test
	@DisplayName("cancelar el turno con la recepcion abierta la CIERRA conservando la llegada")
	void la_cancelacion_cierra_la_recepcion() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		RecepcionView abierta = llegar(fixture, turno);
		recepcion.atenderComoParticular(fixture.actor(), fixture.consultorioId(), turno.id(),
				"Paga en el mostrador", abierta.version());

		TurnoView cancelado = cicloService.cancelar(fixture.actor(), fixture.consultorioId(),
				turno.id(), "El profesional se descompuso", versionDelTurno(turno.id()));

		assertThat(cancelado.estado()).isEqualTo("CANCELADO");
		RecepcionView cerrada = recepcion.ver(fixture.actor(), fixture.consultorioId(), turno.id());
		assertThat(cerrada.estado()).isEqualTo("CERRADA");
		assertThat(cerrada.llegadaEn()).as("la llegada consta: el paciente vino").isNotNull();
		assertThat(cerrada.motivoCierre()).isEqualTo("El profesional se descompuso");
		assertThat(historial(fixture, turno.id())).extracting(EventoDeRecepcionView::tipo)
				.containsExactly("LLEGADA", "PARTICULAR", "CIERRE_POR_CANCELACION");

		assertThatThrownBy(() -> recepcion.registrarLlegada(
				fixture.actor(), fixture.consultorioId(), turno.id()))
				.as("sobre un turno cancelado no hay a que llegar")
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
	}

	@Test
	@DisplayName("con la recepcion abierta el turno no se reprograma ni se marca ausente; anulada, si")
	void la_recepcion_abierta_bloquea_mover_y_ausencia() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		RecepcionView abierta = llegar(fixture, turno);

		assertThatThrownBy(() -> cicloService.reprogramar(fixture.actor(), fixture.consultorioId(),
				turno.id(), new ReprogramacionCommand(hora(10), fixture.profesionalMembershipId(), "mover",
						versionDelTurno(turno.id()))))
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class)
				.hasMessageContaining("recepcion abierta");
		assertThatThrownBy(() -> cicloService.marcarAusente(fixture.actor(), fixture.consultorioId(),
				turno.id(), null, versionDelTurno(turno.id())))
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class)
				.hasMessageContaining("recepcion abierta");

		recepcion.anular(fixture.actor(), fixture.consultorioId(), turno.id(), null, abierta.version());
		TurnoView movido = cicloService.reprogramar(fixture.actor(), fixture.consultorioId(),
				turno.id(), new ReprogramacionCommand(hora(10), fixture.profesionalMembershipId(), "mover",
						versionDelTurno(turno.id())));
		assertThat(movido.inicio()).isEqualTo(hora(10));
	}

	// =================================================================================
	// Agenda del dia, tenant y Sesion
	// =================================================================================

	@Test
	@DisplayName("la agenda del dia trae la recepcion de cada turno y la hora de llegada desde ella")
	void la_agenda_del_dia_trae_la_recepcion() {
		Fixture fixture = fixtures.crear(1);
		TurnoView deLasNueve = reservar(fixture, fixture.personaA(), hora(9));
		TurnoView deLasDiez = reservar(fixture, fixture.personaB(), hora(10));
		llegar(fixture, deLasNueve);

		AgendaDelDiaView delDia = agendaDelDia.delDia(fixture.actor(), fixture.consultorioId(), LUNES);
		List<TurnoDelDiaView> agenda = delDia.turnos();

		assertThat(agenda).extracting(TurnoDelDiaView::id).containsExactly(deLasNueve.id(), deLasDiez.id());
		TurnoDelDiaView primero = agenda.get(0);
		assertThat(primero.estado()).isEqualTo("RESERVADO");
		assertThat(primero.recepcion()).isNotNull();
		assertThat(primero.recepcion().estado()).isEqualTo("LLEGO");
		assertThat(primero.llegadaEn()).isEqualTo(primero.recepcion().llegadaEn());
		assertThat(agenda.get(1).recepcion()).isNull();
		assertThat(agenda.get(1).llegadaEn()).isNull();
	}

	@Test
	@DisplayName("un turno de otro tenant es 404 para la recepcion; uno propio sin llegada, 404 de recepcion")
	void tenant_ajeno() {
		Fixture propio = fixtures.crear(1);
		Fixture ajeno = fixtures.crear(1);
		TurnoView turnoAjeno = reservar(ajeno, ajeno.personaA(), hora(9));
		TurnoView turnoPropio = reservar(propio, propio.personaA(), hora(9));

		// El actor propio apuntando a su sede con el id del turno ajeno.
		assertThatThrownBy(() -> recepcion.registrarLlegada(
				propio.actor(), propio.consultorioId(), turnoAjeno.id()))
				.isInstanceOf(TurnoNotAccessibleException.class);
		assertThatThrownBy(() -> recepcion.historial(
				propio.actor(), propio.consultorioId(), turnoAjeno.id()))
				.isInstanceOf(TurnoNotAccessibleException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recepcion WHERE turno_id = ?",
				Integer.class, turnoAjeno.id())).isZero();

		assertThatThrownBy(() -> recepcion.ver(propio.actor(), propio.consultorioId(), turnoPropio.id()))
				.isInstanceOf(RecepcionNotAccessibleException.class);
	}

	@Test
	@DisplayName("la Sesion arranca desde el turno con la recepcion llamada, y no la mueve (DP-05)")
	void la_sesion_arranca_con_la_recepcion_llamada() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		RecepcionView abierta = llegar(fixture, turno);
		RecepcionView particular = recepcion.atenderComoParticular(fixture.actor(),
				fixture.consultorioId(), turno.id(), "Paga en el mostrador", abierta.version());
		RecepcionView enEspera = recepcion.pasarAEspera(
				fixture.actor(), fixture.consultorioId(), turno.id(), particular.version());
		RecepcionView llamada = recepcion.llamar(
				fixture.actor(), fixture.consultorioId(), turno.id(), enEspera.version());

		long cuentaProfesional = jdbc.queryForObject(
				"SELECT account_id FROM membership WHERE id = ?", Long.class,
				fixture.profesionalMembershipId());
		SesionView sesion = sesiones.iniciar(
				new com.akine.encounter.application.OperatingActor(
						cuentaProfesional, false, fixture.organizationId(), fixture.consultorioId()),
				fixture.consultorioId(), turno.id(), null);

		assertThat(sesion.id()).isPositive();
		assertThat(recepcion.ver(fixture.actor(), fixture.consultorioId(), turno.id()).version())
				.as("abrir la Sesion no toca la recepcion: son dos maquinas")
				.isEqualTo(llamada.version());
	}

	/**
	 * El historial del turno es append-only y conserva eventos de 05.04 con {@code EN_ESPERA}, un
	 * estado que el turno ya no tiene. Si {@code TurnoEvento} lo siguiera mapeando con el enum,
	 * leer el historial de cualquier turno que paso por la espera antes de V78 reventaria.
	 */
	@Test
	@DisplayName("el historial del turno sigue leyendo los eventos viejos de EN_ESPERA")
	void el_historial_conserva_los_eventos_de_espera() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));
		// Las horas se derivan de la del evento de reserva, no del reloj de la base: el orden del
		// historial es por ocurrido_en y el reloj del contenedor no tiene por que coincidir con el
		// de la JVM que escribio la reserva.
		for (String[] evento : new String[][] {
				{"LLEGADA", "RESERVADO", "EN_ESPERA", "1"},
				{"LLEGADA_DESHECHA", "EN_ESPERA", "RESERVADO", "2"}}) {
			jdbc.update("""
					INSERT INTO turno_evento (organization_id, consultorio_id, turno_id, tipo,
					                          estado_anterior, estado_nuevo, actor_cuenta_id, ocurrido_en)
					SELECT organization_id, consultorio_id, turno_id, ?, ?, ?, actor_cuenta_id,
					       ocurrido_en + INTERVAL ? MINUTE
					  FROM turno_evento
					 WHERE turno_id = ? AND tipo = 'RESERVA'
					""", evento[0], evento[1], evento[2], Integer.parseInt(evento[3]), turno.id());
		}

		assertThat(cicloService.historial(fixture.actor(), fixture.consultorioId(), turno.id()))
				.extracting(com.akine.scheduling.application.EventoDeTurnoView::estadoNuevo)
				.containsExactly("RESERVADO", "EN_ESPERA", "RESERVADO");
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private RecepcionView llegar(Fixture fixture, TurnoView turno) {
		return recepcion.registrarLlegada(fixture.actor(), fixture.consultorioId(), turno.id()).recepcion();
	}

	private List<EventoDeRecepcionView> historial(Fixture fixture, long turnoId) {
		return recepcion.historial(fixture.actor(), fixture.consultorioId(), turnoId);
	}

	private void conPractica(Fixture fixture) {
		given(practicas.practicaPrincipal(fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId()))
				.willReturn(Optional.of(PRACTICA));
	}

	private void conCobertura(Fixture fixture, long coberturaId) {
		given(coberturas.aplicables(fixture.organizationId(), fixture.consultorioId(),
				fixture.personaA(), PRACTICA, fixture.ofertaId(), LUNES))
				.willReturn(List.of(new CoberturaAplicable(coberturaId, true,
						new ReferenciaCongelada(1L, "Financiador Sintetico", 2L, "Plan Sintetico"),
						null, false, null)));
	}

	private Object horaDeLlegadaGuardada(long turnoId) {
		return jdbc.queryForMap("SELECT llegada_en FROM recepcion WHERE turno_id = ?", turnoId)
				.get("llegada_en");
	}

	private long versionDelTurno(long turnoId) {
		return jdbc.queryForObject("SELECT version FROM turno WHERE id = ?", Long.class, turnoId);
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
