package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.DisponibilidadDirectory.CierreDeAgenda;
import com.akine.resource.spi.DisponibilidadDirectory.DiaDisponible;
import com.akine.resource.spi.DisponibilidadDirectory.Franja;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.application.AgendaOperativaView.BloqueoEnAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.ProfesionalDeAgendaView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.VentanaDeAgendaDemasiadoAmpliaException;
import com.akine.scheduling.spi.EventoExternoDeAgenda;
import com.akine.scheduling.spi.EventoExternoDeAgenda.EventoDeAgendaExterno;
import com.akine.testsupport.Fixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** La agenda que se opera: quien atiende, que boxes hay, los turnos, las clases y los bloqueos (TURNO - Tecnico §14). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgendaOperativaService")
class AgendaOperativaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long ANA = 31L;
	private static final long EMA = 32L;
	private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final LocalDate MARTES = LUNES.plusDays(1);
	private static final LocalDate DOMINGO = LUNES.plusDays(6);

	@Mock private RecepcionService recepcion;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private DisponibilidadDirectory disponibilidad;
	@Mock private EspacioDirectory espacios;
	@Mock private EventoExternoDeAgenda eventosExternos;
	@Mock private NombresDeAgenda nombres;
	@Mock private SoporteEnAgenda soporte;

	private AgendaOperativaService service;
	private final OperatingActor actor = new OperatingActor(99L, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new AgendaOperativaService(recepcion, consultorios, memberships, disponibilidad, espacios,
				eventosExternos, nombres, soporte);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", ZONA.getId(), true)));
		dadosLosTurnos();
		given(memberships.findPorRolEnSede(ORG_ID, SEDE_ID, "PROFESIONAL")).willReturn(List.of());
		given(nombres.profesionales(anyLong(), any())).willReturn(Map.of());
		given(disponibilidad.efectiva(anyLong(), any(), anyLong(), any(), any())).willReturn(List.of());
		given(disponibilidad.cierresEnVentana(anyLong(), anyLong(), any(), any())).willReturn(List.of());
		given(espacios.enServicio(anyLong(), anyLong(), any(), any())).willReturn(List.of());
		given(eventosExternos.enVentana(anyLong(), anyLong(), any(), any())).willReturn(List.of());
	}

	@Test
	@DisplayName("una columna por profesional, por nombre y con sus franjas; el que dejo el rol pero tiene turnos sigue apareciendo")
	void columnas_de_profesionales() {
		given(memberships.findPorRolEnSede(ORG_ID, SEDE_ID, "PROFESIONAL")).willReturn(List.of(membership(ANA), membership(EMA)));
		dadosLosTurnos(turnoDe(50L, 33L, null));
		given(nombres.profesionales(ORG_ID, Set.of(ANA, EMA, 33L))).willReturn(Map.of(ANA, "Zapata, Ana", EMA, "Alvarez, Ema"));
		Instant nueve = LUNES.atTime(9, 0).atZone(ZONA).toInstant();
		Instant trece = LUNES.atTime(13, 0).atZone(ZONA).toInstant();
		given(disponibilidad.efectiva(ORG_ID, sede(), ANA, LUNES, MARTES))
				.willReturn(List.of(new DiaDisponible(LUNES, null, List.of(new Franja(nueve, trece)))));

		AgendaOperativaView vista = ver(LUNES, MARTES, null, null);

		assertThat(vista.timezone()).isEqualTo(ZONA.getId());
		assertThat(vista.profesionales()).extracting(ProfesionalDeAgendaView::nombre)
				.as("sin nombre resuelto se muestra el id, para no perder la fila")
				.containsExactly("#33", "Alvarez, Ema", "Zapata, Ana");
		assertThat(vista.profesionales().get(2).franjas()).singleElement().satisfies(franja -> {
			assertThat(franja.desde()).isEqualTo(nueve);
			assertThat(franja.hasta()).isEqualTo(trece);
		});
	}

	@Test
	@DisplayName("con profesional se acotan columnas y clases a el, y no se leen los de la sede")
	void acotada_a_un_profesional() {
		given(eventosExternos.enVentana(anyLong(), anyLong(), any(), any())).willReturn(List.of(
				clase(1L, "PROGRAMADA", ANA), clase(2L, "PROGRAMADA", EMA)));

		AgendaOperativaView vista = ver(LUNES, MARTES, ANA, null);

		assertThat(vista.profesionales()).extracting(ProfesionalDeAgendaView::membershipId).containsExactly(ANA);
		assertThat(vista.clases()).singleElement().satisfies(clase -> {
			assertThat(clase.claseId()).isEqualTo(1L);
			assertThat(clase.titulo()).isEqualTo("Pilates");
			assertThat(clase.ocupados()).isEqualTo(3);
		});
		verify(memberships, never()).findPorRolEnSede(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("el box acota los turnos; una clase cancelada no ocupa la agenda")
	void filtro_por_box_y_clases_canceladas() {
		dadosLosTurnos(turnoDe(50L, ANA, 55L), turnoDe(51L, ANA, 56L), turnoDe(52L, ANA, null));
		given(eventosExternos.enVentana(anyLong(), anyLong(), any(), any())).willReturn(List.of(
				clase(1L, "CANCELADA", ANA), clase(2L, "PROGRAMADA", null)));

		AgendaOperativaView vista = ver(LUNES, MARTES, null, 55L);

		assertThat(vista.turnos()).extracting(fila -> fila.turno().id()).containsExactly(50L);
		assertThat(vista.clases()).extracting(clase -> clase.claseId()).containsExactly(2L);
	}

	@Test
	@DisplayName("los boxes son los activos y en servicio, por nombre")
	void boxes_en_servicio() {
		given(espacios.enServicio(anyLong(), anyLong(), any(), any())).willReturn(List.of(
				espacio(1L, "Box 2", true, true), espacio(2L, "Box 1", true, true),
				espacio(3L, "De baja", false, true), espacio(4L, "En obra", true, false)));

		assertThat(ver(LUNES, MARTES, null, null).espacios()).extracting(espacio -> espacio.nombre())
				.containsExactly("Box 1", "Box 2");
	}

	@Test
	@DisplayName("un bloqueo con horas se repite cada dia dentro de la ventana, y fechaHasta es exclusiva")
	void bloqueo_con_horas() {
		given(disponibilidad.cierresEnVentana(ORG_ID, SEDE_ID, LUNES, LUNES.plusDays(3))).willReturn(List.of(
				new CierreDeAgenda(1L, ANA, "CAPACITACION", LUNES.minusDays(2), MARTES.plusDays(1),
						LocalTime.of(10, 0), LocalTime.of(12, 0), "Curso")));

		List<BloqueoEnAgendaView> bloqueos = ver(LUNES, LUNES.plusDays(3), null, null).bloqueos();

		assertThat(bloqueos).extracting(BloqueoEnAgendaView::desde).containsExactly(
				LUNES.atTime(10, 0).atZone(ZONA).toInstant(), MARTES.atTime(10, 0).atZone(ZONA).toInstant());
		assertThat(bloqueos.getFirst().hasta()).isEqualTo(LUNES.atTime(12, 0).atZone(ZONA).toInstant());
		assertThat(bloqueos.getFirst().motivo()).isEqualTo("CAPACITACION");
		assertThat(bloqueos.getFirst().notas()).isEqualTo("Curso");
	}

	@Test
	@DisplayName("un bloqueo de dia completo, o hasta medianoche, termina al empezar el dia siguiente")
	void bloqueo_de_dia_completo() {
		given(disponibilidad.cierresEnVentana(ORG_ID, SEDE_ID, LUNES, MARTES)).willReturn(List.of(
				new CierreDeAgenda(1L, null, "FERIADO", LUNES, MARTES, null, null, null),
				new CierreDeAgenda(2L, ANA, "LICENCIA", LUNES, MARTES, LocalTime.of(15, 0), LocalTime.MIDNIGHT, null),
				new CierreDeAgenda(3L, ANA, "LICENCIA", LUNES, MARTES, LocalTime.of(15, 0), LocalTime.MAX, null)));

		List<BloqueoEnAgendaView> bloqueos = ver(LUNES, MARTES, null, null).bloqueos();

		Instant empiezaElDia = LUNES.atStartOfDay(ZONA).toInstant();
		Instant terminaElDia = MARTES.atStartOfDay(ZONA).toInstant();
		assertThat(bloqueos.get(0).desde()).isEqualTo(empiezaElDia);
		assertThat(bloqueos).extracting(BloqueoEnAgendaView::hasta).containsOnly(terminaElDia);
	}

	@Test
	@DisplayName("acotada a un profesional muestra sus bloqueos y los de la sede, no los de otro")
	void bloqueos_de_un_profesional() {
		given(disponibilidad.cierresEnVentana(ORG_ID, SEDE_ID, LUNES, MARTES)).willReturn(List.of(
				new CierreDeAgenda(1L, null, "FERIADO", LUNES, MARTES, null, null, null),
				new CierreDeAgenda(2L, ANA, "LICENCIA", LUNES, MARTES, null, null, null),
				new CierreDeAgenda(3L, EMA, "LICENCIA", LUNES, MARTES, null, null, null)));

		assertThat(ver(LUNES, MARTES, ANA, null).bloqueos()).extracting(BloqueoEnAgendaView::excepcionId)
				.containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("sin contexto es 403, una sede ajena es 404, sin turno:read no se lee, y la ventana no pasa de 14 dias")
	void rechazos() {
		assertThatThrownBy(() -> service.ver(null, SEDE_ID, LUNES, MARTES, null, null, null, false))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.ver(new OperatingActor(99L, false, null, null), SEDE_ID, LUNES, MARTES, null, null, null, false))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.ver(actor, 8L, LUNES, MARTES, null, null, null, false))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> ver(LUNES, DOMINGO.plusDays(9), null, null))
				.isInstanceOf(VentanaDeAgendaDemasiadoAmpliaException.class);
		verifyNoInteractions(recepcion);

		doThrow(new AccessDeniedException("sin permiso")).when(soporte).exigir(actor, PermissionCodes.TURNO_READ, ORG_ID, SEDE_ID);
		assertThatThrownBy(() -> ver(LUNES, MARTES, null, null)).isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(recepcion);
	}

	private AgendaOperativaView ver(LocalDate desde, LocalDate hasta, Long profesionalId, Long espacioId) {
		return service.ver(actor, SEDE_ID, desde, hasta, profesionalId, null, espacioId, false);
	}

	private void dadosLosTurnos(TurnoDeCalendarioView... turnos) {
		given(recepcion.delRango(any(), anyLong(), any(), any(), any(), any(), anyBoolean()))
				.willReturn(new TurnosDeSedeView(LUNES, MARTES, ZONA.getId(), List.of(turnos)));
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", ZONA.getId(), true);
	}

	private static TurnoDeCalendarioView turnoDe(long id, Long profesionalId, Long espacioId) {
		TurnoDelDiaView base = Fixture.de(TurnoDelDiaView.class);
		return new TurnoDeCalendarioView(new TurnoDelDiaView(id, base.inicio(), base.fin(), base.estado(), base.personaId(),
				base.personaNombre(), base.documento(), base.ofertaId(), base.ofertaNombre(), profesionalId, espacioId,
				base.llegadaEn(), base.motivoCancelacion(), base.serieId(), base.coberturaId(), base.version(),
				base.recepcion(), base.prepago(), base.operativo()), "Nombre");
	}

	private static ConsultorioMembershipSnapshot membership(long id) {
		return new ConsultorioMembershipSnapshot(id, id + 100, ORG_ID, SEDE_ID, "PROFESIONAL", "ACTIVA",
				Instant.EPOCH, null, true, true);
	}

	private static EventoDeAgendaExterno clase(long id, String estado, Long profesionalId) {
		Instant inicio = LUNES.atTime(9, 0).atZone(ZONA).toInstant();
		return new EventoDeAgendaExterno("CLASE", id, inicio, inicio.plusSeconds(3600), estado, 42L, "Pilates grupal",
				"Pilates", profesionalId, 55L, 8, 3);
	}

	private static EspacioSnapshot espacio(long id, String nombre, boolean activo, boolean enServicio) {
		return new EspacioSnapshot(id, ORG_ID, SEDE_ID, nombre, "BOX", 1, Instant.EPOCH, null, activo, enServicio);
	}
}
