package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.EventoExternoDeAgenda;
import com.akine.scheduling.spi.EventoExternoDeAgenda.EventoDeAgendaExterno;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * La agenda unificada del dia (M12 + M28).
 *
 * <h2>Que resuelve, y por que no alcanzaba con la agenda de turnos</h2>
 *
 * <p>En la pantalla del dia conviven <b>turnos individuales y clases grupales</b>, y las dos cosas
 * ocupan al mismo profesional y al mismo box. Una grilla que mostrara solo turnos le haria creer al
 * recepcionista que las 18:00 estan libres cuando hay doce personas haciendo pilates.
 *
 * <p>Las clases no se leen de {@code scheduling}: llegan por {@link EventoExternoDeAgenda}, que es
 * la costura hacia {@code activity}. Este modulo no importa el dominio del otro.
 *
 * <h2>El orden es parte del contrato</h2>
 *
 * <p>Por instante, despues por tipo y al final por id. Sin el desempate, dos eventos del mismo
 * minuto se ordenarian distinto entre una lectura y la siguiente, y la grilla "saltaria" sola
 * delante de quien la esta mirando.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgendaUnificadaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long OFERTA_ID = 42L;
	private static final long PERSONA_ID = 128L;
	private static final long CUENTA = 99L;
	private static final LocalDate FECHA = LocalDate.of(2026, 10, 5);
	private static final Instant MEDIODIA = Instant.parse("2026-10-05T12:00:00Z");

	@Mock private TurnoRepositoryPort turnos;
	@Mock private EventoExternoDeAgenda eventosExternos;
	@Mock private OfertaDirectory ofertas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;

	private AgendaUnificadaService service;

	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new AgendaUnificadaService(
				turnos, eventosExternos, ofertas, consultorios, permissionGuard);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(sede()));
		given(ofertas.find(anyLong(), anyLong(), anyLong())).willReturn(Optional.of(oferta()));
		given(turnos.findDeLaSedeEnVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(eventosExternos.enVentana(anyLong(), anyLong(), any(), any())).willReturn(List.of());
	}

	@Test
	@DisplayName("La grilla mezcla turnos y clases: las dos cosas ocupan al mismo profesional")
	void turnos_y_clases_juntos() {
		// Una grilla que mostrara solo turnos le haria creer al recepcionista que las 18:00 estan
		// libres cuando hay doce personas haciendo pilates.
		given(turnos.findDeLaSedeEnVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(turno(MEDIODIA, 301L)));
		given(eventosExternos.enVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(clase(MEDIODIA.plus(Duration.ofHours(1)), 80L)));

		var vista = service.delDia(actor, CONSULTORIO_ID, FECHA);

		assertThat(vista.eventos()).hasSize(2);
		assertThat(vista.eventos()).extracting(EventoDeAgendaView::tipo)
				.containsExactly(AgendaUnificadaService.TIPO_TURNO, "CLASE");
	}

	@Test
	@DisplayName("Ordena por instante y desempata por tipo y por id: la grilla no salta sola")
	void el_orden_es_estable() {
		// Sin el desempate, dos eventos del mismo minuto se ordenarian distinto entre una lectura y
		// la siguiente, y la pantalla cambiaria delante de quien la mira.
		given(turnos.findDeLaSedeEnVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(turno(MEDIODIA, 302L), turno(MEDIODIA, 301L)));
		given(eventosExternos.enVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(clase(MEDIODIA, 80L)));

		var vista = service.delDia(actor, CONSULTORIO_ID, FECHA);

		assertThat(vista.eventos()).extracting(EventoDeAgendaView::eventoId)
				.as("CLASE antes que TURNO por orden alfabetico del tipo, y 301 antes que 302")
				.containsExactly(80L, 301L, 302L);
	}

	@Test
	@DisplayName("La ventana se calcula en la zona de la SEDE, no en UTC")
	void la_ventana_usa_el_huso_de_la_sede() {
		// A las 21:00 en Cordoba ya es el dia siguiente en UTC: con la zona equivocada, la agenda
		// del dia pierde los turnos de la tarde y muestra los de la manana siguiente.
		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede",
						"America/Argentina/Cordoba", true)));

		service.delDia(actor, CONSULTORIO_ID, FECHA);

		ArgumentCaptor<Instant> desde = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> hasta = ArgumentCaptor.forClass(Instant.class);
		verify(turnos).findDeLaSedeEnVentana(
				anyLong(), anyLong(), desde.capture(), hasta.capture());
		assertThat(desde.getValue()).isEqualTo(Instant.parse("2026-10-05T03:00:00Z"));
		assertThat(hasta.getValue()).isEqualTo(Instant.parse("2026-10-06T03:00:00Z"));
	}

	@Test
	@DisplayName("Un dia sin nada devuelve la grilla vacia, no un error")
	void dia_vacio() {
		var vista = service.delDia(actor, CONSULTORIO_ID, FECHA);

		assertThat(vista.eventos()).isEmpty();
		assertThat(vista.timezone()).isEqualTo("UTC");
	}

	@Test
	@DisplayName("Una sede de otro tenant da 404 ANTES de evaluar el permiso")
	void sede_de_otro_tenant() {
		// El orden importa: un 403 antes del 404 le confirmaria a cualquiera que esa sede existe.
		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.delDia(actor, CONSULTORIO_ID, FECHA))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		verify(permissionGuard, org.mockito.Mockito.never()).requirePermission(any());
	}

	@Test
	@DisplayName("El turno proyecta el nombre comercial de su oferta")
	void el_turno_lleva_el_nombre_de_la_oferta() {
		// La grilla muestra "Kinesiologia", no el id 42: el recepcionista no tiene por que saber
		// que numero tiene cada oferta.
		given(turnos.findDeLaSedeEnVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(turno(MEDIODIA, 301L)));

		var vista = service.delDia(actor, CONSULTORIO_ID, FECHA);

		assertThat(vista.eventos().get(0).ofertaNombre()).isEqualTo("Kinesiologia");
	}

	@Test
	@DisplayName("Si la oferta ya no existe, el turno igual aparece: sin nombre, pero aparece")
	void oferta_borrada_no_esconde_el_turno() {
		// Esconder el turno seria peor que mostrarlo incompleto: la hora sigue ocupada y alguien
		// tiene que verlo.
		given(ofertas.find(anyLong(), anyLong(), anyLong())).willReturn(Optional.empty());
		given(turnos.findDeLaSedeEnVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(turno(MEDIODIA, 301L)));

		var vista = service.delDia(actor, CONSULTORIO_ID, FECHA);

		assertThat(vista.eventos()).hasSize(1);
		assertThat(vista.eventos().get(0).ofertaNombre()).isNull();
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede", "UTC", true);
	}

	private static OfertaSnapshot oferta() {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 45, 1,
				false, true, true, false, true, LocalDate.of(2020, 1, 1), null, true);
	}

	private static Turno turno(Instant inicio, long id) {
		Turno turno = new Turno(ORG_ID, CONSULTORIO_ID, OFERTA_ID, PERSONA_ID, 31L, 12L,
				inicio, inicio.plus(Duration.ofMinutes(45)), CUENTA, Instant.EPOCH, null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}

	private static EventoDeAgendaExterno clase(Instant inicio, long id) {
		return new EventoDeAgendaExterno("CLASE", id, inicio, inicio.plus(Duration.ofHours(1)),
				"PROGRAMADA", OFERTA_ID, "Pilates", "Pilates - avanzado", 31L, 12L, 12, 8);
	}
}
