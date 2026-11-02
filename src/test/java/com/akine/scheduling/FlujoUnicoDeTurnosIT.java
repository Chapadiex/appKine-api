package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.ItemAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.offering.application.OfertaHabilitacionService;
import com.akine.resource.application.ExcepcionAltaCommand;
import com.akine.resource.application.ExcepcionService;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.BloqueoConTurnosAfectadosException;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaOperativaService;
import com.akine.scheduling.application.AltaDeSerieCommand;
import com.akine.scheduling.application.AltaEnListaDeEsperaCommand;
import com.akine.scheduling.application.BusquedaDeDisponibilidad;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.ContextoDeAgendaService;
import com.akine.scheduling.application.DisponibilidadDeTurnosService;
import com.akine.scheduling.application.DisponibilidadView;
import com.akine.scheduling.application.EntradaDeEsperaView;
import com.akine.scheduling.application.ListaDeEsperaService;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.PrevisualizacionDeSerieService;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ReprogramacionCommand;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.SerieDeTurnosService;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.application.ValidacionDeHorarioCommand;
import com.akine.scheduling.domain.EstadoListaDeEspera;
import com.akine.scheduling.domain.OrigenDeCancelacion;
import com.akine.scheduling.domain.OrigenDeTurno;
import com.akine.scheduling.domain.PrioridadListaDeEspera;
import com.akine.scheduling.domain.ReglaDeRecurrencia;
import com.akine.scheduling.domain.exception.ContextoClinicoInvalidoException;
import com.akine.scheduling.domain.exception.EntradaDeEsperaCerradaException;
import com.akine.scheduling.domain.exception.PlanSinSesionesException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * El flujo unico de turnos contra MySQL real (TURNO.md, TURNO - Tecnico). Lo que importa aca es lo que
 * un doble no puede probar: que lo que el motor ofrece es lo que la reserva acepta, con turnos de
 * verdad en la base.
 *
 * <p>El contexto clinico va con un doble porque sembrar un caso con su plan por SQL son seis tablas de
 * otro modulo; lo real se recorre de punta a punta por la pantalla.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class FlujoUnicoDeTurnosIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);
	private static final long PLAN_ID = 57L;
	private static final long CASO_ID = 9L;

	@Autowired private TurnoService turnos;
	@Autowired private CicloDeTurnoService ciclo;
	@Autowired private DisponibilidadDeTurnosService disponibilidad;
	@Autowired private RecepcionService recepcion;
	@Autowired private SerieDeTurnosService series;
	@Autowired private PrevisualizacionDeSerieService previsualizacion;
	@Autowired private ListaDeEsperaService listaDeEspera;
	@Autowired private AgendaOperativaService agendaOperativa;
	@Autowired private ContextoDeAgendaService contextoDeAgenda;
	@Autowired private ExcepcionService excepciones;
	@Autowired private OfertaHabilitacionService habilitaciones;
	@Autowired private JdbcTemplate jdbc;

	@MockitoBean private ContextoClinicoDeAgenda contexto;

	private AgendaFixtures fixtures;
	private Fixture fixture;

	@BeforeEach
	void setUp() {
		fixtures = new AgendaFixtures(jdbc);
		fixture = fixtures.crear(1);
	}

	@Test
	@DisplayName("dos profesionales son dos lugares: el primer turno de las 09:00 no le quita el horario al otro")
	void cupo_por_profesional() {
		long otro = fixtures.agregarProfesional(fixture);

		reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());

		assertThat(disponibilidad.buscar(fixture.actor(), fixture.consultorioId(), busqueda(null, 50)).horarios())
				.as("el otro profesional sigue libre a las 09:00")
				.anyMatch(horario -> horario.desde().equals(hora(9)) && Long.valueOf(otro).equals(horario.profesionalId()))
				.noneMatch(horario -> horario.desde().equals(hora(9))
						&& Long.valueOf(fixture.profesionalMembershipId()).equals(horario.profesionalId()));
		assertThat(reservar(fixture.personaB(), hora(9), otro).profesionalId()).isEqualTo(otro);
	}

	@Test
	@DisplayName("un turno de otra prestacion ocupa al profesional: no se ofrece y la reserva lo rechaza con los datos del choque")
	void lo_que_se_ofrece_es_lo_que_se_acepta() {
		long otraOferta = fixtures.agregarOferta(fixture);
		TurnoView ocupante = turnos.reservar(fixture.actor(), fixture.consultorioId(), otraOferta,
				new ReservaCommand(fixture.personaA(), hora(10), fixture.profesionalMembershipId(), null)).turno();

		assertThat(disponibilidad.buscar(fixture.actor(), fixture.consultorioId(), busqueda(null, 50)).horarios())
				.noneMatch(horario -> horario.desde().equals(hora(10)));

		assertThatThrownBy(() -> reservar(fixture.personaB(), hora(10), fixture.profesionalMembershipId()))
				.isInstanceOfSatisfying(RecursoOcupadoException.class, ocupado -> {
					assertThat(ocupado.getTurnoEnConflictoId()).isEqualTo(ocupante.id());
					assertThat(ocupado.getConflictoInicio()).isEqualTo(hora(10));
				});
	}

	@Test
	@DisplayName("la validacion dice por que no, con alternativas reales, y no reserva nada")
	void validacion_con_alternativas() {
		reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());

		var resultado = disponibilidad.validar(fixture.actor(), fixture.consultorioId(), new ValidacionDeHorarioCommand(
				fixture.ofertaId(), hora(9), fixture.profesionalMembershipId(), null, fixture.personaB(), null, null, null));

		assertThat(resultado.estado()).isEqualTo("NO_DISPONIBLE");
		assertThat(resultado.causa()).isIn("SLOT_COMPLETO", "PROFESIONAL_OCUPADO");
		assertThat(resultado.alternativas()).isNotEmpty().allSatisfy(horario -> assertThat(horario.desde()).isAfter(hora(9)));
		// La alternativa que ofrece se puede reservar: si no, no es una alternativa.
		assertThat(reservar(fixture.personaB(), resultado.alternativas().getFirst().desde(), fixture.profesionalMembershipId()))
				.isNotNull();
	}

	@Test
	@DisplayName("el sobreturno entra con permiso y motivo, queda marcado, y sin permiso no entra")
	void sobreturno() {
		reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());

		ReservaCommand pedido = new ReservaCommand(fixture.personaB(), hora(9), fixture.profesionalMembershipId(), null,
				null, null, null, null, OrigenDeTurno.AGENDA, "Urgencia clinica");
		OperatingActor sinPermiso = fixtures.actorDe(fixture, "ADMINISTRATIVO");
		assertThatThrownBy(() -> turnos.reservar(sinPermiso, fixture.consultorioId(), fixture.ofertaId(), pedido))
				.hasMessageContaining("permiso requerido");

		TurnoView sobreturno = turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), pedido).turno();
		var leido = recepcion.ver(fixture.actor(), fixture.consultorioId(), sobreturno.id());
		assertThat(leido.operativo().sobreturno()).isTrue();
		assertThat(leido.operativo().sobreturnoMotivo()).isEqualTo("Urgencia clinica");
		assertThat(jdbc.queryForObject(
				"SELECT sobreturno_autorizado_por_cuenta_id FROM turno WHERE id = ?", Long.class, sobreturno.id()))
				.isEqualTo(fixture.actor().accountId());
	}

	@Test
	@DisplayName("dos profesionales a la misma hora con dos boxes quedan en boxes distintos, no encima")
	void dos_profesionales_dos_boxes() {
		Fixture conBox = fixtures.poblar(fixture.organizationId(), fixture.consultorioId(), fixture.actor().accountId(), 1, true);
		long otro = fixtures.agregarProfesional(conBox);
		long primerBox = fixtures.agregarEspacio(conBox, "Box 1");
		long segundoBox = fixtures.agregarEspacio(conBox, "Box 2");

		TurnoView primero = turnos.reservar(conBox.actor(), conBox.consultorioId(), conBox.ofertaId(),
				new ReservaCommand(conBox.personaA(), hora(9), conBox.profesionalMembershipId(), null)).turno();
		TurnoView segundo = turnos.reservar(conBox.actor(), conBox.consultorioId(), conBox.ofertaId(),
				new ReservaCommand(conBox.personaB(), hora(9), otro, null)).turno();

		assertThat(List.of(primero.espacioId(), segundo.espacioId())).containsExactlyInAnyOrder(primerBox, segundoBox);
		assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT espacio_id) FROM turno WHERE oferta_id = ?", Long.class, conBox.ofertaId()))
				.isEqualTo(2L);
	}

	@Test
	@DisplayName("con un solo box el segundo profesional no tiene lugar: la reserva, la validacion y la busqueda dicen lo mismo")
	void dos_profesionales_un_box() {
		Fixture conBox = fixtures.poblar(fixture.organizationId(), fixture.consultorioId(), fixture.actor().accountId(), 1, true);
		long otro = fixtures.agregarProfesional(conBox);
		fixtures.agregarEspacio(conBox, "Box unico");
		turnos.reservar(conBox.actor(), conBox.consultorioId(), conBox.ofertaId(),
				new ReservaCommand(conBox.personaA(), hora(9), conBox.profesionalMembershipId(), null));

		assertThatThrownBy(() -> turnos.reservar(conBox.actor(), conBox.consultorioId(), conBox.ofertaId(),
				new ReservaCommand(conBox.personaB(), hora(9), otro, null)))
				.isInstanceOfSatisfying(RecursoOcupadoException.class,
						ocupado -> assertThat(ocupado.getRecurso()).isEqualTo("espacio"));

		var validacion = disponibilidad.validar(conBox.actor(), conBox.consultorioId(), new ValidacionDeHorarioCommand(
				conBox.ofertaId(), hora(9), otro, null, conBox.personaB(), null, null, null));
		assertThat(validacion.estado()).isEqualTo("NO_DISPONIBLE");
		assertThat(validacion.causa()).isEqualTo("ESPACIO_OCUPADO");
		assertThat(validacion.alternativas()).isNotEmpty().noneMatch(horario -> horario.desde().equals(hora(9)));

		assertThat(disponibilidad.buscar(conBox.actor(), conBox.consultorioId(), new BusquedaDeDisponibilidad(
				conBox.ofertaId(), otro, LUNES, LUNES.plusDays(1), null, null, null, 50)).horarios())
				.as("lo que no se puede reservar no se ofrece")
				.noneMatch(horario -> horario.desde().equals(hora(9)));
	}

	@Test
	@DisplayName("un motivo de sobreturno al limite entra en el turno y en el historial: el prefijo no rompe la columna")
	void motivo_de_sobreturno_al_limite() {
		reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());
		String motivo = "x".repeat(300);

		TurnoView sobreturno = turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), new ReservaCommand(
				fixture.personaB(), hora(9), fixture.profesionalMembershipId(), null, null, null, null, null, OrigenDeTurno.AGENDA, motivo)).turno();

		assertThat(jdbc.queryForObject("SELECT sobreturno_motivo FROM turno WHERE id = ?", String.class, sobreturno.id())).isEqualTo(motivo);
		assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(motivo) FROM turno_evento WHERE turno_id = ?", Integer.class, sobreturno.id())).isEqualTo(300);
	}

	@Test
	@DisplayName("dos asociaciones simultaneas no gastan la misma sesion del plan: la segunda espera el lock y ve la primera")
	void asociar_al_plan_en_paralelo() {
		planDe(fixture.personaA(), 1, 0);
		TurnoView uno = reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());
		TurnoView otro = reservar(fixture.personaA(), hora(10), fixture.profesionalMembershipId());

		var desenlaces = AgendaFixtures.enParalelo(List.<java.util.concurrent.Callable<TurnoView>>of(
				() -> turnos.asociarContexto(fixture.actor(), fixture.consultorioId(), uno.id(), null, PLAN_ID, uno.version()),
				() -> turnos.asociarContexto(fixture.actor(), fixture.consultorioId(), otro.id(), null, PLAN_ID, otro.version())));

		assertThat(desenlaces).as(desenlaces.toString()).filteredOn(desenlace -> !desenlace.fallo()).hasSize(1);
		assertThat(desenlaces).filteredOn(AgendaFixtures.Desenlace::fallo).singleElement()
				.satisfies(desenlace -> assertThat(AgendaFixtures.causaEs(desenlace.error(), PlanSinSesionesException.class)).isTrue());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE plan_id = ? AND consultorio_id = ?", Long.class, PLAN_ID, fixture.consultorioId()))
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("el plan pone el tope: dos pendientes son dos turnos, y cancelar devuelve la sesion")
	void plan_con_sesiones_contadas() {
		planDe(fixture.personaA(), 2, 0);

		TurnoView primero = turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(9))).turno();
		turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(10)));

		assertThatThrownBy(() -> turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(11))))
				.isInstanceOfSatisfying(PlanSinSesionesException.class, sinSesiones -> {
					assertThat(sinSesiones.getPendientes()).isZero();
					assertThat(sinSesiones.getPedidas()).isEqualTo(1);
				});

		ciclo.cancelar(fixture.actor(), fixture.consultorioId(), primero.id(), "No puede venir",
				OrigenDeCancelacion.PACIENTE, primero.version());
		assertThat(turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(11))).turno().planId())
				.isEqualTo(PLAN_ID);
		assertThat(jdbc.queryForObject("SELECT origen_cancelacion FROM turno WHERE id = ?", String.class, primero.id()))
				.isEqualTo("PACIENTE");

		var leido = recepcion.ver(fixture.actor(), fixture.consultorioId(), primero.id());
		assertThat(leido.operativo().estadoOperativo()).isEqualTo("CANCELADO");
		assertThat(leido.operativo().casoId()).isEqualTo(CASO_ID);
	}

	@Test
	@DisplayName("un plan de otra persona se rechaza antes de tocar la agenda")
	void plan_de_otra_persona() {
		planDe(fixture.personaB(), 2, 0);

		assertThatThrownBy(() -> turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(9))))
				.isInstanceOf(ContextoClinicoInvalidoException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE consultorio_id = ?", Long.class, fixture.consultorioId()))
				.isZero();
	}

	@Test
	@DisplayName("con duracion propia, lo que se ofrece dura eso, la reserva lo acepta y el turno queda con ese fin; el otro profesional sigue con la de la oferta")
	void duracion_propia_del_profesional() {
		// La oferta del fixture dura 60. Carla la hace en 30: las 09:30 existen para ella y no para el otro.
		long carla = fixtures.agregarProfesional(fixture);
		long otro = fixture.profesionalMembershipId();
		conSuscripcion();
		fijarDuraciones(Map.of(carla, 30));

		var horarios = disponibilidad.buscar(fixture.actor(), fixture.consultorioId(), busqueda(null, 50)).horarios();
		assertThat(horarios).filteredOn(horario -> Long.valueOf(carla).equals(horario.profesionalId()))
				.extracting("desde", "hasta")
				.startsWith(tuple(hora(9), minutos(9, 30)), tuple(minutos(9, 30), hora(10)));
		assertThat(horarios).filteredOn(horario -> Long.valueOf(otro).equals(horario.profesionalId()))
				.extracting("desde", "hasta")
				.startsWith(tuple(hora(9), hora(10)), tuple(hora(10), hora(11)));

		TurnoView suyo = reservar(fixture.personaA(), minutos(9, 30), carla);
		assertThat(suyo.fin()).isEqualTo(hora(10));
		assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MINUTE, inicio, fin) FROM turno WHERE id = ?", Integer.class, suyo.id()))
				.isEqualTo(30);
		assertThat(reservar(fixture.personaB(), hora(9), otro).fin()).isEqualTo(hora(10));

		// Con "cualquier profesional" cada uno se evalua con lo suyo: a las 12:30 Carla entra y el otro,
		// cuya franja termina a las 13:00, no. Con uno solo libre, el fin es el de ese.
		var cualquiera = disponibilidad.validar(fixture.actor(), fixture.consultorioId(), new ValidacionDeHorarioCommand(
				fixture.ofertaId(), minutos(12, 30), null, null, fixture.personaC(), null, null, null));
		assertThat(cualquiera.estado()).isEqualTo("DISPONIBLE");
		assertThat(cualquiera.profesionales()).extracting("id", "duracionPropiaMinutos").containsExactly(tuple(carla, 30));
		assertThat(cualquiera.fin()).isEqualTo(hora(13));

		// Moverlo sin decir quien atiende conserva a Carla y su duracion; pasarselo al otro le cambia el fin.
		TurnoView movido = ciclo.reprogramar(fixture.actor(), fixture.consultorioId(), suyo.id(),
				new ReprogramacionCommand(hora(11), null, "Cambio de horario", suyo.version()));
		assertThat(movido.fin()).isEqualTo(minutos(11, 30));
		assertThat(ciclo.reprogramar(fixture.actor(), fixture.consultorioId(), movido.id(),
				new ReprogramacionCommand(hora(11), otro, "Cambio de profesional", movido.version())).fin())
				.isEqualTo(hora(12));

		// Una serie va con la duracion de su profesional, en la revision y en el alta.
		ReglaDeRecurrencia regla = new ReglaDeRecurrencia(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LUNES.plusWeeks(1), null, 1);
		AltaDeSerieCommand serie = new AltaDeSerieCommand(fixture.ofertaId(), fixture.personaC(), carla, regla, null);
		Instant inicioDeLaSerie = LUNES.plusWeeks(1).atTime(9, 0).atZone(ZONA).toInstant();
		assertThat(previsualizacion.previsualizar(fixture.actor(), fixture.consultorioId(), serie).ocurrencias().getFirst().fin())
				.isEqualTo(inicioDeLaSerie.plus(30, ChronoUnit.MINUTES));
		assertThat(series.crear(fixture.actor(), fixture.consultorioId(), serie).serie().turnos().getFirst().fin())
				.isEqualTo(inicioDeLaSerie.plus(30, ChronoUnit.MINUTES));

		// Sin la duracion propia vuelve a la de la oferta: el turno ya dado conserva su fin.
		fijarDuraciones(Map.of());
		assertThat(habilitaciones.leer(actorDeOffering(), fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId())
				.duracionesPropias()).isEmpty();
		assertThat(disponibilidad.buscar(fixture.actor(), fixture.consultorioId(), new BusquedaDeDisponibilidad(
				fixture.ofertaId(), carla, LUNES.plusWeeks(2), LUNES.plusWeeks(2).plusDays(1), null, null, null, 1)).horarios())
				.singleElement()
				.satisfies(horario -> assertThat(java.time.Duration.between(horario.desde(), horario.hasta())).hasMinutes(60));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oferta_duracion_profesional WHERE oferta_id = ? AND active = 0",
				Long.class, fixture.ofertaId())).as("quitarla es baja logica").isEqualTo(1L);
	}

	@Test
	@DisplayName("las duraciones usan la version de la oferta: quien guarda con una vieja choca, y con la devuelta encadena")
	void duraciones_con_la_version_de_la_oferta() {
		conSuscripcion();
		long leida = habilitaciones.leer(actorDeOffering(), fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId()).ofertaVersion();
		long profesional = fixture.profesionalMembershipId();

		long siguiente = habilitaciones.reemplazarDuraciones(actorDeOffering(), fixture.organizationId(), fixture.consultorioId(),
				fixture.ofertaId(), Map.of(profesional, 30), leida).ofertaVersion();
		habilitaciones.reemplazarDuraciones(actorDeOffering(), fixture.organizationId(), fixture.consultorioId(),
				fixture.ofertaId(), Map.of(profesional, 20), siguiente);

		assertThatThrownBy(() -> habilitaciones.reemplazarDuraciones(actorDeOffering(), fixture.organizationId(), fixture.consultorioId(),
				fixture.ofertaId(), Map.of(profesional, 40), leida))
				.isInstanceOf(OptimisticLockingFailureException.class);
		assertThat(jdbc.queryForObject("SELECT duracion_minutos FROM oferta_duracion_profesional WHERE oferta_id = ? AND active = 1",
				Integer.class, fixture.ofertaId())).isEqualTo(20);
	}

	@Test
	@DisplayName("la previsualizacion no crea nada y sugiere; el alta reserva exactamente lo revisado")
	void serie_revisada() {
		reservar(fixture.personaA(), LUNES.plusWeeks(1).atTime(9, 0).atZone(ZONA).toInstant(), fixture.profesionalMembershipId());
		long antes = contarTurnos();

		ReglaDeRecurrencia regla = new ReglaDeRecurrencia(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LUNES, null, 3);
		var propuesta = previsualizacion.previsualizar(fixture.actor(), fixture.consultorioId(), new AltaDeSerieCommand(
				fixture.ofertaId(), fixture.personaB(), fixture.profesionalMembershipId(), regla, null));

		assertThat(contarTurnos()).as("previsualizar no crea nada").isEqualTo(antes);
		assertThat(propuesta.ocurrencias()).extracting("estado").containsExactly("DISPONIBLE", "NO_DISPONIBLE", "DISPONIBLE");
		var sugerencia = propuesta.ocurrencias().get(1).sugerencia();
		assertThat(sugerencia).as("la ocupada trae una alternativa").isNotNull();

		var creada = series.crear(fixture.actor(), fixture.consultorioId(), new AltaDeSerieCommand(
				fixture.ofertaId(), fixture.personaB(), fixture.profesionalMembershipId(), regla, null,
				List.of(
						new AltaDeSerieCommand.OcurrenciaElegida(propuesta.ocurrencias().get(0).inicio(), null),
						new AltaDeSerieCommand.OcurrenciaElegida(sugerencia.desde(), null)),
				null, null, OrigenDeTurno.PLAN));

		assertThat(creada.serie().turnos()).extracting("inicio")
				.containsExactly(propuesta.ocurrencias().get(0).inicio(), sugerencia.desde());
	}

	@Test
	@DisplayName("la lista de espera: quien espera aparece al cancelar, asignar crea un turno comun y se resuelve una sola vez")
	void lista_de_espera() {
		TurnoView cancelado = reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());
		var entrada = listaDeEspera.anotar(fixture.actor(), fixture.consultorioId(), new AltaEnListaDeEsperaCommand(
				fixture.personaB(), fixture.ofertaId(), null, null, null, LUNES, LUNES.plusDays(5),
				Set.of(DayOfWeek.MONDAY), LocalTime.of(8, 0), LocalTime.of(12, 0), null, "Prefiere a la manana"));
		listaDeEspera.anotar(fixture.actor(), fixture.consultorioId(), new AltaEnListaDeEsperaCommand(
				fixture.personaC(), fixture.ofertaId(), null, null, null, LUNES.plusDays(1), LUNES.plusDays(5),
				null, null, null, null, "No le sirve el lunes"));

		ciclo.cancelar(fixture.actor(), fixture.consultorioId(), cancelado.id(), "No puede venir", cancelado.version());

		var coincidencias = listaDeEspera.coincidencias(
				fixture.actor(), fixture.consultorioId(), cancelado.id(), null, null, null);
		assertThat(coincidencias).extracting("id").containsExactly(entrada.id());

		var asignada = listaDeEspera.asignar(fixture.actor(), fixture.consultorioId(), entrada.id(), hora(9),
				fixture.profesionalMembershipId(), null, entrada.version());
		assertThat(asignada.estado()).isEqualTo("ASIGNADA");
		assertThat(jdbc.queryForObject("SELECT origen FROM turno WHERE id = ?", String.class, asignada.turnoId()))
				.isEqualTo("LISTA_DE_ESPERA");

		assertThatThrownBy(() -> listaDeEspera.descartar(
				fixture.actor(), fixture.consultorioId(), entrada.id(), "Otra vez", asignada.version()))
				.isInstanceOf(EntradaDeEsperaCerradaException.class);
	}

	@Test
	@DisplayName("un bloqueo que deja turnos afuera no se guarda en silencio, y la agenda lo muestra una vez confirmado")
	void bloqueo_con_turnos_afectados() {
		reservar(fixture.personaA(), hora(10), fixture.profesionalMembershipId());
		var actorDeResource = new com.akine.resource.application.OperatingActor(
				fixture.actor().accountId(), false, fixture.organizationId(), fixture.consultorioId());

		assertThatThrownBy(() -> excepciones.crear(actorDeResource, fixture.consultorioId(), cierre(false)))
				.isInstanceOfSatisfying(BloqueoConTurnosAfectadosException.class,
						afectados -> assertThat(afectados.getTurnosAfectados()).isEqualTo(1));

		excepciones.crear(actorDeResource, fixture.consultorioId(), cierre(true));

		var agenda = agendaOperativa.ver(fixture.actor(), fixture.consultorioId(), LUNES, LUNES.plusDays(1), null, null, null, false);
		assertThat(agenda.bloqueos()).hasSize(1).first().satisfies(bloqueo -> {
			assertThat(bloqueo.motivo()).isEqualTo("CAPACITACION");
			assertThat(bloqueo.desde()).isEqualTo(hora(10));
			assertThat(bloqueo.hasta()).isEqualTo(hora(12));
		});
		assertThat(agenda.turnos()).hasSize(1);
		assertThat(agenda.profesionales()).extracting("membershipId").contains(fixture.profesionalMembershipId());
		assertThat(disponibilidad.buscar(fixture.actor(), fixture.consultorioId(), busqueda(null, 50)).horarios())
				.as("lo bloqueado deja de ofrecerse")
				.noneMatch(horario -> horario.desde().equals(hora(10)) || horario.desde().equals(hora(11)));
	}

	@Test
	@DisplayName("el primer disponible respeta la franja y corta en el limite")
	void primer_disponible() {
		DisponibilidadView primero = disponibilidad.buscar(fixture.actor(), fixture.consultorioId(),
				new BusquedaDeDisponibilidad(fixture.ofertaId(), null, LUNES, LUNES.plusDays(1),
						LocalTime.of(10, 0), LocalTime.of(12, 0), null, 1));

		assertThat(primero.horarios()).hasSize(1).first()
				.satisfies(horario -> assertThat(horario.desde()).isEqualTo(hora(10)));
		assertThat(primero.truncado()).isTrue();
	}

	@Test
	@DisplayName("los proximos turnos de una persona traen primero el mas cercano, no los mas lejanos")
	void proximos_de_la_persona() {
		reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());
		reservar(fixture.personaA(), hora(10), fixture.profesionalMembershipId());
		reservar(fixture.personaA(), hora(11), fixture.profesionalMembershipId());

		var proximos = recepcion.deLaPersona(fixture.actor(), fixture.consultorioId(), fixture.personaA(), true, 0, 2);

		assertThat(proximos.total()).isEqualTo(3);
		assertThat(proximos.turnos()).extracting(fila -> fila.turno().inicio()).containsExactly(hora(9), hora(10));
	}

	@Test
	@DisplayName("el historial de una persona trae lo cancelado, el mas nuevo primero; los proximos dejan lo cancelado afuera")
	void historial_de_la_persona() {
		TurnoView primero = reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());
		reservar(fixture.personaA(), hora(10), fixture.profesionalMembershipId());
		ciclo.cancelar(fixture.actor(), fixture.consultorioId(), primero.id(), "No puede venir", primero.version());

		var historial = recepcion.deLaPersona(fixture.actor(), fixture.consultorioId(), fixture.personaA(), false, 0, 10);
		assertThat(historial.total()).isEqualTo(2);
		assertThat(historial.turnos()).extracting(fila -> fila.turno().inicio()).containsExactly(hora(10), hora(9));
		assertThat(historial.turnos()).extracting(fila -> fila.turno().operativo().estadoOperativo())
				.containsExactly("RESERVADO", "CANCELADO");

		assertThat(recepcion.deLaPersona(fixture.actor(), fixture.consultorioId(), fixture.personaA(), true, 0, 10).total()).isEqualTo(1);
		assertThat(recepcion.deLaPersona(fixture.actor(), fixture.consultorioId(), fixture.personaB(), false, 0, 10).turnos()).isEmpty();
	}

	@Test
	@DisplayName("el contexto de agenda cuenta contra turnos de verdad, y sin casos es una primera consulta")
	void contexto_de_agenda() {
		long ofertaDeOtraSede = fixture.ofertaId() + 1000;
		given(contexto.casosActivosDe(anyLong(), org.mockito.ArgumentMatchers.eq(fixture.personaA()))).willReturn(List.of(
				new CasoAgendable(CASO_ID, fixture.personaA(), 3, fixture.ofertaId(), true, fixture.profesionalMembershipId(), List.of(
						new PlanAgendable(PLAN_ID, CASO_ID, fixture.personaA(), 1, "ACTIVO", 3, 4, List.of(
								new ItemAgendable(fixture.ofertaId(), fixture.consultorioId(), "Kinesiologia", 5, 1),
								new ItemAgendable(ofertaDeOtraSede, fixture.consultorioId() + 1000, "Pileta", 5, 0)))))));
		planDe(fixture.personaA(), 5, 1);
		turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(9)));
		TurnoView cancelado = turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(10))).turno();
		ciclo.cancelar(fixture.actor(), fixture.consultorioId(), cancelado.id(), "No puede venir", cancelado.version());

		var vista = contextoDeAgenda.dePersona(fixture.actor(), fixture.consultorioId(), fixture.personaA());

		assertThat(vista.ultimoProfesionalId()).isEqualTo(fixture.profesionalMembershipId());
		var caso = vista.casos().getFirst();
		assertThat(caso.responsableNombre()).as("el nombre sale de la cuenta del profesional").contains("DePrueba");
		assertThat(caso.ofertaNombre()).startsWith("Oferta Sintetica");
		var prestacion = caso.planes().getFirst().prestaciones().getFirst();
		assertThat(caso.planes().getFirst().prestaciones()).hasSize(1);
		assertThat(prestacion.programadas()).as("el cancelado devuelve la sesion").isEqualTo(1);
		assertThat(prestacion.pendientes()).isEqualTo(5 - 1 - 1);

		var primeraConsulta = contextoDeAgenda.dePersona(fixture.actor(), fixture.consultorioId(), fixture.personaB());
		assertThat(primeraConsulta.casos()).isEmpty();
		assertThat(primeraConsulta.ultimoProfesionalId()).isNull();
	}

	@Test
	@DisplayName("el listado de espera filtra en la base y ordena por urgencia, no alfabeticamente; descartar la saca de las activas")
	void listado_de_la_lista_de_espera() {
		var normal = anotar(fixture.personaA(), PrioridadListaDeEspera.NORMAL);
		var urgente = anotar(fixture.personaB(), PrioridadListaDeEspera.URGENTE);
		var alta = anotar(fixture.personaC(), PrioridadListaDeEspera.ALTA);

		var todas = listaDeEspera.listar(fixture.actor(), fixture.consultorioId(), null, null, null, 0, 10);
		assertThat(todas.total()).isEqualTo(3);
		assertThat(todas.entradas()).extracting(EntradaDeEsperaView::id).containsExactly(urgente.id(), alta.id(), normal.id());
		assertThat(todas.entradas().getFirst().personaNombre()).startsWith("PacienteB");

		var pagina = listaDeEspera.listar(fixture.actor(), fixture.consultorioId(), null, null, null, 1, 1);
		assertThat(pagina.total()).isEqualTo(3);
		assertThat(pagina.entradas()).extracting(EntradaDeEsperaView::id).containsExactly(alta.id());

		var descartada = listaDeEspera.descartar(fixture.actor(), fixture.consultorioId(), alta.id(), "Ya consiguio", alta.version());
		assertThat(descartada.estado()).isEqualTo("DESCARTADA");
		assertThat(descartada.version()).as("la vista lleva la version que deja el cambio").isGreaterThan(alta.version());
		assertThat(jdbc.queryForObject("SELECT motivo_cierre FROM lista_espera_turno WHERE id = ?", String.class, alta.id()))
				.isEqualTo("Ya consiguio");

		assertThat(listaDeEspera.listar(fixture.actor(), fixture.consultorioId(), EstadoListaDeEspera.ACTIVA, null, null, 0, 10).total()).isEqualTo(2);
		assertThat(listaDeEspera.listar(fixture.actor(), fixture.consultorioId(), EstadoListaDeEspera.DESCARTADA, fixture.ofertaId(), fixture.personaC(), 0, 10).entradas())
				.extracting(EntradaDeEsperaView::id).containsExactly(alta.id());
		assertThat(listaDeEspera.listar(fixture.actor(), fixture.consultorioId(), null, fixture.ofertaId() + 1000, null, 0, 10).entradas()).isEmpty();

		var otroCentro = fixtures.crear(1);
		assertThatThrownBy(() -> listaDeEspera.descartar(fixture.actor(), otroCentro.consultorioId(), normal.id(), null, normal.version()))
				.as("una sede de otro tenant no se ve: 404, no 403")
				.isInstanceOf(com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException.class);
	}

	@Test
	@DisplayName("las coincidencias sin turno salen de la oferta y el horario; la duracion de la oferta da el fin")
	void coincidencias_de_un_horario() {
		var leSirve = anotar(fixture.personaA(), PrioridadListaDeEspera.NORMAL);

		assertThat(listaDeEspera.coincidencias(fixture.actor(), fixture.consultorioId(), null, fixture.ofertaId(), hora(9), null))
				.extracting(EntradaDeEsperaView::id).containsExactly(leSirve.id());
		assertThat(listaDeEspera.coincidencias(fixture.actor(), fixture.consultorioId(), null, fixture.ofertaId(), hora(9).plus(java.time.Duration.ofDays(30)), null))
				.isEmpty();
	}

	@Test
	@DisplayName("asociar un caso a un turno ya dado queda en la fila y en el historial, y no pasa por encima del tope del plan")
	void asociar_contexto_a_un_turno() {
		planDe(fixture.personaA(), 1, 0);
		turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(), conPlan(hora(9)));
		TurnoView suelto = reservar(fixture.personaA(), hora(10), fixture.profesionalMembershipId());

		assertThatThrownBy(() -> turnos.asociarContexto(fixture.actor(), fixture.consultorioId(), suelto.id(), null, PLAN_ID, suelto.version()))
				.as("el plan ya tiene su unica sesion programada")
				.isInstanceOf(PlanSinSesionesException.class);

		TurnoView asociado = turnos.asociarContexto(fixture.actor(), fixture.consultorioId(), suelto.id(), CASO_ID, null, suelto.version());

		assertThat(asociado.casoId()).isEqualTo(CASO_ID);
		assertThat(asociado.version()).isGreaterThan(suelto.version());
		assertThat(jdbc.queryForObject("SELECT caso_id FROM turno WHERE id = ?", Long.class, suelto.id())).isEqualTo(CASO_ID);
		assertThat(jdbc.queryForObject("SELECT tipo FROM turno_evento WHERE turno_id = ? ORDER BY id DESC LIMIT 1", String.class, suelto.id()))
				.isEqualTo("CAMBIO_DE_CONTEXTO");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno_evento WHERE turno_id = ?", Long.class, suelto.id())).isEqualTo(2L);

		assertThatThrownBy(() -> turnos.asociarContexto(fixture.actor(), fixture.consultorioId(), suelto.id(), null, null, suelto.version()))
				.as("con la version vieja no se pisa")
				.isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("confirmar persiste la transicion una sola vez, y un cancelado no se confirma")
	void confirmar_contra_la_base() {
		TurnoView turno = reservar(fixture.personaA(), hora(9), fixture.profesionalMembershipId());

		assertThat(turnos.confirmar(fixture.actor(), fixture.consultorioId(), turno.id()).estado()).isEqualTo("CONFIRMADO");
		turnos.confirmar(fixture.actor(), fixture.consultorioId(), turno.id());

		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?", String.class, turno.id())).isEqualTo("CONFIRMADO");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno_evento WHERE turno_id = ? AND tipo = 'CONFIRMACION'", Long.class, turno.id()))
				.isEqualTo(1L);

		TurnoView otro = reservar(fixture.personaB(), hora(10), fixture.profesionalMembershipId());
		ciclo.cancelar(fixture.actor(), fixture.consultorioId(), otro.id(), "No puede venir", otro.version());
		assertThatThrownBy(() -> turnos.confirmar(fixture.actor(), fixture.consultorioId(), otro.id()))
				.isInstanceOf(com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException.class);
	}

	@Test
	@DisplayName("una oferta de baja responde NO_DISPONIBLE en la validacion, sin caerse buscando alternativas")
	void validacion_de_una_oferta_de_baja() {
		jdbc.update("UPDATE oferta_servicio_consultorio SET active = 0, deleted_at = UTC_TIMESTAMP(6), deactivation_reason = 'Sintetico' WHERE id = ?",
				fixture.ofertaId());

		var resultado = disponibilidad.validar(fixture.actor(), fixture.consultorioId(), new ValidacionDeHorarioCommand(
				fixture.ofertaId(), hora(9), fixture.profesionalMembershipId(), null, null, null, null, null));

		assertThat(resultado.estado()).isEqualTo("NO_DISPONIBLE");
		assertThat(resultado.causa()).isEqualTo("OFERTA_NO_AGENDABLE");
		assertThat(resultado.alternativas()).isEmpty();
	}

	@Test
	@DisplayName("quien puede atender: los habilitados de la oferta y los de la sede, con su nombre")
	void profesionales_que_atienden() {
		long otro = fixtures.agregarProfesional(fixture);

		assertThat(disponibilidad.profesionales(fixture.actor(), fixture.consultorioId(), fixture.ofertaId()))
				.extracting(recurso -> recurso.id()).containsExactlyInAnyOrder(fixture.profesionalMembershipId(), otro);
		assertThat(disponibilidad.profesionales(fixture.actor(), fixture.consultorioId(), null))
				.extracting(recurso -> recurso.id()).contains(fixture.profesionalMembershipId(), otro);
		assertThat(disponibilidad.profesionales(fixture.actor(), fixture.consultorioId(), fixture.ofertaId()))
				.allSatisfy(recurso -> assertThat(recurso.nombre()).contains("DePrueba"));
	}

	// =================================================================================

	private EntradaDeEsperaView anotar(long personaId, PrioridadListaDeEspera prioridad) {
		return listaDeEspera.anotar(fixture.actor(), fixture.consultorioId(), new AltaEnListaDeEsperaCommand(
				personaId, fixture.ofertaId(), null, null, null, LUNES, LUNES.plusDays(5),
				null, null, null, prioridad, null));
	}

	private ExcepcionAltaCommand cierre(boolean confirma) {
		return new ExcepcionAltaCommand(fixture.profesionalMembershipId(), TipoExcepcion.CIERRE, MotivoExcepcion.CAPACITACION,
				LUNES, LUNES.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), null, null, confirma);
	}

	private void planDe(long personaId, int planificadas, int realizadas) {
		PlanAgendable plan = new PlanAgendable(PLAN_ID, CASO_ID, personaId, 1, "ACTIVO", 3, 4,
				List.of(new ItemAgendable(fixture.ofertaId(), fixture.consultorioId(), "Kinesiologia", planificadas, realizadas)));
		given(contexto.plan(anyLong(), org.mockito.ArgumentMatchers.eq(PLAN_ID))).willReturn(Optional.of(plan));
		given(contexto.caso(anyLong(), org.mockito.ArgumentMatchers.eq(CASO_ID))).willReturn(Optional.of(
				new CasoAgendable(CASO_ID, personaId, 1, fixture.ofertaId(), true, null, List.of(plan))));
	}

	private ReservaCommand conPlan(Instant inicio) {
		return new ReservaCommand(fixture.personaA(), inicio, fixture.profesionalMembershipId(), null, null, null,
				null, PLAN_ID, OrigenDeTurno.PLAN, null);
	}

	private long contarTurnos() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE consultorio_id = ?", Long.class, fixture.consultorioId());
	}

	private BusquedaDeDisponibilidad busqueda(Long profesionalId, int limite) {
		return new BusquedaDeDisponibilidad(fixture.ofertaId(), profesionalId, LUNES, LUNES.plusDays(1), null, null, null, limite);
	}

	private TurnoView reservar(long personaId, Instant inicio, long profesionalId) {
		return turnos.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, profesionalId, null)).turno();
	}

	private Instant hora(int horaLocal) {
		return LUNES.atTime(horaLocal, 0).atZone(ZONA).toInstant();
	}

	private Instant minutos(int horaLocal, int minuto) {
		return LUNES.atTime(horaLocal, minuto).atZone(ZONA).toInstant();
	}

	private com.akine.offering.application.OperatingActor actorDeOffering() {
		return new com.akine.offering.application.OperatingActor(
				fixture.actor().accountId(), false, fixture.organizationId(), fixture.consultorioId());
	}

	/** Leer la configuracion exige pertenencia, y la pertenencia una suscripcion: AgendaFixtures no la crea. */
	private void conSuscripcion() {
		jdbc.update("""
				INSERT INTO subscription (organization_id, plan_id, status, started_at, active,
				                          version, created_at, updated_at)
				SELECT ?, id, 'ACTIVA', UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
				FROM plan WHERE code = 'BASICO'
				""", fixture.organizationId());
	}

	private void fijarDuraciones(Map<Long, Integer> duraciones) {
		long version = habilitaciones.leer(actorDeOffering(), fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId())
				.ofertaVersion();
		habilitaciones.reemplazarDuraciones(actorDeOffering(), fixture.organizationId(), fixture.consultorioId(),
				fixture.ofertaId(), duraciones, version);
	}
}
