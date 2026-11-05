package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.audit.AuditAgendaFilter;
import com.akine.platform.spi.audit.AuditEventSummary;
import com.akine.platform.spi.audit.AuditQuery;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.resource.spi.CierresDeSedeDirectory;
import com.akine.resource.spi.CierresDeSedeDirectory.CierreDeSede;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import com.akine.scheduling.domain.exception.ProfesionalNotAccessibleException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Quien lee el historial de agenda de quien: el recorte lo hace el servicio con el alcance de
 * {@code agenda:excepciones}, y el paciente solo viaja con {@code turno:read}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistorialDeAgendaServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE_A = 3L;
	private static final long SEDE_B = 4L;
	private static final long CUENTA_PRO = 100L;
	private static final long PRO_EN_A = 31L;
	private static final long PRO_EN_B = 32L;
	private static final long CUENTA_OTRO_PRO = 200L;
	private static final long OTRO_PRO_EN_A = 41L;
	private static final long CUENTA_SECRETARIA = 300L;
	private static final PageRequest PAGINA = PageRequest.of(0, 10);

	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionEvaluator permisos;
	@Mock private AuditQuery auditoria;
	@Mock private AccountIdentityDirectory identidades;
	@Mock private NombresDeAgenda nombres;
	@Mock private PacienteDirectory pacientes;
	@Mock private TurnoRepositoryPort turnos;
	@Mock private CierresDeSedeDirectory cierresDeSede;
	@Mock private OcupacionExternaProbe clases;

	private HistorialDeAgendaService service;

	@BeforeEach
	void setUp() {
		service = new HistorialDeAgendaService(memberships, consultorios, permisos, auditoria, identidades, nombres,
				pacientes, turnos, cierresDeSede, clases);
		given(memberships.find(ORG, PRO_EN_A)).willReturn(Optional.of(membership(PRO_EN_A, CUENTA_PRO, SEDE_A)));
		given(memberships.find(ORG, OTRO_PRO_EN_A)).willReturn(Optional.of(membership(OTRO_PRO_EN_A, CUENTA_OTRO_PRO, SEDE_A)));
		given(memberships.findTodasDeLaCuenta(ORG, CUENTA_PRO)).willReturn(List.of(
				membership(PRO_EN_A, CUENTA_PRO, SEDE_A), membership(PRO_EN_B, CUENTA_PRO, SEDE_B)));
		given(memberships.findByAccount(ORG, CUENTA_PRO)).willReturn(List.of(
				membership(PRO_EN_A, CUENTA_PRO, SEDE_A), membership(PRO_EN_B, CUENTA_PRO, SEDE_B)));
		given(memberships.findByAccount(ORG, CUENTA_SECRETARIA)).willReturn(List.of(membership(51L, CUENTA_SECRETARIA, SEDE_A)));
		given(consultorios.find(anyLong(), anyLong())).willAnswer(i -> Optional.of(
				new ConsultorioSnapshot(i.getArgument(1), ORG, "Sede " + i.getArgument(1), "America/Argentina/Cordoba", true)));
		given(permisos.evaluate(any())).willReturn(PermissionDecision.rechazada(DenialKind.NO_PERMISSION));
		given(auditoria.deAgenda(any(), any())).willReturn(Page.empty(PAGINA));
	}

	@Test
	@DisplayName("el profesional ve su propia agenda en todas sus sedes, con las memberships de las dos")
	void profesional_ve_lo_suyo() {
		concede(CUENTA_PRO, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_A, "ACTIVIDAD_PROPIA");
		concede(CUENTA_PRO, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_B, "ACTIVIDAD_PROPIA");

		service.listar(actor(CUENTA_PRO), ORG, filtro(PRO_EN_A, null), PAGINA);

		AuditAgendaFilter pedido = pedidoALaAuditoria();
		assertThat(pedido.memberships()).containsExactlyInAnyOrder(PRO_EN_A, PRO_EN_B);
		assertThat(pedido.sedes()).containsExactlyInAnyOrder(SEDE_A, SEDE_B);
	}

	@Test
	@DisplayName("el profesional no ve la agenda de otro: 403 y ni se consulta la auditoria")
	void profesional_no_ve_lo_ajeno() {
		concede(CUENTA_PRO, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_A, "ACTIVIDAD_PROPIA");

		assertThatThrownBy(() -> service.listar(actor(CUENTA_PRO), ORG, filtro(OTRO_PRO_EN_A, null), PAGINA))
				.isInstanceOf(AgendaAjenaException.class);
		verify(auditoria, never()).deAgenda(any(), any());
	}

	@Test
	@DisplayName("la secretaria ve solo las sedes que gestiona, aunque el profesional atienda en otras")
	void secretaria_limitada_a_su_sede() {
		concede(CUENTA_SECRETARIA, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_A, "CONSULTORIO");

		service.listar(actor(CUENTA_SECRETARIA), ORG, filtro(PRO_EN_A, null), PAGINA);

		assertThat(pedidoALaAuditoria().sedes()).containsExactly(SEDE_A);
	}

	@Test
	@DisplayName("la secretaria que pide otra sede recibe 403")
	void secretaria_pide_otra_sede() {
		concede(CUENTA_SECRETARIA, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_A, "CONSULTORIO");

		assertThatThrownBy(() -> service.listar(actor(CUENTA_SECRETARIA), ORG, filtro(PRO_EN_A, SEDE_B), PAGINA))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("el administrador de la organizacion lee todas las sedes")
	void org_admin_toda_la_organizacion() {
		concede(CUENTA_SECRETARIA, PermissionCodes.AGENDA_EXCEPCIONES, null, "ORGANIZACION");

		service.listar(actor(CUENTA_SECRETARIA), ORG, filtro(PRO_EN_A, null), PAGINA);

		assertThat(pedidoALaAuditoria().sedes()).isNull();
	}

	@Test
	@DisplayName("otra organizacion o un profesional ajeno al tenant: 404")
	void cross_tenant_es_404() {
		assertThatThrownBy(() -> service.listar(actor(CUENTA_SECRETARIA), 99L, filtro(PRO_EN_A, null), PAGINA))
				.isInstanceOf(ProfesionalNotAccessibleException.class);
		assertThatThrownBy(() -> service.listar(actor(CUENTA_SECRETARIA), ORG, filtro(999L, null), PAGINA))
				.isInstanceOf(ProfesionalNotAccessibleException.class);
		verify(auditoria, never()).deAgenda(any(), any());
	}

	@Test
	@DisplayName("sin turno:read en la sede, el cambio de turno viaja sin paciente")
	void sin_turno_read_no_hay_paciente() {
		concede(CUENTA_SECRETARIA, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_A, "CONSULTORIO");
		AuditEventSummary reserva = new AuditEventSummary(1L, ORG, SEDE_A, CUENTA_SECRETARIA, "TURNO_RESERVADO",
				"Turno", 500L, null, "RESERVADO",
				Map.of("personaId", "77", "profesionalMembershipId", String.valueOf(PRO_EN_A),
						"inicio", "2026-11-10T13:00:00Z", "rol", "ADMINISTRATIVO"),
				null, null, Instant.parse("2026-11-04T12:00:00Z"));
		given(auditoria.deAgenda(any(), any())).willReturn(new PageImpl<>(List.of(reserva), PAGINA, 1));

		CambioDeAgendaView cambio = service.listar(actor(CUENTA_SECRETARIA), ORG, filtro(PRO_EN_A, null), PAGINA)
				.getContent().get(0);

		assertThat(cambio.tipo()).isEqualTo(TipoDeCambioDeAgenda.TURNO_RESERVA);
		assertThat(cambio.actorRol()).isEqualTo("ADMINISTRATIVO");
		assertThat(cambio.despues()).startsWith("Mar 10/11 10:00 · Sede 3");
		assertThat(cambio.personaId()).isNull();
		assertThat(cambio.personaNombre()).isNull();
		verify(pacientes, never()).findAll(anyLong(), any());
	}

	@Test
	@DisplayName("cierre de sede: viaja solo el que afecta (horario o turnos) y de las sedes que el lector alcanza")
	void cierre_de_sede_solo_el_que_afecta() {
		concede(CUENTA_SECRETARIA, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_A, "CONSULTORIO");
		CierreDeSede conHorario = cierre(600L, Set.of(PRO_EN_A));
		CierreDeSede sinNada = cierre(601L, Set.of());
		CierreDeSede conTurno = cierre(602L, Set.of());
		given(cierresDeSede.cierresDeSede(anyLong(), any())).willReturn(List.of(conHorario, sinNada, conTurno));
		Turno turno = org.mockito.Mockito.mock(Turno.class);
		given(turno.getInicio()).willReturn(Instant.parse("2026-11-16T13:00:00Z"));
		given(turno.getFin()).willReturn(Instant.parse("2026-11-16T14:00:00Z"));
		given(turno.getEstado()).willReturn(EstadoTurno.RESERVADO);
		given(turnos.findDeProfesionalesEnSedeEnVentana(anyLong(), anyLong(), any(), any(), any()))
				.willAnswer(i -> List.of());
		// Solo el 602 tiene un turno de la persona adentro; el 601 no la toca.
		given(turnos.findDeProfesionalesEnSedeEnVentana(anyLong(), anyLong(), any(),
				argThat(desde -> desde != null && desde.equals(medianoche("2026-11-23"))), any()))
				.willReturn(List.of(turno));
		AuditEventSummary alta = new AuditEventSummary(2L, ORG, SEDE_A, CUENTA_SECRETARIA,
				"DISPONIBILIDAD_EXCEPCION_CREATED", HistorialDeAgendaService.ENTIDAD_EXCEPCION, 602L, null, "ACTIVO",
				Map.of("alcance", "SEDE", "despues", "Cierre por feriado, 23/11/2026, dia completo"),
				null, null, Instant.parse("2026-11-04T12:00:00Z"));
		given(auditoria.deAgenda(any(), any())).willReturn(new PageImpl<>(List.of(alta), PAGINA, 1));

		CambioDeAgendaView cambio = service.listar(actor(CUENTA_SECRETARIA), ORG, filtro(PRO_EN_A, null), PAGINA)
				.getContent().get(0);

		verify(cierresDeSede).cierresDeSede(ORG, Map.of(SEDE_A, Set.of(PRO_EN_A)));
		AuditAgendaFilter pedido = pedidoALaAuditoria();
		assertThat(pedido.eventosDeSede().entityIds()).containsExactlyInAnyOrder(600L, 602L);
		assertThat(pedido.eventosDeSede().eventTypes()).containsExactlyInAnyOrder("DISPONIBILIDAD_EXCEPCION_CREATED",
				"DISPONIBILIDAD_EXCEPCION_UPDATED", "DISPONIBILIDAD_EXCEPCION_DEACTIVATED");
		assertThat(pedido.traslados()).isEqualTo(HistorialDeAgendaService.TRASLADOS);
		assertThat(cambio.tipo()).isEqualTo(TipoDeCambioDeAgenda.CIERRE_DE_SEDE_ALTA);
		assertThat(cambio.profesionalId()).isEqualTo(PRO_EN_A);
		assertThat(cambio.cierreDeSede().ultimoDia()).isEqualTo(java.time.LocalDate.of(2026, 11, 23));
		assertThat(cambio.cierreDeSede().turnosPendientes()).isEqualTo(1);
		assertThat(cambio.cierreDeSede().conHorarioHabitual()).isFalse();
	}

	@Test
	@DisplayName("pedir solo EXCEPCION_* no trae cierres de sede, y solo CIERRE_DE_SEDE_* no trae los propios")
	void tipos_de_excepcion_y_de_cierre_no_se_mezclan() {
		concede(CUENTA_SECRETARIA, PermissionCodes.AGENDA_EXCEPCIONES, SEDE_A, "CONSULTORIO");
		given(cierresDeSede.cierresDeSede(anyLong(), any())).willReturn(List.of(cierre(600L, Set.of(PRO_EN_A))));

		service.listar(actor(CUENTA_SECRETARIA), ORG, new FiltroDeHistorialDeAgenda(PRO_EN_A, null,
				Set.of(TipoDeCambioDeAgenda.EXCEPCION_ALTA), null, null, null), PAGINA);
		verify(cierresDeSede, never()).cierresDeSede(anyLong(), any());

		service.listar(actor(CUENTA_SECRETARIA), ORG, new FiltroDeHistorialDeAgenda(PRO_EN_A, null,
				Set.of(TipoDeCambioDeAgenda.CIERRE_DE_SEDE_BAJA), null, null, null), PAGINA);
		ArgumentCaptor<AuditAgendaFilter> captor = ArgumentCaptor.forClass(AuditAgendaFilter.class);
		verify(auditoria, org.mockito.Mockito.times(2)).deAgenda(captor.capture(), any());
		assertThat(captor.getAllValues().get(0).eventTypes()).containsExactly("DISPONIBILIDAD_EXCEPCION_CREATED");
		assertThat(captor.getAllValues().get(0).eventosDeSede()).isNull();
		assertThat(captor.getAllValues().get(1).eventTypes()).isEmpty();
		assertThat(captor.getAllValues().get(1).eventosDeSede().eventTypes())
				.containsExactly("DISPONIBILIDAD_EXCEPCION_DEACTIVATED");
	}

	@Test
	@DisplayName("traslado: una fila con el destino, los hechos absorbidos como relacionados, misma persona = sin cambio")
	void traslado_es_una_fila() {
		concede(CUENTA_SECRETARIA, PermissionCodes.AGENDA_EXCEPCIONES, null, "ORGANIZACION");
		AuditEventSummary traslado = new AuditEventSummary(10L, ORG, SEDE_A, CUENTA_SECRETARIA, "TURNO_TRASLADADO",
				"Turno", 500L, "RESERVADO", "CANCELADO",
				Map.of("personaId", "77", "profesionalMembershipId", String.valueOf(PRO_EN_A),
						"inicio", "2026-11-10T13:00:00Z", "turnoNuevoId", "501", "consultorioDestinoId",
						String.valueOf(SEDE_B), "operacionId", "op-1"),
				"Se mudo", null, Instant.parse("2026-11-04T12:00:00Z"));
		given(auditoria.deAgenda(any(), any())).willReturn(new PageImpl<>(List.of(traslado), PAGINA, 1));
		Turno nuevo = org.mockito.Mockito.mock(Turno.class);
		given(nuevo.getId()).willReturn(501L);
		given(nuevo.getConsultorioId()).willReturn(SEDE_B);
		given(nuevo.getProfesionalMembershipId()).willReturn(PRO_EN_B);
		given(nuevo.getInicio()).willReturn(Instant.parse("2026-11-10T14:00:00Z"));
		given(turnos.findTodosPorId(ORG, Set.of(501L))).willReturn(List.of(nuevo));
		given(auditoria.absorbidasDeTraslados(ORG, HistorialDeAgendaService.TRASLADOS, "Turno", Set.of(500L)))
				.willReturn(List.of(
						new AuditEventSummary(8L, ORG, SEDE_A, CUENTA_SECRETARIA, "TURNO_CANCELADO", "Turno", 500L,
								"RESERVADO", "CANCELADO", Map.of(), null, null, Instant.parse("2026-11-04T12:00:00Z")),
						new AuditEventSummary(9L, ORG, SEDE_B, CUENTA_SECRETARIA, "TURNO_RESERVADO", "Turno", 501L,
								null, "RESERVADO", Map.of("turnoOrigenId", "500"), null, null,
								Instant.parse("2026-11-04T12:00:00Z"))));

		CambioDeAgendaView cambio = service.listar(actor(CUENTA_SECRETARIA), ORG, filtro(PRO_EN_A, null), PAGINA)
				.getContent().get(0);

		assertThat(cambio.tipo()).isEqualTo(TipoDeCambioDeAgenda.TURNO_TRASLADO);
		assertThat(cambio.antes()).startsWith("Mar 10/11 10:00 · Sede 3");
		assertThat(cambio.despues()).startsWith("Mar 10/11 11:00 · Sede 4");
		assertThat(cambio.turnoNuevoId()).isEqualTo(501L);
		assertThat(cambio.operacionId()).isEqualTo("op-1");
		assertThat(cambio.cambioDeProfesional()).isFalse();
		assertThat(cambio.eventosRelacionados()).extracting(CambioDeAgendaView.EventoRelacionadoView::id)
				.containsExactly(8L, 9L);
	}

	private static CierreDeSede cierre(long id, Set<Long> conHorario) {
		java.time.LocalDate dia = java.time.LocalDate.of(2026, 11, id == 600L ? 16 : id == 602L ? 23 : 30);
		return new CierreDeSede(id, SEDE_A, "FERIADO", dia, dia.plusDays(1), null, null, true, conHorario);
	}

	private static Instant medianoche(String fecha) {
		return java.time.LocalDate.parse(fecha).atStartOfDay(java.time.ZoneId.of("America/Argentina/Cordoba")).toInstant();
	}

	private void concede(long cuenta, String permiso, Long sede, String alcance) {
		given(permisos.evaluate(argThat((PermissionQuery q) -> q != null && q.accountId() == cuenta
				&& permiso.equals(q.permissionCode()) && java.util.Objects.equals(sede, q.consultorioId()))))
				.willReturn(PermissionDecision.concedida(alcance, false));
	}

	private AuditAgendaFilter pedidoALaAuditoria() {
		ArgumentCaptor<AuditAgendaFilter> captor = ArgumentCaptor.forClass(AuditAgendaFilter.class);
		verify(auditoria).deAgenda(captor.capture(), any());
		return captor.getValue();
	}

	private static FiltroDeHistorialDeAgenda filtro(long profesional, Long sede) {
		return new FiltroDeHistorialDeAgenda(profesional, sede, Set.of(), null, null, null);
	}

	private static OperatingActor actor(long cuenta) {
		return new OperatingActor(cuenta, false, ORG, SEDE_A);
	}

	private static ConsultorioMembershipSnapshot membership(long id, long cuenta, Long sede) {
		return new ConsultorioMembershipSnapshot(id, cuenta, ORG, sede, "PROFESIONAL", "ACTIVA",
				Instant.parse("2020-01-01T00:00:00Z"), null, true, true);
	}
}
