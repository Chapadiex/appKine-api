package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.ItemAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.scheduling.application.DisponibilidadView.HorarioDisponibleView;
import com.akine.scheduling.application.PrevisualizacionDeSerieView.OcurrenciaPrevistaView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.ReglaDeRecurrencia;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.ContextoClinicoInvalidoException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Revisar una serie antes de crearla (TURNO - Tecnico §20). No escribe nada: evalua cada ocurrencia con
 * los controles de la reserva y dice por que una no se puede, ofreciendo el horario libre mas cercano.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PrevisualizacionDeSerieService")
class PrevisualizacionDeSerieServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long OFERTA = 42L;
	private static final long PERSONA = 128L;
	private static final long PROFESIONAL = 31L;
	private static final long CASO = 9L;
	private static final long PLAN = 57L;
	private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");
	private static final LocalDate PRIMER_LUNES = LocalDate.now(ZONA).plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
	private static final ReglaDeRecurrencia TRES_LUNES = new ReglaDeRecurrencia(
			Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), PRIMER_LUNES, null, 3);

	@Mock private RevalidadorDeSlot revalidador;
	@Mock private DisponibilidadDeTurnosService disponibilidad;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private OfertaDirectory ofertas;
	@Mock private NombresDeAgenda nombres;
	@Mock private ContextoClinicoDeAgenda contextoDeAgenda;
	@Mock private SesionesDelPlan sesionesDelPlan;
	@Mock private SoporteEnAgenda soporte;

	private PrevisualizacionDeSerieService service;
	private final OperatingActor actor = new OperatingActor(99L, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new PrevisualizacionDeSerieService(revalidador, disponibilidad, consultorios, ofertas, nombres,
				new ContextoClinicoDeTurno(contextoDeAgenda), sesionesDelPlan, soporte);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", ZONA.getId(), true)));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA)).willReturn(Optional.of(oferta(true, null)));
		given(revalidador.feriadosQueCierran(anyLong(), any(), any(), any(), any(), any())).willReturn(Set.of());
		given(revalidador.evaluarSinLock(any())).willReturn(new RevalidadorDeSlot.Asignacion(PROFESIONAL, null));
		given(nombres.profesionales(anyLong(), any())).willReturn(Map.of(PROFESIONAL, "Perez, Ana"));
		given(disponibilidad.buscar(any(), anyLong(), any())).willReturn(disponibles());
	}

	@Test
	@DisplayName("todo libre: cada ocurrencia con su numero, su profesional y su box, sin sugerencias")
	void todo_disponible() {
		given(revalidador.evaluarSinLock(any())).willReturn(new RevalidadorDeSlot.Asignacion(PROFESIONAL, 55L));
		given(nombres.espacios(eq(ORG_ID), eq(List.of(55L)), any())).willReturn(Map.of(55L, "Box 1"));

		PrevisualizacionDeSerieView vista = service.previsualizar(actor, SEDE_ID, serie());

		assertThat(vista.ofertaNombre()).isEqualTo("Kinesiologia");
		assertThat(vista.pendientesDelPlan()).as("sin plan no hay tope").isNull();
		assertThat(vista.ocurrencias()).extracting(OcurrenciaPrevistaView::numero).containsExactly(1, 2, 3);
		assertThat(vista.ocurrencias()).allSatisfy(ocurrencia -> {
			assertThat(ocurrencia.estado()).isEqualTo("DISPONIBLE");
			assertThat(ocurrencia.profesionalNombre()).isEqualTo("Perez, Ana");
			assertThat(ocurrencia.espacioNombre()).isEqualTo("Box 1");
			assertThat(ocurrencia.sugerencia()).isNull();
		});
		verify(disponibilidad, never()).buscar(any(), anyLong(), any());
	}

	@Test
	@DisplayName("cada rechazo se explica en su ocurrencia y no corta la revision: no es el alta")
	void cada_ocurrencia_explica_su_rechazo() {
		given(revalidador.evaluarSinLock(any()))
				.willThrow(new RecursoOcupadoException("profesional"))
				.willThrow(new RecursoOcupadoException("espacio"))
				.willThrow(new SlotCompletoException(1));

		List<OcurrenciaPrevistaView> ocurrencias = service.previsualizar(actor, SEDE_ID, serie()).ocurrencias();

		assertThat(ocurrencias).extracting(OcurrenciaPrevistaView::estado).containsOnly("NO_DISPONIBLE");
		assertThat(ocurrencias).extracting(OcurrenciaPrevistaView::motivo).containsExactly(
				"El profesional ya tiene otro turno en ese horario.",
				"No queda ningun box libre en ese horario.",
				"Ese horario ya no tiene lugar.");
		assertThat(ocurrencias).extracting(ocurrencia -> ocurrencia.sugerencia().desde())
				.as("cada una sugiere un horario posterior al que no se puede")
				.allSatisfy(desde -> assertThat(desde).isNotNull());
	}

	@Test
	@DisplayName("un horario que dejo de existir trae el motivo de la reserva")
	void sin_hueco() {
		given(revalidador.evaluarSinLock(any())).willThrow(new SlotNoDisponibleException("el profesional ya no atiende en ese horario"));

		assertThat(service.previsualizar(actor, SEDE_ID, serie()).ocurrencias().getFirst().motivo())
				.isEqualTo("el profesional ya no atiende en ese horario");
	}

	@Test
	@DisplayName("un feriado no se evalua: se marca aparte, porque el alta lo omite y no lo rechaza")
	void feriado() {
		given(revalidador.feriadosQueCierran(anyLong(), any(), any(), eq(PROFESIONAL), eq(PRIMER_LUNES), eq(PRIMER_LUNES.plusWeeks(2).plusDays(1))))
				.willReturn(Set.of(PRIMER_LUNES.plusWeeks(1)));

		List<OcurrenciaPrevistaView> ocurrencias = service.previsualizar(actor, SEDE_ID, serie()).ocurrencias();

		assertThat(ocurrencias).extracting(OcurrenciaPrevistaView::estado).containsExactly("DISPONIBLE", "FERIADO", "DISPONIBLE");
		assertThat(ocurrencias.get(1).motivo()).contains("feriado");
		verify(revalidador, org.mockito.Mockito.times(2)).evaluarSinLock(any());
	}

	@Test
	@DisplayName("una ocurrencia pasada no se evalua, y la sugerencia es posterior a ella")
	void ocurrencia_pasada() {
		LocalDate lunesPasado = LocalDate.now(ZONA).minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		ReglaDeRecurrencia regla = new ReglaDeRecurrencia(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), lunesPasado, null, 1);

		OcurrenciaPrevistaView ocurrencia = service.previsualizar(actor, SEDE_ID,
				new AltaDeSerieCommand(OFERTA, PERSONA, PROFESIONAL, regla, null)).ocurrencias().getFirst();

		assertThat(ocurrencia.motivo()).isEqualTo("Ese horario ya paso.");
		assertThat(ocurrencia.sugerencia()).isNotNull();
		verify(revalidador, never()).evaluarSinLock(any());
	}

	@Test
	@DisplayName("una serie que cruza el fin de la vigencia muestra las ocurrencias vencidas sin caerse: no hay 409 aca")
	void serie_que_cruza_el_fin_de_la_vigencia() {
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA)).willReturn(Optional.of(oferta(true, PRIMER_LUNES.plusDays(3))));
		// Es lo que hace la busqueda real: una ventana que la oferta no toca es OfertaNoAgendable.
		given(disponibilidad.buscar(any(), anyLong(), any())).willThrow(new OfertaNoAgendableException(OFERTA, "su vigencia no toca la ventana pedida"));

		List<OcurrenciaPrevistaView> ocurrencias = service.previsualizar(actor, SEDE_ID, serie()).ocurrencias();

		assertThat(ocurrencias).extracting(OcurrenciaPrevistaView::estado).containsExactly("DISPONIBLE", "NO_DISPONIBLE", "NO_DISPONIBLE");
		assertThat(ocurrencias.get(1).motivo()).isEqualTo("La prestacion no esta vigente ese dia.");
		assertThat(ocurrencias.get(1).sugerencia()).as("sin nada que sugerir").isNull();
	}

	@Test
	@DisplayName("sin nada posterior libre, la ocurrencia queda sin sugerencia")
	void sin_sugerencia() {
		given(revalidador.evaluarSinLock(any())).willThrow(new SlotCompletoException(1));
		Instant antes = PRIMER_LUNES.minusWeeks(1).atStartOfDay(ZONA).toInstant();
		given(disponibilidad.buscar(any(), anyLong(), any())).willReturn(disponiblesEn(antes));

		assertThat(service.previsualizar(actor, SEDE_ID, serie()).ocurrencias())
				.extracting(OcurrenciaPrevistaView::sugerencia).containsOnlyNulls();
	}

	@Test
	@DisplayName("sin profesional en la serie la ocurrencia viaja sin nombre")
	void sin_profesional() {
		assertThat(service.previsualizar(actor, SEDE_ID, new AltaDeSerieCommand(OFERTA, PERSONA, null, TRES_LUNES, null)).ocurrencias())
				.extracting(OcurrenciaPrevistaView::profesionalNombre).containsOnlyNulls();
	}

	@Test
	@DisplayName("con plan informa las sesiones que le quedan a esa prestacion, para avisar antes de crear")
	void pendientes_del_plan() {
		PlanAgendable plan = dadoElPlan();
		given(sesionesDelPlan.resumir(ORG_ID, plan)).willReturn(new SesionesDelPlan.ResumenDelPlan(PLAN, List.of(
				new SesionesDelPlan.ItemDelPlan(OFERTA, SEDE_ID, "Kinesiologia", 10, 2, 3, 1, 4)), Map.of()));

		assertThat(service.previsualizar(actor, SEDE_ID, conPlan()).pendientesDelPlan()).isEqualTo(4);
	}

	@Test
	@DisplayName("si el resumen no trae la prestacion le quedan cero")
	void pendientes_de_una_prestacion_sin_item() {
		PlanAgendable plan = dadoElPlan();
		given(sesionesDelPlan.resumir(ORG_ID, plan)).willReturn(new SesionesDelPlan.ResumenDelPlan(PLAN, List.of(), Map.of()));

		assertThat(service.previsualizar(actor, SEDE_ID, conPlan()).pendientesDelPlan()).isZero();
	}

	@Test
	@DisplayName("sin contexto es 403, una sede u oferta ajena es 404, sin turno:read no se lee y un plan ajeno es 409")
	void rechazos() {
		assertThatThrownBy(() -> service.previsualizar(null, SEDE_ID, serie())).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.previsualizar(new OperatingActor(99L, false, null, null), SEDE_ID, serie()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.previsualizar(actor, 8L, serie())).isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> service.previsualizar(actor, SEDE_ID, new AltaDeSerieCommand(999L, PERSONA, PROFESIONAL, TRES_LUNES, null)))
				.isInstanceOf(OfertaNotAccessibleException.class);
		assertThatThrownBy(() -> service.previsualizar(actor, SEDE_ID, conPlan()))
				.isInstanceOf(ContextoClinicoInvalidoException.class);

		doThrow(new AccessDeniedException("sin permiso")).when(soporte).exigir(actor, PermissionCodes.TURNO_READ, ORG_ID, SEDE_ID);
		assertThatThrownBy(() -> service.previsualizar(actor, SEDE_ID, serie())).isInstanceOf(AccessDeniedException.class);
	}

	private PlanAgendable dadoElPlan() {
		PlanAgendable plan = new PlanAgendable(PLAN, CASO, PERSONA, 1, "ACTIVO", 3, 4,
				List.of(new ItemAgendable(OFERTA, SEDE_ID, "Kinesiologia", 10, 2)));
		given(contextoDeAgenda.plan(ORG_ID, PLAN)).willReturn(Optional.of(plan));
		given(contextoDeAgenda.caso(ORG_ID, CASO)).willReturn(Optional.of(
				new CasoAgendable(CASO, PERSONA, 1, OFERTA, true, null, List.of(plan))));
		return plan;
	}

	private static AltaDeSerieCommand serie() {
		return new AltaDeSerieCommand(OFERTA, PERSONA, PROFESIONAL, TRES_LUNES, null);
	}

	private static AltaDeSerieCommand conPlan() {
		return new AltaDeSerieCommand(OFERTA, PERSONA, PROFESIONAL, TRES_LUNES, null, null, null, PLAN, null);
	}

	private static DisponibilidadView disponibles() {
		return disponiblesEn(PRIMER_LUNES.plusYears(1).atStartOfDay(ZONA).toInstant());
	}

	private static DisponibilidadView disponiblesEn(Instant desde) {
		return new DisponibilidadView(OFERTA, "Kinesiologia", 45, false, ZONA.getId(), PRIMER_LUNES, false, List.of(),
				List.of(new HorarioDisponibleView(desde, desde.plusSeconds(2700), PROFESIONAL, "Perez, Ana", null, null, 1)));
	}

	private static OfertaSnapshot oferta(boolean active, LocalDate hasta) {
		return new OfertaSnapshot(OFERTA, ORG_ID, SEDE_ID, 3L, "Kinesiologia", 45, 1, false, true, false, false, false,
				PRIMER_LUNES.minusYears(1), hasta, active);
	}
}
