package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.ItemAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.scheduling.application.ContextoDeAgendaView.CasoDeAgendaView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los casos y planes de una persona para programarle turnos. Exige {@code turno:read} y no
 * {@code hc:read}: recepcion programa sin leer la historia clinica.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContextoDeAgendaService")
class ContextoDeAgendaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long OTRA_SEDE = 8L;
	private static final long PERSONA = 128L;
	private static final long OFERTA = 42L;
	private static final long RESPONSABLE = 31L;

	@Mock private ContextoClinicoDeAgenda contexto;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PacienteDirectory pacientes;
	@Mock private OfertaDirectory ofertas;
	@Mock private NombresDeAgenda nombres;
	@Mock private TurnoRepositoryPort turnos;
	@Mock private AtencionProbe atenciones;
	@Mock private SoporteEnAgenda soporte;

	private ContextoDeAgendaService service;
	private final OperatingActor actor = new OperatingActor(99L, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new ContextoDeAgendaService(contexto, new SesionesDelPlan(turnos, atenciones), consultorios,
				pacientes, ofertas, nombres, turnos, soporte);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true)));
		given(pacientes.find(ORG_ID, PERSONA)).willReturn(Optional.of(new PacienteSnapshot(
				PERSONA, ORG_ID, "Sintetico", "Paciente", null, null, null, true, true, 5L, Instant.now())));
		given(contexto.casosActivosDe(ORG_ID, PERSONA)).willReturn(List.of());
		given(nombres.profesionales(anyLong(), any())).willReturn(Map.of());
		given(ofertas.findTodas(anyLong(), anyLong(), anyList())).willReturn(Map.of());
		given(turnos.findDeLosPlanes(anyLong(), any())).willReturn(List.of());
		given(atenciones.atencionesDe(anyLong(), any())).willReturn(Map.of());
		given(turnos.findDeLaPersonaEnSede(anyLong(), anyLong(), anyLong(), anyBoolean(), any(), anyInt(), anyInt()))
				.willReturn(List.of());
	}

	@Test
	@DisplayName("sin casos es el caso normal de una primera consulta, no un error")
	void primera_consulta() {
		ContextoDeAgendaView vista = service.dePersona(actor, SEDE_ID, PERSONA);

		assertThat(vista.personaId()).isEqualTo(PERSONA);
		assertThat(vista.ultimoProfesionalId()).isNull();
		assertThat(vista.casos()).isEmpty();
	}

	@Test
	@DisplayName("cada caso trae su numero, su prestacion y su responsable, y de cada plan lo que le queda en esta sede")
	void caso_con_plan_y_conteos() {
		PlanAgendable plan = new PlanAgendable(57L, 9L, PERSONA, 2, "ACTIVO", 3, 4, List.of(
				new ItemAgendable(OFERTA, SEDE_ID, "Kinesiologia", 10, 2),
				new ItemAgendable(43L, OTRA_SEDE, "Pileta", 5, 0)));
		given(contexto.casosActivosDe(ORG_ID, PERSONA)).willReturn(List.of(
				new CasoAgendable(9L, PERSONA, 3, OFERTA, true, RESPONSABLE, List.of(plan))));
		given(nombres.profesionales(ORG_ID, Set.of(RESPONSABLE))).willReturn(Map.of(RESPONSABLE, "Perez, Ana"));
		given(ofertas.findTodas(ORG_ID, SEDE_ID, List.of(OFERTA))).willReturn(Map.of(OFERTA, oferta()));
		given(turnos.findDeLosPlanes(ORG_ID, List.of(57L))).willReturn(List.of(turno(1L, OFERTA), turno(2L, OFERTA)));

		CasoDeAgendaView caso = service.dePersona(actor, SEDE_ID, PERSONA).casos().getFirst();

		assertThat(caso.numeroCaso()).isEqualTo(3);
		assertThat(caso.ofertaNombre()).isEqualTo("Kinesiologia");
		assertThat(caso.responsableNombre()).isEqualTo("Perez, Ana");
		assertThat(caso.planes()).singleElement().satisfies(vistaDelPlan -> {
			assertThat(vistaDelPlan.numeroPlan()).isEqualTo(2);
			assertThat(vistaDelPlan.frecuenciaSemanal()).isEqualTo(3);
			assertThat(vistaDelPlan.prestaciones())
					.as("la prestacion de otra sede no se programa desde aca")
					.singleElement().satisfies(prestacion -> {
						assertThat(prestacion.planificadas()).isEqualTo(10);
						assertThat(prestacion.realizadas()).isEqualTo(2);
						assertThat(prestacion.programadas()).isEqualTo(2);
						assertThat(prestacion.pendientes()).as("el tope que aplica la reserva").isEqualTo(6);
					});
		});
	}

	@Test
	@DisplayName("un caso sin oferta, sin responsable o con una oferta de otra sede viaja sin esos nombres")
	void caso_sin_prestacion_ni_responsable() {
		given(contexto.casosActivosDe(ORG_ID, PERSONA)).willReturn(List.of(
				new CasoAgendable(9L, PERSONA, 1, null, true, null, List.of()),
				new CasoAgendable(10L, PERSONA, 2, 999L, true, null, null)));

		List<CasoDeAgendaView> casos = service.dePersona(actor, SEDE_ID, PERSONA).casos();

		assertThat(casos).extracting(CasoDeAgendaView::ofertaNombre).containsOnlyNulls();
		assertThat(casos).extracting(CasoDeAgendaView::responsableNombre).containsOnlyNulls();
		assertThat(casos.get(1).planes()).isEmpty();
	}

	@Test
	@DisplayName("el ultimo profesional sale del turno mas reciente de la persona en la sede, y se saltea uno sin profesional")
	void ultimo_profesional() {
		given(turnos.findDeLaPersonaEnSede(eq(ORG_ID), eq(SEDE_ID), eq(PERSONA), eq(false), any(), eq(0), eq(1)))
				.willReturn(List.of(turno(1L, OFERTA)));
		assertThat(service.dePersona(actor, SEDE_ID, PERSONA).ultimoProfesionalId()).isEqualTo(RESPONSABLE);

		Turno sinProfesional = turno(2L, OFERTA);
		ReflectionTestUtils.setField(sinProfesional, "profesionalMembershipId", null);
		given(turnos.findDeLaPersonaEnSede(eq(ORG_ID), eq(SEDE_ID), eq(PERSONA), eq(false), any(), eq(0), eq(1)))
				.willReturn(List.of(sinProfesional));
		assertThat(service.dePersona(actor, SEDE_ID, PERSONA).ultimoProfesionalId()).isNull();
	}

	@Test
	@DisplayName("sin contexto es 403, una sede o persona ajena es 404, y sin turno:read no se lee nada clinico")
	void rechazos() {
		assertThatThrownBy(() -> service.dePersona(null, SEDE_ID, PERSONA)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.dePersona(new OperatingActor(99L, false, null, null), SEDE_ID, PERSONA))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.dePersona(actor, OTRA_SEDE, PERSONA)).isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> service.dePersona(actor, SEDE_ID, 999L)).isInstanceOf(PersonaNotAccessibleException.class);

		doThrow(new AccessDeniedException("sin permiso")).when(soporte).exigir(actor, PermissionCodes.TURNO_READ, ORG_ID, SEDE_ID);
		assertThatThrownBy(() -> service.dePersona(actor, SEDE_ID, PERSONA)).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(contexto);
	}

	private static OfertaSnapshot oferta() {
		return new OfertaSnapshot(OFERTA, ORG_ID, SEDE_ID, 3L, "Kinesiologia", 45, 1, false, true, false, false, false,
				LocalDate.of(2020, 1, 1), null, true);
	}

	private static Turno turno(long id, long ofertaId) {
		Instant inicio = Instant.now().plus(Duration.ofDays(id));
		Turno turno = new Turno(ORG_ID, SEDE_ID, ofertaId, PERSONA, RESPONSABLE, null, inicio,
				inicio.plus(Duration.ofHours(1)), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}
}
