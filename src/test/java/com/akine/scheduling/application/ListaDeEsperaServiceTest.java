package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.domain.EntradaDeListaDeEspera;
import com.akine.scheduling.domain.EstadoListaDeEspera;
import com.akine.scheduling.domain.OrigenDeCancelacion;
import com.akine.scheduling.domain.OrigenDeTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.PrioridadListaDeEspera;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.EntradaDeEsperaCerradaException;
import com.akine.scheduling.domain.exception.EntradaDeEsperaNotAccessibleException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.ListaDeEsperaRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Quien espera un lugar (TURNO.md §20): anotarse no reserva, y asignar crea un turno comun.
 *
 * <p>Lo que se prueba es lo que protege a la entrada: que una entrada cerrada o con la version vieja
 * no llegue a reservar, y que si la reserva falla la entrada siga activa.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ListaDeEsperaService")
class ListaDeEsperaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long OFERTA = 42L;
	private static final long PERSONA = 128L;
	private static final long ENTRADA = 900L;
	private static final long TURNO = 300L;
	private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final Instant LUNES_09 = LUNES.atTime(9, 0).atZone(ZONA).toInstant();

	@Mock private ListaDeEsperaRepositoryPort entradas;
	@Mock private TurnoRepositoryPort turnos;
	@Mock private TurnoService turnoService;
	@Mock private CicloDeTurnoService ciclo;
	@Mock private ContextoClinicoDeAgenda contextoDeAgenda;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PacienteDirectory pacientes;
	@Mock private ContactoDirectory contactos;
	@Mock private OfertaDirectory ofertas;
	@Mock private NombresDeAgenda nombres;
	@Mock private SoporteEnAgenda soporte;
	@Mock private AuditTrail auditTrail;

	private ListaDeEsperaService service;
	private final OperatingActor actor = new OperatingActor(99L, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new ListaDeEsperaService(entradas, turnos, turnoService, ciclo, new ContextoClinicoDeTurno(contextoDeAgenda),
				consultorios, pacientes, contactos, ofertas, nombres, soporte, auditTrail, mock(PlatformTransactionManager.class));

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", ZONA.getId(), true)));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA)).willReturn(Optional.of(oferta()));
		given(ofertas.findTodas(eq(ORG_ID), eq(SEDE_ID), any())).willReturn(Map.of(OFERTA, oferta()));
		given(pacientes.find(ORG_ID, PERSONA)).willReturn(Optional.of(paciente(true)));
		given(pacientes.findAll(eq(ORG_ID), any())).willReturn(Map.of(PERSONA, paciente(true)));
		given(contactos.findAll(eq(ORG_ID), any())).willReturn(Map.of(
				PERSONA, new ContactoDePersona(PERSONA, "persona@ejemplo.test", "Paciente", "351-555-0000")));
		given(nombres.profesionales(anyLong(), any())).willReturn(Map.of(31L, "Perez, Ana"));
		given(entradas.save(any())).willAnswer(invocacion -> conId(invocacion.getArgument(0), ENTRADA));
		given(entradas.saveAndFlush(any())).willAnswer(invocacion -> invocacion.getArgument(0));
	}

	// =================================================================================
	// Anotar
	// =================================================================================

	@Test
	@DisplayName("anotar exige turno:manage, no reserva nada, audita, y la vista ya viene con nombres y telefono")
	void anotar() {
		EntradaDeEsperaView vista = service.anotar(actor, SEDE_ID, alta(PrioridadListaDeEspera.URGENTE));

		verify(soporte).exigir(actor, PermissionCodes.TURNO_MANAGE, ORG_ID, SEDE_ID);
		verifyNoInteractions(turnoService);
		assertThat(vista.estado()).isEqualTo("ACTIVA");
		assertThat(vista.prioridad()).isEqualTo("URGENTE");
		assertThat(vista.personaNombre()).isEqualTo("Sintetico, Paciente");
		assertThat(vista.telefono()).isEqualTo("351-555-0000");
		assertThat(vista.ofertaNombre()).isEqualTo("Kinesiologia");
		assertThat(vista.profesionalNombre()).isEqualTo("Perez, Ana");
		assertThat(vista.dias()).containsExactly(DayOfWeek.MONDAY.getValue(), DayOfWeek.FRIDAY.getValue());
		assertThat(auditado().eventType()).isEqualTo("LISTA_DE_ESPERA_ALTA");
	}

	@Test
	@DisplayName("sin prioridad queda NORMAL, y sin contacto ni oferta resuelta la vista no se cae")
	void anotar_con_lo_minimo() {
		given(contactos.findAll(eq(ORG_ID), any())).willReturn(Map.of());
		given(ofertas.findTodas(eq(ORG_ID), eq(SEDE_ID), any())).willReturn(Map.of());

		EntradaDeEsperaView vista = service.anotar(actor, SEDE_ID, new AltaEnListaDeEsperaCommand(
				PERSONA, OFERTA, null, null, null, LUNES, LUNES, null, null, null, null, "   "));

		assertThat(vista.prioridad()).isEqualTo("NORMAL");
		assertThat(vista.notas()).as("unas notas en blanco no son notas").isNull();
		assertThat(vista.telefono()).isNull();
		assertThat(vista.profesionalNombre()).isNull();
		assertThat(vista.ofertaNombre()).isEqualTo("(oferta no disponible)");
		assertThat(vista.dias()).isEmpty();
	}

	@Test
	@DisplayName("no se anota a quien no es paciente: el perfil se activa aparte y con su actor")
	void anotar_exige_paciente_vigente() {
		given(pacientes.find(ORG_ID, PERSONA)).willReturn(Optional.of(paciente(false)));
		assertThatThrownBy(() -> service.anotar(actor, SEDE_ID, alta(null)))
				.isInstanceOf(PersonaSinPerfilPacienteException.class);

		given(pacientes.find(ORG_ID, PERSONA)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.anotar(actor, SEDE_ID, alta(null)))
				.isInstanceOf(PersonaNotAccessibleException.class);

		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.anotar(actor, SEDE_ID, alta(null)))
				.isInstanceOf(OfertaNotAccessibleException.class);

		verify(entradas, never()).save(any());
	}

	// =================================================================================
	// Listar
	// =================================================================================

	@Test
	@DisplayName("listar exige turno:read, y con cero filas no le pide la pagina a la base")
	void listar_vacio() {
		EntradasDeEsperaPagina pagina = service.listar(actor, SEDE_ID, EstadoListaDeEspera.ACTIVA, OFERTA, PERSONA, 0, 20);

		assertThat(pagina.total()).isZero();
		assertThat(pagina.entradas()).isEmpty();
		verify(soporte).exigir(actor, PermissionCodes.TURNO_READ, ORG_ID, SEDE_ID);
		verify(entradas, never()).listar(anyLong(), anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
		verifyNoInteractions(pacientes);
	}

	@Test
	@DisplayName("listar pasa los filtros a la base y arma la vista de cada fila")
	void listar_con_filas() {
		given(entradas.contar(ORG_ID, SEDE_ID, EstadoListaDeEspera.ACTIVA, OFERTA, null)).willReturn(7L);
		given(entradas.listar(ORG_ID, SEDE_ID, EstadoListaDeEspera.ACTIVA, OFERTA, null, 1, 2))
				.willReturn(List.of(entrada(ENTRADA, PrioridadListaDeEspera.ALTA, Instant.now())));

		EntradasDeEsperaPagina pagina = service.listar(actor, SEDE_ID, EstadoListaDeEspera.ACTIVA, OFERTA, null, 1, 2);

		assertThat(pagina.total()).isEqualTo(7L);
		assertThat(pagina.entradas()).extracting(EntradaDeEsperaView::id).containsExactly(ENTRADA);
	}

	// =================================================================================
	// Coincidencias
	// =================================================================================

	@Test
	@DisplayName("con un turno el horario sale de ese turno, los mas urgentes primero y a igual prioridad los mas viejos")
	void coincidencias_de_un_turno() {
		Instant antes = Instant.parse("2027-01-01T10:00:00Z");
		Instant despues = Instant.parse("2027-02-01T10:00:00Z");
		given(turnos.findByIdInScope(ORG_ID, SEDE_ID, TURNO)).willReturn(Optional.of(turno()));
		EntradaDeListaDeEspera normal = entrada(1L, PrioridadListaDeEspera.NORMAL, antes);
		EntradaDeListaDeEspera altaNueva = entrada(2L, PrioridadListaDeEspera.ALTA, despues);
		EntradaDeListaDeEspera altaVieja = entrada(3L, PrioridadListaDeEspera.ALTA, antes);
		EntradaDeListaDeEspera urgente = entrada(4L, PrioridadListaDeEspera.URGENTE, despues);
		EntradaDeListaDeEspera otraFecha = new EntradaDeListaDeEspera(ORG_ID, SEDE_ID, PERSONA, OFERTA, null, null, null,
				LUNES.plusDays(1), LUNES.plusDays(2), null, null, null, null, null, 99L, antes);
		given(entradas.findActivasDeLaOferta(ORG_ID, SEDE_ID, OFERTA))
				.willReturn(List.of(normal, altaNueva, altaVieja, urgente, otraFecha));

		assertThat(service.coincidencias(actor, SEDE_ID, TURNO, null, null, null))
				.extracting(EntradaDeEsperaView::id).containsExactly(4L, 3L, 2L, 1L);
	}

	@Test
	@DisplayName("sin turno, la oferta y el inicio dan el horario y la duracion de la oferta da el fin")
	void coincidencias_de_un_horario() {
		// Una franja de 09:00 a 09:30 no le sirve a un turno de 45 minutos que arranca a las 09:00.
		EntradaDeListaDeEspera noLeAlcanza = new EntradaDeListaDeEspera(ORG_ID, SEDE_ID, PERSONA, OFERTA, null, null, null,
				LUNES, LUNES, null, LocalTime.of(9, 0), LocalTime.of(9, 30), null, null, 99L, Instant.now());
		EntradaDeListaDeEspera leSirve = conId(new EntradaDeListaDeEspera(ORG_ID, SEDE_ID, PERSONA, OFERTA, null, null, null,
				LUNES, LUNES, null, LocalTime.of(9, 0), LocalTime.of(10, 0), null, null, 99L, Instant.now()), 5L);
		given(entradas.findActivasDeLaOferta(ORG_ID, SEDE_ID, OFERTA)).willReturn(List.of(noLeAlcanza, leSirve));

		assertThat(service.coincidencias(actor, SEDE_ID, null, OFERTA, LUNES_09, 31L))
				.extracting(EntradaDeEsperaView::id).containsExactly(5L);
	}

	@Test
	@DisplayName("sin turno ni horario es 400; un turno o una oferta ajena es 404; nadie compatible es lista vacia")
	void coincidencias_rechazos() {
		assertThatThrownBy(() -> service.coincidencias(actor, SEDE_ID, null, OFERTA, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.coincidencias(actor, SEDE_ID, null, null, LUNES_09, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.coincidencias(actor, SEDE_ID, 999L, null, null, null))
				.isInstanceOf(TurnoNotAccessibleException.class);
		assertThatThrownBy(() -> service.coincidencias(actor, SEDE_ID, null, 999L, LUNES_09, null))
				.isInstanceOf(OfertaNotAccessibleException.class);

		assertThat(service.coincidencias(actor, SEDE_ID, null, OFERTA, LUNES_09, null)).isEmpty();
		verifyNoInteractions(contactos);
	}

	// =================================================================================
	// Asignar
	// =================================================================================

	@Test
	@DisplayName("asignar crea un turno comun con clave de idempotencia y origen: el reintento no reserva otro")
	void asignar() {
		dadaLaEntrada(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now()));
		given(turnoService.reservar(eq(actor), eq(SEDE_ID), eq(OFERTA), any())).willReturn(reservaDe(turno()));

		EntradaDeEsperaView vista = service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, 55L, 0L);

		ArgumentCaptor<ReservaCommand> reserva = ArgumentCaptor.forClass(ReservaCommand.class);
		verify(turnoService).reservar(eq(actor), eq(SEDE_ID), eq(OFERTA), reserva.capture());
		assertThat(reserva.getValue().idempotencyKey())
				.as("lleva el horario: elegir otro despues de un conflicto es otro pedido, no un choque de clave")
				.isEqualTo("lista-de-espera:" + ENTRADA + ":0:" + LUNES_09.getEpochSecond());
		assertThat(reserva.getValue().origen()).isEqualTo(OrigenDeTurno.LISTA_DE_ESPERA);
		assertThat(reserva.getValue().espacioId()).isEqualTo(55L);
		assertThat(reserva.getValue().esSobreturno()).as("estar en la lista no da un lugar que no existe").isFalse();
		assertThat(vista.estado()).isEqualTo("ASIGNADA");
		assertThat(vista.turnoId()).isEqualTo(TURNO);
		assertThat(auditado().eventType()).isEqualTo("LISTA_DE_ESPERA_ASIGNADA");
		verify(soporte).exigir(actor, PermissionCodes.TURNO_MANAGE, ORG_ID, SEDE_ID);
	}

	@Test
	@DisplayName("si la reserva falla, el conflicto es el de cualquier reserva y la entrada sigue activa")
	void asignar_con_el_horario_tomado() {
		EntradaDeListaDeEspera entrada = entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now());
		dadaLaEntrada(entrada);
		given(turnoService.reservar(any(), anyLong(), anyLong(), any())).willThrow(new RecursoOcupadoException("profesional"));

		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, null, 0L))
				.isInstanceOf(RecursoOcupadoException.class);

		assertThat(entrada.getEstado()).isEqualTo(EstadoListaDeEspera.ACTIVA);
		verify(entradas, never()).saveAndFlush(any());
		verifyNoInteractions(ciclo);
	}

	@Test
	@DisplayName("una entrada ya resuelta, una version vieja o una entrada ajena no llegan a reservar")
	void asignar_rechazos() {
		EntradaDeListaDeEspera cerrada = entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now());
		cerrada.descartar("Ya consiguio", 99L, Instant.now());
		dadaLaEntrada(cerrada);
		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, null, 0L))
				.isInstanceOf(EntradaDeEsperaCerradaException.class);

		dadaLaEntrada(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now()));
		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, null, 3L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, 999L, LUNES_09, 31L, null, 0L))
				.isInstanceOf(EntradaDeEsperaNotAccessibleException.class);

		verifyNoInteractions(turnoService);
	}

	@Test
	@DisplayName("si la entrada cambio mientras se reservaba, el turno recien dado se cancela y el conflicto sigue: nadie lo pidio ya")
	void asignar_con_la_entrada_movida_durante_la_reserva() {
		EntradaDeListaDeEspera movida = entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now());
		ReflectionTestUtils.setField(movida, "version", 4L);
		given(entradas.findByIdInScope(ORG_ID, SEDE_ID, ENTRADA))
				.willReturn(Optional.of(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now())))
				.willReturn(Optional.of(movida));
		given(turnoService.reservar(any(), anyLong(), anyLong(), any())).willReturn(reservaDe(turno()));

		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, null, 0L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(ciclo).cancelar(eq(actor), eq(SEDE_ID), eq(TURNO), any(), eq(OrigenDeCancelacion.INSTITUCION), eq(0L));
		verify(entradas, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("si otro la resolvio con la misma version, tambien se cancela el turno recien dado")
	void asignar_con_la_entrada_resuelta_durante_la_reserva() {
		EntradaDeListaDeEspera resuelta = entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now());
		resuelta.descartar("Ya consiguio", 99L, Instant.now());
		given(entradas.findByIdInScope(ORG_ID, SEDE_ID, ENTRADA))
				.willReturn(Optional.of(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now())))
				.willReturn(Optional.of(resuelta));
		given(turnoService.reservar(any(), anyLong(), anyLong(), any())).willReturn(reservaDe(turno()));

		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, null, 0L))
				.isInstanceOf(EntradaDeEsperaCerradaException.class);

		verify(ciclo).cancelar(eq(actor), eq(SEDE_ID), eq(TURNO), any(), eq(OrigenDeCancelacion.INSTITUCION), eq(0L));
	}

	@Test
	@DisplayName("el doble click no cancela el turno del otro: si la clave devolvio un turno ya dado, no es de este pedido")
	void asignar_repetido_no_cancela_el_turno_ya_dado() {
		// Dos pedidos con la misma version y el mismo horario comparten la clave: el segundo recibe el
		// turno del primero, creado = false, y cuando el primero ya marco la entrada le salta el conflicto.
		EntradaDeListaDeEspera yaAsignada = entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now());
		yaAsignada.asignar(TURNO, 99L, Instant.now());
		ReflectionTestUtils.setField(yaAsignada, "version", 1L);
		given(entradas.findByIdInScope(ORG_ID, SEDE_ID, ENTRADA))
				.willReturn(Optional.of(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now())))
				.willReturn(Optional.of(yaAsignada));
		given(turnoService.reservar(any(), anyLong(), anyLong(), any()))
				.willReturn(new ResultadoDeReserva(TurnoView.de(turno()), false));

		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, null, 0L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verifyNoInteractions(ciclo);
	}

	@Test
	@DisplayName("un error tecnico al marcar no cancela el turno: el reintento lo recupera por la clave")
	void asignar_con_un_error_tecnico_al_marcar() {
		dadaLaEntrada(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now()));
		given(turnoService.reservar(any(), anyLong(), anyLong(), any())).willReturn(reservaDe(turno()));
		given(entradas.saveAndFlush(any())).willThrow(new DataAccessResourceFailureException("conexion caida"));

		assertThatThrownBy(() -> service.asignar(actor, SEDE_ID, ENTRADA, LUNES_09, 31L, null, 0L))
				.isInstanceOf(DataAccessResourceFailureException.class);

		verifyNoInteractions(ciclo);
	}

	// =================================================================================
	// Descartar
	// =================================================================================

	@Test
	@DisplayName("descartar saca a la persona sin turno, guarda el motivo y lo audita")
	void descartar() {
		dadaLaEntrada(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now()));

		EntradaDeEsperaView vista = service.descartar(actor, SEDE_ID, ENTRADA, "  Ya consiguio turno ", 0L);

		assertThat(vista.estado()).isEqualTo("DESCARTADA");
		assertThat(vista.motivoCierre()).isEqualTo("Ya consiguio turno");
		assertThat(vista.turnoId()).isNull();
		AuditEntry auditado = auditado();
		assertThat(auditado.eventType()).isEqualTo("LISTA_DE_ESPERA_DESCARTADA");
		assertThat(auditado.previousState()).isEqualTo("ACTIVA");
		assertThat(auditado.newState()).isEqualTo("DESCARTADA");
		verify(soporte).exigir(actor, PermissionCodes.TURNO_MANAGE, ORG_ID, SEDE_ID);
	}

	@Test
	@DisplayName("una entrada se resuelve una sola vez, y no con una version vieja")
	void descartar_rechazos() {
		EntradaDeListaDeEspera cerrada = entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now());
		cerrada.asignar(TURNO, 99L, Instant.now());
		dadaLaEntrada(cerrada);
		assertThatThrownBy(() -> service.descartar(actor, SEDE_ID, ENTRADA, null, 0L))
				.isInstanceOf(EntradaDeEsperaCerradaException.class)
				.hasMessageContaining("asignada");

		dadaLaEntrada(entrada(ENTRADA, PrioridadListaDeEspera.NORMAL, Instant.now()));
		assertThatThrownBy(() -> service.descartar(actor, SEDE_ID, ENTRADA, null, 1L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(entradas, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("sin contexto es 403, una sede ajena es 404 y sin turno:manage no se opera")
	void rechazos_de_acceso() {
		OperatingActor sinContexto = new OperatingActor(99L, false, null, null);
		assertThatThrownBy(() -> service.listar(sinContexto, SEDE_ID, null, null, null, 0, 20))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.anotar(null, SEDE_ID, alta(null)))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.listar(actor, 8L, null, null, null, 0, 20))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		doThrow(new AccessDeniedException("sin permiso")).when(soporte)
				.exigir(actor, PermissionCodes.TURNO_MANAGE, ORG_ID, SEDE_ID);
		assertThatThrownBy(() -> service.descartar(actor, SEDE_ID, ENTRADA, null, 0L))
				.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(entradas);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private void dadaLaEntrada(EntradaDeListaDeEspera entrada) {
		given(entradas.findByIdInScope(ORG_ID, SEDE_ID, ENTRADA)).willReturn(Optional.of(entrada));
	}

	private AuditEntry auditado() {
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		return auditoria.getValue();
	}

	private static AltaEnListaDeEsperaCommand alta(PrioridadListaDeEspera prioridad) {
		return new AltaEnListaDeEsperaCommand(PERSONA, OFERTA, 31L, null, null, LUNES, LUNES.plusDays(10),
				Set.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY), LocalTime.of(8, 0), LocalTime.of(12, 0), prioridad, "Prefiere a la manana");
	}

	private static EntradaDeListaDeEspera entrada(long id, PrioridadListaDeEspera prioridad, Instant creadaEn) {
		return conId(new EntradaDeListaDeEspera(ORG_ID, SEDE_ID, PERSONA, OFERTA, null, null, null,
				LUNES, LUNES.plusDays(5), null, null, null, prioridad, null, 99L, creadaEn), id);
	}

	private static EntradaDeListaDeEspera conId(EntradaDeListaDeEspera entrada, long id) {
		ReflectionTestUtils.setField(entrada, "id", id);
		return entrada;
	}

	private static Turno turno() {
		Turno turno = new Turno(ORG_ID, SEDE_ID, OFERTA, 129L, 31L, null, LUNES_09, LUNES_09.plusSeconds(2700),
				99L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", TURNO);
		return turno;
	}

	private static ResultadoDeReserva reservaDe(Turno turno) {
		return new ResultadoDeReserva(TurnoView.de(turno), true);
	}

	private static OfertaSnapshot oferta() {
		return new OfertaSnapshot(OFERTA, ORG_ID, SEDE_ID, 3L, "Kinesiologia", 45, 1, false, true, false, false, false,
				LocalDate.of(2020, 1, 1), null, true);
	}

	private static PacienteSnapshot paciente(boolean vigente) {
		return new PacienteSnapshot(PERSONA, ORG_ID, "Sintetico", "Paciente", null, null, null, true, vigente, 5L, Instant.now());
	}
}
