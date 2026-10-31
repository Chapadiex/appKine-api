package com.akine.scheduling.application;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.OrigenDeTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.exception.CoberturaNoAccesibleException;
import com.akine.scheduling.domain.exception.ContextoClinicoInvalidoException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.PlanSinSesionesException;
import com.akine.scheduling.domain.exception.SedeDadaDeBajaException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Defecto encontrado en E-3: <b>se podia reservar un turno en el pasado</b>.
 *
 * <p>Reprogramar ya rechazaba un destino pasado y {@code TurnoCicloConcurrenteIT} daba por hecho
 * que "la reserva exige futuro", pero la reserva no lo controlaba: el motor de 05.01 tambien
 * dibuja los slots de hoy que ya pasaron, y el revalidador solo mira que el hueco exista. El turno
 * nacia inalterable (DP-04: no se cancela ni se mueve lo que ya empezo), asi que un error de fecha
 * solo podia cerrarse marcando AUSENTE a un paciente que nunca falto.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TurnoService")
class TurnoServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long TURNO_ID = 900L;
	private static final long CASO_ID = 9L;
	private static final long PLAN_ID = 57L;

	@Mock private TurnoRepositoryPort turnos;
	@Mock private TurnoEventoRepositoryPort eventos;
	@Mock private AgendaSedeRepositoryPort agendas;
	@Mock private OfertaDirectory ofertas;
	@Mock private PacienteDirectory pacientes;
	@Mock private CoberturasAplicablesDirectory coberturas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AgendaSedeIniciador iniciador;
	@Mock private RevalidadorDeSlot revalidador;
	@Mock private AvisosDeTurno avisos;
	@Mock private ContextoClinicoDeAgenda contextoDeAgenda;
	@Mock private SesionesDelPlan sesionesDelPlan;
	@Mock private AuditTrail auditTrail;

	private TurnoService service;
	private final OperatingActor actor = new OperatingActor(9L, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void prepararServicio() {
		// Un estado real y no null: la reserva puede marcar su transaccion para revertir.
		PlatformTransactionManager transacciones = mock(PlatformTransactionManager.class);
		given(transacciones.getTransaction(any())).willReturn(new SimpleTransactionStatus());
		service = new TurnoService(turnos, eventos, agendas, ofertas, pacientes, coberturas, consultorios,
				permissionGuard, iniciador, revalidador, avisos,
				new ContextoClinicoDeTurno(contextoDeAgenda),
				sesionesDelPlan, auditTrail, transacciones);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true)));
		given(agendas.lockByScope(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(mock(AgendaSede.class)));
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.of(new OfertaSnapshot(
				42L, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 60, 1, false, true, false, false, false,
				LocalDate.now().minusYears(1), null, true)));
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.of(new PacienteSnapshot(
				128L, ORG_ID, "Sintetico", "Paciente", null, null, null, true, true, 5L, Instant.now())));
	}

	@Test
	@DisplayName("un horario que ya paso no se reserva: 409 slot-no-disponible y ninguna fila")
	void no_se_reserva_en_el_pasado() {
		Instant haceUnaHora = Instant.now().minus(Duration.ofHours(1));

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, haceUnaHora, 31L, null)))
				.isInstanceOf(SlotNoDisponibleException.class)
				.hasMessageContaining("ya paso");

		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("05.02: una cobertura elegida que no es de la persona o no esta vigente ese dia es 404 y no reserva")
	void cobertura_elegida_ajena_no_reserva() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(coberturas.vigenteDeLaPersona(eq(ORG_ID), eq(128L), eq(412L), any()))
				.willReturn(false);

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null, 412L)))
				.isInstanceOf(CoberturaNoAccesibleException.class);

		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("05.02: la cobertura elegida y vigente queda en el turno; sin elegir no se consulta")
	void cobertura_elegida_queda_en_el_turno() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(coberturas.vigenteDeLaPersona(eq(ORG_ID), eq(128L), eq(412L), any()))
				.willReturn(true);
		given(revalidador.revalidarBajoLock(any(), any())).willReturn(new RevalidadorDeSlot.Asignacion(31L, null));
		given(turnos.save(any(Turno.class))).willAnswer(inv -> {
			Turno t = inv.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(t, "id", 900L);
			return t;
		});

		var conCobertura = service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null, 412L));
		var sinCobertura = service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null));

		assertThat(conCobertura.turno().coberturaId()).isEqualTo(412L);
		assertThat(sinCobertura.turno().coberturaId()).isNull();
		verify(coberturas).vigenteDeLaPersona(anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("carga §10: lo que no lee turnos va ANTES del lock de la agenda; cupo y solapamiento, despues")
	void lo_que_no_lee_turnos_va_antes_del_lock() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(revalidador.prevalidar(any())).willReturn(new RevalidadorDeSlot.Previa(31L));
		given(revalidador.revalidarBajoLock(any(), any())).willReturn(new RevalidadorDeSlot.Asignacion(31L, null));
		given(turnos.save(any(Turno.class))).willAnswer(inv -> {
			Turno t = inv.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(t, "id", 900L);
			return t;
		});

		service.reservar(actor, CONSULTORIO_ID, 42L, new ReservaCommand(128L, manana, 31L, null));

		// El lock serializa todas las reservas de la sede: lo que corre despues de tomarlo corre con
		// las demas esperando. Oferta, paciente, habilitacion y franja no leen turnos y nadie que
		// los cambie toma este lock, asi que van antes.
		InOrder orden = inOrder(ofertas, pacientes, revalidador, avisos, agendas, turnos);
		orden.verify(ofertas).find(ORG_ID, CONSULTORIO_ID, 42L);
		orden.verify(pacientes).find(ORG_ID, 128L);
		orden.verify(revalidador).prevalidar(any());
		orden.verify(avisos).contactoDe(ORG_ID, 128L);
		orden.verify(agendas).lockByScope(ORG_ID, CONSULTORIO_ID);
		orden.verify(revalidador).revalidarBajoLock(any(), eq(new RevalidadorDeSlot.Previa(31L)));
		orden.verify(turnos).save(any(Turno.class));
	}

	@Test
	@DisplayName("carga §10: un rechazo leido antes del lock sale DESPUES de la idempotencia: el reintento sigue devolviendo su turno")
	void el_rechazo_previo_no_tapa_el_reintento() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		ReservaCommand command = new ReservaCommand(128L, manana, 31L, "clave-1");
		Turno existente = new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, null,
				manana, manana.plus(Duration.ofHours(1)), 9L, Instant.now(),
				"clave-1", command.huella(CONSULTORIO_ID, 42L));
		org.springframework.test.util.ReflectionTestUtils.setField(existente, "id", 900L);
		given(turnos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));
		// Desde el primer intento la persona dejo de ser paciente: bajo el lock, antes, la
		// idempotencia salia primero y devolvia el turno. Tiene que seguir siendo asi.
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.empty());

		ResultadoDeReserva reintento = service.reservar(actor, CONSULTORIO_ID, 42L, command);

		assertThat(reintento.creado()).isFalse();
		assertThat(reintento.turno().id()).isEqualTo(900L);
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("carga §10: sin clave repetida, el rechazo leido antes del lock se lanza bajo el lock")
	void el_rechazo_previo_se_lanza_bajo_el_lock() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null)))
				.isInstanceOf(PersonaNotAccessibleException.class);

		verify(agendas).lockByScope(ORG_ID, CONSULTORIO_ID);
		verify(revalidador, never()).revalidarBajoLock(any(), any());
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("carga §10: un error tecnico antes del lock se propaga en el acto, aunque la clave ya exista")
	void el_error_tecnico_previo_no_se_guarda() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		ReservaCommand command = new ReservaCommand(128L, manana, 31L, "clave-1");
		given(pacientes.find(ORG_ID, 128L))
				.willThrow(new org.springframework.dao.DataAccessResourceFailureException("conexion caida"));

		// Guardarlo y seguir seria trabajar con una sesion de Hibernate que ya lanzo, y con la clave
		// repetida terminaria en un 200 despues de un error.
		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, command))
				.isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

		verify(agendas, never()).lockByScope(anyLong(), anyLong());
		verify(turnos, never()).findByIdempotencyKey(anyLong(), any());
		verify(turnos, never()).save(any());
	}

	// =================================================================================
	// Rechazos de la reserva (flujo unico, §3 y §5)
	// =================================================================================

	@Test
	@DisplayName("sin contexto de trabajo es 403 en reservar, confirmar y asociar contexto: un 401 deja al frontend en bucle")
	void sin_contexto() {
		OperatingActor sinContexto = new OperatingActor(9L, false, null, null);

		assertThatThrownBy(() -> service.reservar(sinContexto, CONSULTORIO_ID, 42L, reserva(manana())))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.confirmar(null, CONSULTORIO_ID, TURNO_ID))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.asociarContexto(sinContexto, CONSULTORIO_ID, TURNO_ID, null, null, 0L))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("una oferta que no es de la sede es 404, y no le impide a un reintento recuperar su turno")
	void oferta_inexistente() {
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, reserva(manana())))
				.isInstanceOf(OfertaNotAccessibleException.class);

		ReservaCommand conClave = new ReservaCommand(128L, manana(), 31L, "clave-1");
		given(turnos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(turnoReservado(conClave)));
		assertThat(service.reservar(actor, CONSULTORIO_ID, 42L, conClave).creado()).isFalse();
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("una oferta de baja o fuera de vigencia es 409, con el motivo que corresponde")
	void oferta_que_no_agenda() {
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.of(oferta(false, null)));
		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, reserva(manana())))
				.isInstanceOf(OfertaNoAgendableException.class)
				.hasMessageContaining("dada de baja");

		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.of(oferta(true, LocalDate.now().minusYears(1).plusDays(1))));
		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, reserva(manana())))
				.isInstanceOf(OfertaNoAgendableException.class)
				.hasMessageContaining("no esta vigente");

		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("no se reserva para quien no es paciente: este camino no activa perfiles")
	void persona_sin_perfil_de_paciente() {
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.of(new PacienteSnapshot(
				128L, ORG_ID, "Sintetico", "Persona", null, null, null, true, false, null, null)));

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, reserva(manana())))
				.isInstanceOf(PersonaSinPerfilPacienteException.class);

		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("la misma clave con otro pedido es 409: devolver el turno anterior le haria creer que reservo el nuevo")
	void clave_con_otro_pedido() {
		Instant manana = manana();
		Turno existente = turnoReservado(new ReservaCommand(128L, manana, 31L, "clave-1"));
		given(turnos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana.plusSeconds(3600), 31L, "clave-1")))
				.isInstanceOf(IdempotencyKeyConflictException.class);

		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("sobreturno: se pide turno:sobreturno aparte de turno:manage, y sin el no se toma ningun lock")
	void sobreturno_sin_permiso() {
		given(permissionGuard.requirePermission(argThat(consulta -> consulta != null
				&& PermissionCodes.TURNO_SOBRETURNO.equals(consulta.permissionCode()))))
				.willThrow(new AccessDeniedException("sin permiso"));

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, new ReservaCommand(
				128L, manana(), 31L, null, null, null, null, null, OrigenDeTurno.AGENDA, "Urgencia")))
				.isInstanceOf(AccessDeniedException.class);

		verify(iniciador, never()).asegurar(anyLong(), anyLong());
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("sobreturno: queda marcado con motivo y autorizante, el revalidador lo sabe, y se audita")
	void sobreturno_autorizado() {
		conAsignacion();

		var reservado = service.reservar(actor, CONSULTORIO_ID, 42L, new ReservaCommand(
				128L, manana(), 31L, null, null, 55L, null, null, OrigenDeTurno.AGENDA, "  Urgencia clinica "));

		assertThat(reservado.turno().sobreturno()).isTrue();
		ArgumentCaptor<RevalidadorDeSlot.Pedido> pedido = ArgumentCaptor.forClass(RevalidadorDeSlot.Pedido.class);
		verify(revalidador).revalidarBajoLock(pedido.capture(), any());
		assertThat(pedido.getValue().sobreturno()).isTrue();
		assertThat(pedido.getValue().espacioElegidoId()).isEqualTo(55L);

		ArgumentCaptor<Turno> guardado = ArgumentCaptor.forClass(Turno.class);
		verify(turnos).save(guardado.capture());
		assertThat(guardado.getValue().getSobreturnoMotivo()).isEqualTo("Urgencia clinica");
		assertThat(guardado.getValue().getOrigen()).isEqualTo(OrigenDeTurno.AGENDA);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo("TURNO_SOBRETURNO");
		assertThat(auditoria.getValue().reason()).isEqualTo("Urgencia clinica");
		ArgumentCaptor<TurnoEvento> evento = ArgumentCaptor.forClass(TurnoEvento.class);
		verify(eventos).registrar(evento.capture());
		assertThat(evento.getValue().getMotivo()).isEqualTo("Sobreturno: Urgencia clinica");
	}

	@Test
	@DisplayName("el plan se mira bajo el lock y despues del lugar: sin sesiones es 409 y no se crea nada")
	void plan_sin_sesiones() {
		conAsignacion();
		var plan = dadoElPlan();
		doThrow(new PlanSinSesionesException(PLAN_ID, 0, 1)).when(sesionesDelPlan).exigirDisponibles(ORG_ID, plan, 42L, 1);

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, conPlan(manana())))
				.isInstanceOf(PlanSinSesionesException.class);

		InOrder orden = inOrder(agendas, revalidador, sesionesDelPlan);
		orden.verify(agendas).lockByScope(ORG_ID, CONSULTORIO_ID);
		orden.verify(revalidador).revalidarBajoLock(any(), any());
		orden.verify(sesionesDelPlan).exigirDisponibles(ORG_ID, plan, 42L, 1);
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("el plan se relee bajo el lock: lo que se cerro mientras se esperaba tiene que contar")
	void el_plan_se_relee_bajo_el_lock() {
		conAsignacion();
		var antes = dadoElPlan();
		var bajoElLock = new ContextoClinicoDeAgenda.PlanAgendable(PLAN_ID, CASO_ID, 128L, 1, "ACTIVO", 3, 4, java.util.List.of(
				new ContextoClinicoDeAgenda.ItemAgendable(42L, CONSULTORIO_ID, "Kinesiologia", 10, 1)));
		given(contextoDeAgenda.plan(ORG_ID, PLAN_ID)).willReturn(Optional.of(antes)).willReturn(Optional.of(bajoElLock));

		service.reservar(actor, CONSULTORIO_ID, 42L, conPlan(manana()));

		verify(sesionesDelPlan).exigirDisponibles(ORG_ID, bajoElLock, 42L, 1);
		verify(sesionesDelPlan, never()).exigirDisponibles(ORG_ID, antes, 42L, 1);
	}

	@Test
	@DisplayName("un plan que se suspendio mientras se esperaba el lock se rechaza y no se crea el turno")
	void plan_suspendido_mientras_se_esperaba() {
		conAsignacion();
		var antes = dadoElPlan();
		var suspendido = new ContextoClinicoDeAgenda.PlanAgendable(PLAN_ID, CASO_ID, 128L, 1, "SUSPENDIDO", 3, 4, antes.items());
		given(contextoDeAgenda.plan(ORG_ID, PLAN_ID)).willReturn(Optional.of(antes)).willReturn(Optional.of(suspendido));

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, conPlan(manana())))
				.isInstanceOf(ContextoClinicoInvalidoException.class);

		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("un motivo al limite no rompe el evento: entra entero en el turno y recortado en turno_evento")
	void motivo_de_sobreturno_al_limite() {
		conAsignacion();
		String motivo = "x".repeat(300);

		service.reservar(actor, CONSULTORIO_ID, 42L, new ReservaCommand(
				128L, manana(), 31L, null, null, null, null, null, OrigenDeTurno.AGENDA, motivo));

		ArgumentCaptor<Turno> guardado = ArgumentCaptor.forClass(Turno.class);
		verify(turnos).save(guardado.capture());
		assertThat(guardado.getValue().getSobreturnoMotivo()).isEqualTo(motivo);
		ArgumentCaptor<TurnoEvento> evento = ArgumentCaptor.forClass(TurnoEvento.class);
		verify(eventos).registrar(evento.capture());
		assertThat(evento.getValue().getMotivo()).startsWith("Sobreturno: ").hasSize(300);
	}

	@Test
	@DisplayName("un turno de plan guarda caso, plan y origen, y el caso sale del plan")
	void turno_de_un_plan() {
		conAsignacion();
		dadoElPlan();

		var reservado = service.reservar(actor, CONSULTORIO_ID, 42L, conPlan(manana())).turno();

		assertThat(reservado.casoId()).isEqualTo(CASO_ID);
		assertThat(reservado.planId()).isEqualTo(PLAN_ID);
	}

	@Test
	@DisplayName("un plan que no sirve se rechaza bajo el lock, sin llegar a revalidar el horario")
	void plan_que_no_sirve() {
		given(contextoDeAgenda.plan(ORG_ID, PLAN_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L, conPlan(manana())))
				.isInstanceOf(ContextoClinicoInvalidoException.class);

		verify(revalidador, never()).revalidarBajoLock(any(), any());
	}

	// =================================================================================
	// Confirmar
	// =================================================================================

	@Test
	@DisplayName("confirmar registra la transicion una sola vez: el doble click no ensucia el historial")
	void confirmar_es_idempotente() {
		Turno turno = dadoElTurno();

		TurnoView confirmado = service.confirmar(actor, CONSULTORIO_ID, TURNO_ID);
		service.confirmar(actor, CONSULTORIO_ID, TURNO_ID);

		assertThat(confirmado.estado()).isEqualTo("CONFIRMADO");
		assertThat(turno.getConfirmadoEn()).isNotNull();
		ArgumentCaptor<TurnoEvento> evento = ArgumentCaptor.forClass(TurnoEvento.class);
		verify(eventos).registrar(evento.capture());
		assertThat(evento.getValue().getTipo()).isEqualTo(TipoEventoTurno.CONFIRMACION);
		assertThat(evento.getValue().getEstadoAnterior()).isEqualTo("RESERVADO");
		assertThat(evento.getValue().getEstadoNuevo()).isEqualTo("CONFIRMADO");
	}

	@Test
	@DisplayName("un turno cancelado no se confirma: quedaba confirmado sin ocupar el lugar")
	void no_se_confirma_un_cancelado() {
		Turno turno = dadoElTurno();
		turno.cancelar("No puede venir", 9L, Instant.now());

		assertThatThrownBy(() -> service.confirmar(actor, CONSULTORIO_ID, TURNO_ID))
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);

		verify(turnos, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("un turno que no existe en la sede es 404 al confirmar y al asociar contexto")
	void turno_inexistente() {
		assertThatThrownBy(() -> service.confirmar(actor, CONSULTORIO_ID, 999L))
				.isInstanceOf(TurnoNotAccessibleException.class);
		assertThatThrownBy(() -> service.asociarContexto(actor, CONSULTORIO_ID, 999L, null, null, 0L))
				.isInstanceOf(TurnoNotAccessibleException.class);
	}

	// =================================================================================
	// Asociar caso y plan a un turno ya dado (§7)
	// =================================================================================

	@Test
	@DisplayName("asociar un plan controla las sesiones igual que reservar, y deja el evento en el historial")
	void asociar_plan() {
		Turno turno = dadoElTurno();
		var plan = dadoElPlan();

		TurnoView asociado = service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, null, PLAN_ID, 0L);

		verify(sesionesDelPlan).exigirDisponibles(ORG_ID, plan, 42L, 1);
		assertThat(asociado.casoId()).isEqualTo(CASO_ID);
		assertThat(asociado.planId()).isEqualTo(PLAN_ID);
		assertThat(turno.getPlanId()).isEqualTo(PLAN_ID);
		ArgumentCaptor<TurnoEvento> evento = ArgumentCaptor.forClass(TurnoEvento.class);
		verify(eventos).registrar(evento.capture());
		assertThat(evento.getValue().getTipo()).isEqualTo(TipoEventoTurno.CAMBIO_DE_CONTEXTO);
	}

	@Test
	@DisplayName("un plan sin sesiones no se puede asociar: no es un camino para pasarse del tope")
	void asociar_plan_sin_sesiones() {
		dadoElTurno();
		var plan = dadoElPlan();
		doThrow(new PlanSinSesionesException(PLAN_ID, 0, 1)).when(sesionesDelPlan).exigirDisponibles(ORG_ID, plan, 42L, 1);

		assertThatThrownBy(() -> service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, null, PLAN_ID, 0L))
				.isInstanceOf(PlanSinSesionesException.class);

		verify(turnos, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("asociar un plan cuenta sus sesiones bajo el lock de la sede: dos pedidos simultaneos no gastan la misma")
	void asociar_toma_el_lock_antes_de_contar() {
		dadoElTurno();
		var plan = dadoElPlan();

		service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, null, PLAN_ID, 0L);

		InOrder orden = inOrder(iniciador, agendas, sesionesDelPlan, turnos);
		orden.verify(iniciador).asegurar(ORG_ID, CONSULTORIO_ID);
		orden.verify(agendas).lockByScope(ORG_ID, CONSULTORIO_ID);
		orden.verify(sesionesDelPlan).exigirDisponibles(ORG_ID, plan, 42L, 1);
		orden.verify(turnos).saveAndFlush(any());
	}

	@Test
	@DisplayName("en una sede dada de baja no se asocia nada: es la misma puerta que reservar")
	void asociar_en_una_sede_de_baja() {
		dadoElTurno();
		given(consultorios.inactivaBajoLockCompartido(ORG_ID, CONSULTORIO_ID)).willReturn(true);

		assertThatThrownBy(() -> service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, null, null, 0L))
				.isInstanceOf(SedeDadaDeBajaException.class);

		verify(turnos, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("volver a asociar el mismo plan no gasta otra sesion: el turno ya la tenia contada")
	void asociar_el_mismo_plan() {
		Turno turno = dadoElTurno();
		turno.asociarContexto(CASO_ID, PLAN_ID);
		dadoElPlan();

		service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, CASO_ID, PLAN_ID, 0L);

		verify(sesionesDelPlan, never()).exigirDisponibles(anyLong(), any(), anyLong(), anyInt());
	}

	@Test
	@DisplayName("sin caso ni plan los quita; una version vieja es 409 antes de leer nada clinico")
	void desasociar_y_version_vieja() {
		Turno turno = dadoElTurno();
		turno.asociarContexto(CASO_ID, PLAN_ID);

		assertThatThrownBy(() -> service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, null, null, 7L))
				.isInstanceOf(OptimisticLockingFailureException.class);
		assertThat(turno.getPlanId()).isEqualTo(PLAN_ID);

		TurnoView desasociado = service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, null, null, 0L);

		assertThat(desasociado.casoId()).isNull();
		assertThat(desasociado.planId()).isNull();
		org.mockito.Mockito.verifyNoInteractions(contextoDeAgenda, sesionesDelPlan);
	}

	@Test
	@DisplayName("un turno que ya cerro su ciclo no cambia de contexto")
	void asociar_a_un_turno_cerrado() {
		Turno turno = dadoElTurno();
		turno.cancelar("No puede venir", 9L, Instant.now());

		assertThatThrownBy(() -> service.asociarContexto(actor, CONSULTORIO_ID, TURNO_ID, null, null, 0L))
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
	}

	private void conAsignacion() {
		given(revalidador.revalidarBajoLock(any(), any())).willReturn(new RevalidadorDeSlot.Asignacion(31L, null));
		given(turnos.save(any(Turno.class))).willAnswer(invocacion -> {
			Turno t = invocacion.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(t, "id", TURNO_ID);
			return t;
		});
	}

	private Turno dadoElTurno() {
		Instant inicio = manana();
		Turno turno = new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, null, inicio, inicio.plus(Duration.ofHours(1)),
				9L, Instant.now(), null, null);
		org.springframework.test.util.ReflectionTestUtils.setField(turno, "id", TURNO_ID);
		given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.of(turno));
		given(turnos.saveAndFlush(any())).willAnswer(invocacion -> invocacion.getArgument(0));
		return turno;
	}

	private ContextoClinicoDeAgenda.PlanAgendable dadoElPlan() {
		var plan = new ContextoClinicoDeAgenda.PlanAgendable(PLAN_ID, CASO_ID, 128L, 1, "ACTIVO", 3, 4, java.util.List.of(
				new ContextoClinicoDeAgenda.ItemAgendable(42L, CONSULTORIO_ID, "Kinesiologia", 10, 0)));
		given(contextoDeAgenda.plan(ORG_ID, PLAN_ID)).willReturn(Optional.of(plan));
		given(contextoDeAgenda.caso(ORG_ID, CASO_ID)).willReturn(Optional.of(new ContextoClinicoDeAgenda.CasoAgendable(
				CASO_ID, 128L, 1, 42L, true, null, java.util.List.of(plan))));
		return plan;
	}

	private Turno turnoReservado(ReservaCommand command) {
		Turno turno = new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, null, command.inicio(),
				command.inicio().plus(Duration.ofHours(1)), 9L, Instant.now(), command.idempotencyKey(),
				command.huella(CONSULTORIO_ID, 42L));
		org.springframework.test.util.ReflectionTestUtils.setField(turno, "id", TURNO_ID);
		return turno;
	}

	private static Instant manana() {
		return Instant.now().plus(Duration.ofDays(1));
	}

	private static ReservaCommand reserva(Instant inicio) {
		return new ReservaCommand(128L, inicio, 31L, null);
	}

	private static ReservaCommand conPlan(Instant inicio) {
		return new ReservaCommand(128L, inicio, 31L, null, null, null, null, PLAN_ID, OrigenDeTurno.PLAN, null);
	}

	private static OfertaSnapshot oferta(boolean activa, LocalDate vigenciaHasta) {
		return new OfertaSnapshot(42L, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 60, 1, false, true, false, false, false,
				LocalDate.now().minusYears(1), vigenciaHasta, activa);
	}

	@Test
	@DisplayName("05.02: sin cobertura la huella es la de antes; con cobertura cambia")
	void la_huella_sin_cobertura_no_cambia() {
		Instant inicio = Instant.parse("2026-11-02T12:00:00Z");
		String sin = new ReservaCommand(128L, inicio, 31L, "k").huella(CONSULTORIO_ID, 42L);
		String con = new ReservaCommand(128L, inicio, 31L, "k", 412L).huella(CONSULTORIO_ID, 42L);

		// SHA-256 de "7|42|128|2026-11-02T12:00:00Z|31", el formato anterior a 05.02.
		assertThat(sin).isEqualTo(sha256("7|42|128|2026-11-02T12:00:00Z|31"));
		assertThat(con).isNotEqualTo(sin);
	}

	private static String sha256(String texto) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(texto.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
