package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.ItemAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.application.AgendaView.DiaDeAgenda;
import com.akine.scheduling.application.AgendaView.SlotDisponible;
import com.akine.scheduling.application.ValidacionDeHorarioView.RecursoLibreView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.EspacioNoElegibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PlanSinSesionesException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.exception.VentanaDeAgendaDemasiadoAmpliaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * El motor que usan todos los puntos de entrada del flujo unico
 * (docs/diseno/AKINE-turnos-flujo-unico.md §3).
 *
 * <p>La agenda y el revalidador son dobles a proposito: las reglas de horario y de ocupacion tienen
 * sus propios tests y {@code FlujoUnicoDeTurnosIT} las recorre contra MySQL. Lo que vive aca es lo que
 * esta clase agrega encima: acercar la agenda a lo que pregunta el operador, y traducir cada rechazo a
 * una causa que la pantalla pueda explicar, siempre con 200 y alternativas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DisponibilidadDeTurnosService")
class DisponibilidadDeTurnosServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long OFERTA_ID = 42L;
	private static final long PROFESIONAL = 31L;
	private static final long OTRO_PROFESIONAL = 32L;
	private static final long PERSONA = 128L;
	private static final long CASO = 9L;
	private static final long PLAN = 57L;
	private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");

	/** Un dia futuro cualquiera: la busqueda no mira el reloj, y la validacion solo para el pasado. */
	private static final LocalDate DIA = LocalDate.now(ZONA).plusDays(7);
	private static final Instant INICIO = DIA.atTime(9, 0).atZone(ZONA).toInstant();

	@Mock private AgendaService agenda;
	@Mock private RevalidadorDeSlot revalidador;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private OfertaDirectory ofertas;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private NombresDeAgenda nombres;
	@Mock private ContextoClinicoDeAgenda contextoDeAgenda;
	@Mock private SesionesDelPlan sesionesDelPlan;
	@Mock private SoporteEnAgenda soporte;

	private DisponibilidadDeTurnosService service;
	private final OperatingActor actor = new OperatingActor(99L, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new DisponibilidadDeTurnosService(agenda, revalidador, consultorios, ofertas, memberships, nombres,
				new ContextoClinicoDeTurno(contextoDeAgenda), contextoDeAgenda, sesionesDelPlan, soporte);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", ZONA.getId(), true)));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(true, true, DIA.minusYears(1), null)));
		given(nombres.profesionales(anyLong(), any())).willReturn(Map.of());
		given(nombres.espacios(anyLong(), any(), any())).willReturn(Map.of());
		given(agenda.buscar(any(), anyLong(), anyLong(), any(), any(), any())).willReturn(agendaCon());
		given(revalidador.evaluarSinLock(any())).willReturn(new RevalidadorDeSlot.Asignacion(PROFESIONAL, null));
		given(revalidador.espaciosLibres(any())).willReturn(List.of());
	}

	// =================================================================================
	// Busqueda: lo que se puede pedir
	// =================================================================================

	@Test
	@DisplayName("sin contexto de trabajo es 403 y no 401: un 401 deja al frontend en bucle de login")
	void sin_contexto_de_trabajo() {
		OperatingActor sinContexto = new OperatingActor(99L, false, null, null);

		assertThatThrownBy(() -> service.buscar(sinContexto, SEDE_ID, busqueda(DIA, DIA.plusDays(1))))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.validar(null, SEDE_ID, validacion(PROFESIONAL)))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.profesionales(sinContexto, SEDE_ID, null))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(agenda, revalidador);
	}

	@Test
	@DisplayName("una sede de otro tenant es 404 antes del permiso: un 403 confirmaria que existe")
	void sede_de_otro_tenant() {
		assertThatThrownBy(() -> service.buscar(actor, 8L, busqueda(DIA, DIA.plusDays(1))))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		verifyNoInteractions(soporte, agenda);
	}

	@Test
	@DisplayName("una oferta que no es de esa sede es 404 en la busqueda, la validacion y los profesionales")
	void oferta_de_otra_sede() {
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(1))))
				.isInstanceOf(OfertaNotAccessibleException.class);
		assertThatThrownBy(() -> service.validar(actor, SEDE_ID, validacion(PROFESIONAL)))
				.isInstanceOf(OfertaNotAccessibleException.class);
		assertThatThrownBy(() -> service.profesionales(actor, SEDE_ID, OFERTA_ID))
				.isInstanceOf(OfertaNotAccessibleException.class);
	}

	@Test
	@DisplayName("sin turno:read no se calcula nada, y el permiso es de lectura: nada de esto reserva")
	void sin_permiso_de_lectura() {
		doThrow(new AccessDeniedException("sin permiso")).when(soporte)
				.exigir(actor, PermissionCodes.TURNO_READ, ORG_ID, SEDE_ID);

		assertThatThrownBy(() -> service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(1))))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.validar(actor, SEDE_ID, validacion(PROFESIONAL)))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(agenda, revalidador);
	}

	@Test
	@DisplayName("una oferta de baja o que no toca la ventana es 409 al buscar, con el motivo que corresponde")
	void oferta_que_no_agenda() {
		dadaLaOferta(oferta(false, true, DIA.minusYears(1), null));
		assertThatThrownBy(() -> service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(7))))
				.isInstanceOf(OfertaNoAgendableException.class)
				.hasMessageContaining("dada de baja");

		dadaLaOferta(oferta(true, true, DIA.minusYears(1), DIA.minusDays(1)));
		assertThatThrownBy(() -> service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(7))))
				.isInstanceOf(OfertaNoAgendableException.class)
				.hasMessageContaining("vigencia");

		verifyNoInteractions(agenda);
	}

	@Test
	@DisplayName("una ventana de mas de 62 dias se rechaza: el costo del motor crece con la ventana")
	void ventana_demasiado_amplia() {
		assertThatThrownBy(() -> service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(63))))
				.isInstanceOf(VentanaDeAgendaDemasiadoAmpliaException.class);
	}

	// =================================================================================
	// Busqueda: de a tandas, sin leer de mas
	// =================================================================================

	@Test
	@DisplayName("sin hasta recorre 62 dias de a tandas de 14, sin pasarse del ultimo dia")
	void recorre_la_ventana_de_a_tandas() {
		DisponibilidadView vista = service.buscar(actor, SEDE_ID, busqueda(DIA, null));

		assertThat(vista.horarios()).isEmpty();
		assertThat(vista.truncado()).isFalse();
		assertThat(vista.buscadoHasta()).isEqualTo(DIA.plusDays(62));
		verify(agenda, times(5)).buscar(any(), anyLong(), anyLong(), any(), any(), any());
		verify(agenda).buscar(actor, SEDE_ID, OFERTA_ID, DIA, DIA.plusDays(14), null);
		verify(agenda).buscar(actor, SEDE_ID, OFERTA_ID, DIA.plusDays(56), DIA.plusDays(62), null);
	}

	@Test
	@DisplayName("sin desde arranca hoy en la zona de la sede, no en UTC")
	void sin_desde_arranca_hoy_en_la_sede() {
		service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(OFERTA_ID, PROFESIONAL, null, null, null, null, null, 1));

		LocalDate hoy = LocalDate.now(ZONA);
		verify(agenda).buscar(actor, SEDE_ID, OFERTA_ID, hoy, hoy.plusDays(14), PROFESIONAL);
	}

	@Test
	@DisplayName("no le pide a la agenda las tandas en las que la oferta ya vencio")
	void no_pide_tandas_sin_vigencia() {
		dadaLaOferta(oferta(true, true, DIA.minusYears(1), DIA.plusDays(15)));

		service.buscar(actor, SEDE_ID, busqueda(DIA, null));

		verify(agenda, times(2)).buscar(any(), anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	@DisplayName("con limite 1 es el primer disponible: corta en esa tanda y avisa que hay mas")
	void primer_disponible() {
		dadaLaAgenda(dia(DIA, null, slot(DIA, 9), slot(DIA, 10)));

		DisponibilidadView vista = service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(
				OFERTA_ID, null, DIA, null, null, null, null, 1));

		assertThat(vista.horarios()).extracting(horario -> horario.desde()).containsExactly(INICIO);
		assertThat(vista.truncado()).isTrue();
		verify(agenda, times(1)).buscar(any(), anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	@DisplayName("el limite vale 20 si no se pide y no pasa de 200 aunque se pida mas")
	void limites_de_la_respuesta() {
		SlotDisponible[] muchos = IntStream.range(0, 201)
				.mapToObj(minutos -> new SlotDisponible(INICIO.plusSeconds(minutos * 60L),
						INICIO.plusSeconds(minutos * 60L + 2700), PROFESIONAL, null, 1, 1))
				.toArray(SlotDisponible[]::new);
		dadaLaAgenda(dia(DIA, null, muchos));

		assertThat(service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(1))).horarios()).hasSize(20);
		assertThat(service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(
				OFERTA_ID, null, DIA, DIA.plusDays(1), null, null, null, 5000)).horarios()).hasSize(200);
	}

	// =================================================================================
	// Busqueda: lo que se ofrece
	// =================================================================================

	@Test
	@DisplayName("un slot sin cupo no se ofrece, y los motivos de los dias vacios llegan sin repetir")
	void cupo_y_motivos() {
		dadaLaAgenda(
				dia(DIA, "FERIADO"),
				dia(DIA.plusDays(1), null, new SlotDisponible(hora(DIA.plusDays(1), 9), hora(DIA.plusDays(1), 10), PROFESIONAL, null, 1, 0),
						slot(DIA.plusDays(1), 10)),
				dia(DIA.plusDays(2), "FERIADO"),
				dia(DIA.plusDays(3), "COMPLETO"));

		DisponibilidadView vista = service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(4)));

		assertThat(vista.horarios()).extracting(horario -> horario.desde()).containsExactly(hora(DIA.plusDays(1), 10));
		assertThat(vista.motivosSinHorario()).containsExactly("FERIADO", "COMPLETO");
	}

	@Test
	@DisplayName("los dias pedidos filtran los horarios, y el motivo de un dia descartado se informa igual")
	void solo_los_dias_pedidos() {
		DayOfWeek diaPedido = DIA.getDayOfWeek();
		dadaLaAgenda(dia(DIA, null, slot(DIA, 9)), dia(DIA.plusDays(1), "CIERRE", slot(DIA.plusDays(1), 9)));

		DisponibilidadView vista = service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(
				OFERTA_ID, null, DIA, DIA.plusDays(2), null, null, Set.of(diaPedido), 10));

		assertThat(vista.horarios()).extracting(horario -> horario.desde()).containsExactly(INICIO);
		assertThat(vista.motivosSinHorario()).containsExactly("CIERRE");
	}

	@Test
	@DisplayName("la franja mira el inicio: un turno que arranca adentro y cruza la hora pedida se ofrece, y uno que arranca justo ahi no")
	void franja_horaria() {
		Instant onceYMedia = DIA.atTime(11, 30).atZone(ZONA).toInstant();
		dadaLaAgenda(dia(DIA, null, slot(DIA, 9), slot(DIA, 10),
				new SlotDisponible(onceYMedia, DIA.atTime(12, 15).atZone(ZONA).toInstant(), PROFESIONAL, null, 1, 1),
				slot(DIA, 12)));

		DisponibilidadView vista = service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(
				OFERTA_ID, null, DIA, DIA.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), null, 10));

		assertThat(vista.horarios()).extracting(horario -> horario.desde()).containsExactly(hora(DIA, 10), onceYMedia);
	}

	@Test
	@DisplayName("mañana y tarde no dejan un horario afuera: el de 12:45 a 13:30 esta en la mañana hasta las 13")
	void un_turno_del_mediodia_no_se_pierde_entre_las_dos_franjas() {
		Instant mediodia = DIA.atTime(12, 45).atZone(ZONA).toInstant();
		dadaLaAgenda(dia(DIA, null, new SlotDisponible(mediodia, DIA.atTime(13, 30).atZone(ZONA).toInstant(), PROFESIONAL, null, 1, 1)));

		assertThat(service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(OFERTA_ID, null, DIA, DIA.plusDays(1),
				null, LocalTime.of(13, 0), null, 10)).horarios()).hasSize(1);
		assertThat(service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(OFERTA_ID, null, DIA, DIA.plusDays(1),
				LocalTime.of(13, 0), null, null, 10)).horarios()).as("no aparece dos veces").isEmpty();
	}

	@Test
	@DisplayName("un turno que termina a medianoche se ve en una franja de la noche: se compara por su inicio")
	void fin_a_medianoche() {
		Instant onceDeLaNoche = DIA.atTime(23, 0).atZone(ZONA).toInstant();
		dadaLaAgenda(dia(DIA, null, new SlotDisponible(onceDeLaNoche,
				DIA.plusDays(1).atStartOfDay(ZONA).toInstant(), PROFESIONAL, null, 1, 1)));

		assertThat(service.buscar(actor, SEDE_ID, new BusquedaDeDisponibilidad(OFERTA_ID, null, DIA, DIA.plusDays(1),
				LocalTime.of(22, 0), LocalTime.of(23, 30), null, 10)).horarios()).hasSize(1);
	}

	@Test
	@DisplayName("cada horario viaja con el nombre del profesional y del box; sin ellos, sin nombre")
	void horarios_con_nombres() {
		given(nombres.profesionales(ORG_ID, Set.of(PROFESIONAL))).willReturn(Map.of(PROFESIONAL, "Perez, Ana"));
		given(nombres.espacios(eq(ORG_ID), eq(Set.of(55L)), eq(INICIO))).willReturn(Map.of(55L, "Box 1"));
		dadaLaAgenda(dia(DIA, null,
				new SlotDisponible(INICIO, INICIO.plusSeconds(2700), PROFESIONAL, 55L, 2, 1),
				new SlotDisponible(hora(DIA, 10), hora(DIA, 11), null, null, 1, 1)));

		var horarios = service.buscar(actor, SEDE_ID, busqueda(DIA, DIA.plusDays(1))).horarios();

		assertThat(horarios.get(0).profesionalNombre()).isEqualTo("Perez, Ana");
		assertThat(horarios.get(0).espacioNombre()).isEqualTo("Box 1");
		assertThat(horarios.get(0).cupoLibre()).isEqualTo(1);
		assertThat(horarios.get(1).profesionalNombre()).isNull();
		assertThat(horarios.get(1).espacioNombre()).isNull();
	}

	// =================================================================================
	// Validacion: se puede
	// =================================================================================

	@Test
	@DisplayName("un horario libre devuelve quien atiende y que boxes hay, y no toca nada: evalua sin lock")
	void horario_disponible() {
		given(nombres.profesionales(ORG_ID, Set.of(PROFESIONAL))).willReturn(Map.of(PROFESIONAL, "Perez, Ana"));
		given(revalidador.espaciosLibres(any())).willReturn(List.of(new EspacioSnapshot(
				55L, ORG_ID, SEDE_ID, "Box 1", "BOX", 1, Instant.EPOCH, null, true, true)));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, new ValidacionDeHorarioCommand(
				OFERTA_ID, INICIO, PROFESIONAL, 55L, null, null, null, 300L));

		assertThat(vista.estado()).isEqualTo("DISPONIBLE");
		assertThat(vista.causa()).isNull();
		assertThat(vista.fin()).isEqualTo(INICIO.plus(Duration.ofMinutes(45)));
		assertThat(vista.profesionales()).containsExactly(new RecursoLibreView(PROFESIONAL, "Perez, Ana"));
		assertThat(vista.espaciosLibres()).containsExactly(new RecursoLibreView(55L, "Box 1"));
		assertThat(vista.alternativas()).isEmpty();

		ArgumentCaptor<RevalidadorDeSlot.Pedido> pedido = ArgumentCaptor.forClass(RevalidadorDeSlot.Pedido.class);
		verify(revalidador).evaluarSinLock(pedido.capture());
		assertThat(pedido.getValue().turnoExcluidoId())
				.as("el turno que se mueve no choca contra si mismo").isEqualTo(300L);
		assertThat(pedido.getValue().espacioElegidoId()).isEqualTo(55L);
		assertThat(pedido.getValue().sobreturno()).isFalse();
		verify(revalidador, never()).revalidar(any());
		verify(revalidador, never()).revalidarBajoLock(any(), any());
	}

	@Test
	@DisplayName("sin profesional pregunta por cada habilitado vigente, y devuelve solo los que estan libres")
	void cualquier_profesional() {
		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of(
				habilitacion(PROFESIONAL, Instant.EPOCH, null),
				habilitacion(OTRO_PROFESIONAL, Instant.EPOCH, null),
				habilitacion(33L, Instant.EPOCH, Instant.EPOCH.plusSeconds(60))));
		given(revalidador.evaluarSinLock(any())).willAnswer(invocacion -> {
			RevalidadorDeSlot.Pedido pedido = invocacion.getArgument(0);
			if (pedido.profesionalId() == PROFESIONAL) {
				throw new RecursoOcupadoException("profesional");
			}
			return new RevalidadorDeSlot.Asignacion(pedido.profesionalId(), null);
		});

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(null));

		assertThat(vista.estado()).isEqualTo("DISPONIBLE");
		assertThat(vista.profesionales())
				.as("el habilitado vencido no se evalua, y sin nombre resuelto se muestra el id")
				.containsExactly(new RecursoLibreView(OTRO_PROFESIONAL, "#" + OTRO_PROFESIONAL));
		verify(revalidador, times(2)).evaluarSinLock(any());
	}

	@Test
	@DisplayName("la habilitacion se mira en el horario pedido y no hoy: la que arranca antes del turno sirve")
	void habilitacion_vigente_en_el_horario_pedido() {
		// Lo mismo que hacen la agenda y el revalidador: si no, la busqueda ofrece el horario y la
		// validacion de "cualquier profesional" lo rechaza.
		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of(
				habilitacion(PROFESIONAL, INICIO.minus(Duration.ofDays(1)), null)));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(null));

		assertThat(vista.estado()).isEqualTo("DISPONIBLE");
		assertThat(vista.profesionales()).extracting(RecursoLibreView::id).containsExactly(PROFESIONAL);
	}

	@Test
	@DisplayName("una oferta sin habilitaciones declaradas la prestan todos los profesionales de la sede")
	void sin_habilitaciones_son_todos() {
		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of());
		given(memberships.findPorRolEnSede(ORG_ID, SEDE_ID, "PROFESIONAL")).willReturn(List.of(membership(PROFESIONAL)));

		assertThat(service.validar(actor, SEDE_ID, validacion(null)).profesionales())
				.extracting(RecursoLibreView::id).containsExactly(PROFESIONAL);
	}

	@Test
	@DisplayName("una oferta que no lleva profesional evalua una sola vez y no devuelve profesionales")
	void oferta_sin_profesional() {
		dadaLaOferta(oferta(true, false, DIA.minusYears(1), null));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(null));

		assertThat(vista.estado()).isEqualTo("DISPONIBLE");
		assertThat(vista.profesionales()).isEmpty();
		verify(ofertas, never()).profesionalesHabilitados(anyLong(), anyLong(), anyLong());
	}

	// =================================================================================
	// Validacion: no se puede, y por que. Siempre 200: la pantalla explica y ofrece otra cosa
	// =================================================================================

	static Stream<Arguments> rechazos() {
		return Stream.of(
				Arguments.of(new RecursoOcupadoException("profesional"), "PROFESIONAL_OCUPADO",
						"El profesional ya tiene otro turno en ese horario."),
				Arguments.of(new RecursoOcupadoException("espacio"), "ESPACIO_OCUPADO",
						"No queda ningun box libre en ese horario."),
				Arguments.of(new SlotCompletoException(1), "SLOT_COMPLETO", "Ese horario ya no tiene lugar."),
				Arguments.of(new SlotNoDisponibleException("el profesional ya no atiende en ese horario"),
						"SLOT_NO_DISPONIBLE", "el profesional ya no atiende en ese horario"),
				Arguments.of(new EspacioNoElegibleException(55L), "SLOT_NO_DISPONIBLE",
						"Ese horario no se puede reservar."));
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("rechazos")
	@DisplayName("cada rechazo de la reserva se traduce a su causa, con alternativas reales")
	void rechazos_con_causa_y_alternativas(RuntimeException rechazo, String causa, String motivo) {
		given(revalidador.evaluarSinLock(any())).willThrow(rechazo);
		dadaLaAgenda(dia(DIA, null, slot(DIA, 10), slot(DIA, 11), slot(DIA, 12), slot(DIA, 13)));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(PROFESIONAL));

		assertThat(vista.estado()).isEqualTo("NO_DISPONIBLE");
		assertThat(vista.causa()).isEqualTo(causa);
		assertThat(vista.motivo()).isEqualTo(motivo);
		assertThat(vista.profesionales()).isEmpty();
		assertThat(vista.alternativas())
				.as("tres, del mismo profesional y desde el mismo dia")
				.extracting(horario -> horario.desde())
				.containsExactly(hora(DIA, 10), hora(DIA, 11), hora(DIA, 12));
		verify(agenda).buscar(actor, SEDE_ID, OFERTA_ID, DIA, DIA.plusDays(14), PROFESIONAL);
	}

	@Test
	@DisplayName("un profesional ocupado por un turno devuelve con quien choca, para mostrarlo en el panel")
	void conflicto_con_datos_del_turno() {
		Instant choqueFin = INICIO.plus(Duration.ofHours(1));
		given(revalidador.evaluarSinLock(any())).willThrow(
				new RecursoOcupadoException("profesional", 300L, INICIO, choqueFin));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(PROFESIONAL));

		assertThat(vista.turnoEnConflictoId()).isEqualTo(300L);
		assertThat(vista.conflictoInicio()).isEqualTo(INICIO);
		assertThat(vista.conflictoFin()).isEqualTo(choqueFin);
	}

	@Test
	@DisplayName("si nadie puede atender el horario no se puede, y no se le pregunta al revalidador")
	void nadie_habilitado() {
		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of(
				habilitacion(PROFESIONAL, Instant.EPOCH, Instant.EPOCH.plusSeconds(60))));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(null));

		assertThat(vista.causa()).isEqualTo("SLOT_NO_DISPONIBLE");
		verifyNoInteractions(revalidador);
	}

	@Test
	@DisplayName("un horario pasado no se evalua y ofrece lo que viene")
	void horario_pasado() {
		Instant ayer = Instant.now().minus(Duration.ofDays(1));
		LocalDate fecha = ayer.atZone(ZONA).toLocalDate();
		given(agenda.buscar(actor, SEDE_ID, OFERTA_ID, fecha, fecha.plusDays(14), PROFESIONAL))
				.willReturn(agendaCon(dia(DIA, null, slot(DIA, 9))));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID,
				new ValidacionDeHorarioCommand(OFERTA_ID, ayer, PROFESIONAL, null, null, null, null, null));

		assertThat(vista.causa()).isEqualTo("PASADO");
		assertThat(vista.alternativas()).hasSize(1);
		verifyNoInteractions(revalidador);
	}

	@Test
	@DisplayName("una oferta de baja responde 200 con NO_DISPONIBLE y sin alternativas: el contrato no publica 409")
	void oferta_de_baja_en_la_validacion() {
		dadaLaOferta(oferta(false, true, DIA.minusYears(1), null));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(PROFESIONAL));

		assertThat(vista.estado()).isEqualTo("NO_DISPONIBLE");
		assertThat(vista.causa()).isEqualTo("OFERTA_NO_AGENDABLE");
		assertThat(vista.motivo()).isEqualTo("La prestacion esta dada de baja.");
		assertThat(vista.alternativas()).isEmpty();
	}

	@Test
	@DisplayName("una oferta vencida tampoco es un 409: sin nada que ofrecer, la lista de alternativas va vacia")
	void oferta_vencida_en_la_validacion() {
		dadaLaOferta(oferta(true, true, DIA.minusYears(1), DIA.minusDays(1)));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(PROFESIONAL));

		assertThat(vista.causa()).isEqualTo("OFERTA_NO_AGENDABLE");
		assertThat(vista.motivo()).isEqualTo("La prestacion no esta vigente ese dia.");
		assertThat(vista.alternativas()).isEmpty();
	}

	@Test
	@DisplayName("una oferta que todavia no empieza ofrece lo que hay cuando empiece")
	void oferta_que_todavia_no_empieza() {
		dadaLaOferta(oferta(true, true, DIA.plusDays(3), null));
		given(agenda.buscar(actor, SEDE_ID, OFERTA_ID, DIA, DIA.plusDays(14), PROFESIONAL))
				.willReturn(agendaCon(dia(DIA.plusDays(3), null, slot(DIA.plusDays(3), 9))));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, validacion(PROFESIONAL));

		assertThat(vista.causa()).isEqualTo("OFERTA_NO_AGENDABLE");
		assertThat(vista.alternativas()).hasSize(1);
	}

	// =================================================================================
	// Validacion: caso y plan
	// =================================================================================

	@Test
	@DisplayName("sin persona no se mira el contexto clinico, aunque venga un caso")
	void sin_persona_no_hay_contexto() {
		service.validar(actor, SEDE_ID, new ValidacionDeHorarioCommand(
				OFERTA_ID, INICIO, PROFESIONAL, null, null, CASO, null, null));

		verifyNoInteractions(contextoDeAgenda, sesionesDelPlan);
	}

	@Test
	@DisplayName("un plan de otro paciente responde CONTEXTO_CLINICO y sin alternativas: ningun horario lo arregla")
	void plan_de_otro_paciente() {
		dadoElPlan(999L);

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, conPlan());

		assertThat(vista.causa()).isEqualTo("CONTEXTO_CLINICO");
		assertThat(vista.motivo()).contains("otro paciente");
		assertThat(vista.alternativas()).isEmpty();
		verifyNoInteractions(agenda, revalidador);
	}

	@Test
	@DisplayName("el tope del plan es CONTEXTO_CLINICO: no hay override, quien necesita mas modifica el plan")
	void plan_sin_sesiones() {
		PlanAgendable plan = dadoElPlan(PERSONA);
		doThrow(new PlanSinSesionesException(PLAN, 0, 1)).when(sesionesDelPlan)
				.exigirDisponibles(ORG_ID, plan, OFERTA_ID, 1);

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, conPlan());

		assertThat(vista.causa()).isEqualTo("CONTEXTO_CLINICO");
		assertThat(vista.motivo()).contains("sesiones pendientes");
		verifyNoInteractions(revalidador);
	}

	@Test
	@DisplayName("un profesional distinto del responsable es aviso y no bloqueo")
	void responsable_distinto() {
		given(contextoDeAgenda.caso(ORG_ID, CASO)).willReturn(Optional.of(caso(OTRO_PROFESIONAL)));

		ValidacionDeHorarioView vista = service.validar(actor, SEDE_ID, conCaso(PROFESIONAL));

		assertThat(vista.estado()).isEqualTo("DISPONIBLE_CON_AVISO");
		assertThat(vista.avisos()).singleElement().asString().contains("responsable");
		assertThat(vista.profesionales()).hasSize(1);
	}

	@Test
	@DisplayName("con el responsable, con un caso sin responsable o con cualquier profesional no hay aviso")
	void sin_aviso_de_responsable() {
		given(contextoDeAgenda.caso(ORG_ID, CASO)).willReturn(Optional.of(caso(PROFESIONAL)));
		assertThat(service.validar(actor, SEDE_ID, conCaso(PROFESIONAL)).estado()).isEqualTo("DISPONIBLE");

		given(contextoDeAgenda.caso(ORG_ID, CASO)).willReturn(Optional.of(caso(null)));
		assertThat(service.validar(actor, SEDE_ID, conCaso(PROFESIONAL)).estado()).isEqualTo("DISPONIBLE");

		given(contextoDeAgenda.caso(ORG_ID, CASO)).willReturn(Optional.of(caso(OTRO_PROFESIONAL)));
		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of(
				habilitacion(PROFESIONAL, Instant.EPOCH, null)));
		assertThat(service.validar(actor, SEDE_ID, conCaso(null)).estado()).isEqualTo("DISPONIBLE");
	}

	// =================================================================================
	// Quien puede atender
	// =================================================================================

	@Test
	@DisplayName("sin oferta son todos los de la sede, por nombre, y sin nombre resuelto se muestra el id")
	void profesionales_de_la_sede() {
		given(memberships.findPorRolEnSede(ORG_ID, SEDE_ID, "PROFESIONAL"))
				.willReturn(List.of(membership(PROFESIONAL), membership(OTRO_PROFESIONAL), membership(33L)));
		given(nombres.profesionales(ORG_ID, List.of(PROFESIONAL, OTRO_PROFESIONAL, 33L)))
				.willReturn(Map.of(PROFESIONAL, "Zapata, Ana", OTRO_PROFESIONAL, "Alvarez, Ema"));

		assertThat(service.profesionales(actor, SEDE_ID, null)).containsExactly(
				new RecursoLibreView(33L, "#33"),
				new RecursoLibreView(OTRO_PROFESIONAL, "Alvarez, Ema"),
				new RecursoLibreView(PROFESIONAL, "Zapata, Ana"));
	}

	@Test
	@DisplayName("con oferta son los habilitados vigentes, sin repetir; sin habilitaciones declaradas, todos")
	void profesionales_de_la_oferta() {
		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of(
				habilitacion(PROFESIONAL, Instant.EPOCH, null),
				habilitacion(PROFESIONAL, Instant.EPOCH, null),
				habilitacion(OTRO_PROFESIONAL, Instant.EPOCH, Instant.EPOCH.plusSeconds(60))));
		assertThat(service.profesionales(actor, SEDE_ID, OFERTA_ID))
				.extracting(RecursoLibreView::id).containsExactly(PROFESIONAL);

		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of());
		given(memberships.findPorRolEnSede(ORG_ID, SEDE_ID, "PROFESIONAL")).willReturn(List.of(membership(33L)));
		assertThat(service.profesionales(actor, SEDE_ID, OFERTA_ID))
				.extracting(RecursoLibreView::id).containsExactly(33L);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private void dadaLaOferta(OfertaSnapshot oferta) {
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta));
	}

	private void dadaLaAgenda(DiaDeAgenda... dias) {
		given(agenda.buscar(any(), anyLong(), anyLong(), any(), any(), any())).willReturn(agendaCon(dias));
	}

	private PlanAgendable dadoElPlan(long personaId) {
		PlanAgendable plan = new PlanAgendable(PLAN, CASO, personaId, 1, "ACTIVO", 3, 4,
				List.of(new ItemAgendable(OFERTA_ID, SEDE_ID, "Kinesiologia", 10, 0)));
		given(contextoDeAgenda.plan(ORG_ID, PLAN)).willReturn(Optional.of(plan));
		given(contextoDeAgenda.caso(ORG_ID, CASO)).willReturn(Optional.of(caso(null)));
		return plan;
	}

	private static CasoAgendable caso(Long responsable) {
		return new CasoAgendable(CASO, PERSONA, 1, OFERTA_ID, true, responsable, List.of());
	}

	private static BusquedaDeDisponibilidad busqueda(LocalDate desde, LocalDate hasta) {
		return new BusquedaDeDisponibilidad(OFERTA_ID, null, desde, hasta, null, null, null, null);
	}

	private static ValidacionDeHorarioCommand validacion(Long profesionalId) {
		return new ValidacionDeHorarioCommand(OFERTA_ID, INICIO, profesionalId, null, null, null, null, null);
	}

	private static ValidacionDeHorarioCommand conPlan() {
		return new ValidacionDeHorarioCommand(OFERTA_ID, INICIO, PROFESIONAL, null, PERSONA, null, PLAN, null);
	}

	private static ValidacionDeHorarioCommand conCaso(Long profesionalId) {
		return new ValidacionDeHorarioCommand(OFERTA_ID, INICIO, profesionalId, null, PERSONA, CASO, null, null);
	}

	private static OfertaSnapshot oferta(boolean active, boolean requiereProfesional, LocalDate desde, LocalDate hasta) {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, SEDE_ID, 3L, "Kinesiologia", 45, 1, false,
				requiereProfesional, false, false, false, desde, hasta, active);
	}

	private static HabilitacionSnapshot habilitacion(long profesional, Instant desde, Instant hasta) {
		return new HabilitacionSnapshot(1L, profesional, desde, hasta, true);
	}

	private static ConsultorioMembershipSnapshot membership(long id) {
		return new ConsultorioMembershipSnapshot(id, id + 100, ORG_ID, SEDE_ID, "PROFESIONAL", "ACTIVA",
				Instant.EPOCH, null, true, true);
	}

	private static AgendaView agendaCon(DiaDeAgenda... dias) {
		return new AgendaView(OFERTA_ID, SEDE_ID, "Kinesiologia", 45, ZONA.getId(), new ArrayList<>(Arrays.asList(dias)));
	}

	private static DiaDeAgenda dia(LocalDate fecha, String motivo, SlotDisponible... slots) {
		return new DiaDeAgenda(fecha, motivo, List.of(slots));
	}

	private static SlotDisponible slot(LocalDate fecha, int hora) {
		return new SlotDisponible(hora(fecha, hora), hora(fecha, hora).plusSeconds(2700), PROFESIONAL, null, 1, 1);
	}

	private static Instant hora(LocalDate fecha, int hora) {
		return fecha.atTime(hora, 0).atZone(ZONA).toInstant();
	}
}
