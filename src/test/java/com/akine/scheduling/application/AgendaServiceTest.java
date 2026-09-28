package com.akine.scheduling.application;

import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.DisponibilidadDirectory.DiaDisponible;
import com.akine.resource.spi.DisponibilidadDirectory.Franja;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.VentanaDeAgendaDemasiadoAmpliaException;
import com.akine.scheduling.spi.ReservaProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * Las reglas de composicion del motor de slots.
 *
 * <p>Lo que se prueba es <b>que motivo gana</b> cuando un dia no tiene turnos, y que las tres
 * vigencias se evaluen dia por dia. No hay tests de codigos HTTP, de validacion de forma ni del
 * comportamiento de Spring: eso lo garantiza el framework y probarlo solo agrega tests que hay
 * que mantener.
 *
 * <p>El {@code SlotGenerator} <b>no se sustituye</b>: se prueba directo en su propio test, y aca
 * la orquestacion corre contra el real. Un doble del generador dejaria sin probar justamente la
 * parte que importa.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgendaService")
class AgendaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long OFERTA_ID = 42L;
	private static final long PROFESIONAL_ID = 31L;
	private static final long ESPACIO_ID = 55L;
	private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");

	private static final LocalDate LUNES = LocalDate.of(2026, 9, 14);
	private static final LocalDate MARTES = LUNES.plusDays(1);

	@Mock private OfertaDirectory ofertas;
	@Mock private DisponibilidadDirectory disponibilidad;
	@Mock private EspacioDirectory espacios;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private PermissionGuard permissionGuard;
	@Mock private ReservaProbe reservas;

	private AgendaService service;

	private final OperatingActor actor = new OperatingActor(99L, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new AgendaService(
				ofertas, disponibilidad, espacios, consultorios, memberships, permissionGuard, reservas);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede centro", ZONA.getId(), true)));
		// Sin profesionales de la sede: los tests declaran sus habilitaciones explicitamente, y la
		// regla de "lista vacia = todos" tiene su propio test.
		given(memberships.findPorRolEnSede(anyLong(), anyLong(), any())).willReturn(List.of());
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static OfertaSnapshot oferta(
			int duracionMinutos, boolean requiereProfesional, boolean requiereEspacio,
			LocalDate vigenciaDesde, LocalDate vigenciaHasta, boolean active) {

		return new OfertaSnapshot(OFERTA_ID, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia",
				duracionMinutos, 1, false, requiereProfesional, requiereEspacio, false, false,
				vigenciaDesde, vigenciaHasta, active);
	}

	private static OfertaSnapshot ofertaDe(int duracionMinutos) {
		return oferta(duracionMinutos, true, false, LUNES.minusYears(1), null, true);
	}

	private static HabilitacionSnapshot habilitacion(long recursoId, Instant hasta) {
		return new HabilitacionSnapshot(
				1L, recursoId, LUNES.minusYears(1).atStartOfDay(ZONA).toInstant(), hasta, true);
	}

	private static DiaDisponible atiende(LocalDate fecha, String desde, String hasta) {
		return new DiaDisponible(fecha, null, List.of(new Franja(
				fecha.atTime(java.time.LocalTime.parse(desde)).atZone(ZONA).toInstant(),
				fecha.atTime(java.time.LocalTime.parse(hasta)).atZone(ZONA).toInstant())));
	}

	private static DiaDisponible noAtiende(LocalDate fecha, String razon) {
		return new DiaDisponible(fecha, razon, List.of());
	}

	private void conDisponibilidad(List<DiaDisponible> dias) {
		given(disponibilidad.efectiva(anyLong(), any(), anyLong(), any(), any()))
				.willReturn(dias);
	}


	private AgendaView buscarUnDia() {
		return service.buscar(actor, CONSULTORIO_ID, OFERTA_ID, LUNES, MARTES, null);
	}

	// =================================================================================

	@Nested
	@DisplayName("Que motivo explica un dia sin turnos")
	class MotivoDelDiaVacio {

		@Test
		@DisplayName("La vigencia de la oferta se evalua DIA POR DIA, no contra la ventana")
		void oferta_vencida_a_mitad_de_ventana() {
			// El ruling R13 de 02.04 aplicado a la oferta: una que vence el lunes no puede seguir
			// ofreciendo turnos el martes porque la consulta abarco los dos dias. Sin el control
			// por dia, el motor los ofrece y la reserva los crea contra una oferta muerta.
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(
					oferta(30, true, false, LUNES.minusYears(1), LUNES, true)));
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(PROFESIONAL_ID, null)));
			conDisponibilidad(List.of(atiende(LUNES, "09:00", "10:00"),
					atiende(MARTES, "09:00", "10:00")));

			var dias = service.buscar(
					actor, CONSULTORIO_ID, OFERTA_ID, LUNES, MARTES.plusDays(1), null).dias();

			assertThat(dias.get(0).slots()).hasSize(2);
			assertThat(dias.get(1).motivoSinSlots()).isEqualTo("OFERTA_NO_VIGENTE");
			assertThat(dias.get(1).slots()).isEmpty();
		}

		@Test
		@DisplayName("Sin ningun profesional habilitado vigente ese dia: SIN_PROFESIONAL")
		void habilitacion_vencida_ese_dia() {
			// Lo mismo para la habilitacion de 02.07: dada de baja el lunes, el martes no ofrece.
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(ofertaDe(30)));
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(
							PROFESIONAL_ID, MARTES.atStartOfDay(ZONA).toInstant())));
			conDisponibilidad(List.of(atiende(LUNES, "09:00", "10:00"),
					atiende(MARTES, "09:00", "10:00")));

			var dias = service.buscar(
					actor, CONSULTORIO_ID, OFERTA_ID, LUNES, MARTES.plusDays(1), null).dias();

			assertThat(dias.get(0).slots()).hasSize(2);
			assertThat(dias.get(1).motivoSinSlots()).isEqualTo("SIN_PROFESIONAL");
		}

		@Test
		@DisplayName("Hubo horario y recursos pero la franja no alcanza para un slot entero")
		void franja_mas_corta_que_la_oferta() {
			// Bloque de 30 minutos con oferta de 45. El motivo tiene que ser accionable: lo que le
			// falta al administrador es ampliar el bloque, y "no hay turnos" no se lo dice.
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(ofertaDe(45)));
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(PROFESIONAL_ID, null)));
			conDisponibilidad(List.of(atiende(LUNES, "09:00", "09:30")));

			assertThat(buscarUnDia().dias().get(0).motivoSinSlots())
					.isEqualTo("FRANJA_MAS_CORTA_QUE_LA_OFERTA");
		}

		@Test
		@DisplayName("El motivo de M05 se propaga: un feriado sale como FERIADO")
		void propaga_el_motivo_de_la_disponibilidad() {
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(ofertaDe(30)));
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(PROFESIONAL_ID, null)));
			conDisponibilidad(List.of(noAtiende(LUNES, "FERIADO")));

			assertThat(buscarUnDia().dias().get(0).motivoSinSlots()).isEqualTo("FERIADO");
		}

		@Test
		@DisplayName("El cuarto estado de M05 —vacio sin regla— se traduce a SIN_HORARIO")
		void razon_nula_de_m05_es_sin_horario() {
			// M05 usa razonVacio == null con franjas vacias para "ninguna regla lo abrio". La
			// agenda no puede propagar ese null porque de este lado null ya significa "el dia SI
			// tiene slots": sin la traduccion, el dia saldria como si tuviera turnos.
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(ofertaDe(30)));
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(PROFESIONAL_ID, null)));
			conDisponibilidad(List.of(noAtiende(LUNES, null)));

			assertThat(buscarUnDia().dias().get(0).motivoSinSlots()).isEqualTo("SIN_HORARIO");
		}

		@Test
		@DisplayName("Ningun dia de la ventana se omite, tengan turnos o no")
		void nunca_omite_un_dia() {
			// La pantalla dibuja una grilla de fechas: un dia ausente la deja en blanco sin poder
			// distinguir "no hay turnos" de "no pregunte por ese dia".
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(ofertaDe(30)));
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(PROFESIONAL_ID, null)));
			conDisponibilidad(List.of(atiende(LUNES, "09:00", "10:00"), noAtiende(MARTES, "CIERRE")));

			var dias = service.buscar(
					actor, CONSULTORIO_ID, OFERTA_ID, LUNES, MARTES.plusDays(1), null).dias();

			assertThat(dias).hasSize(2);
			assertThat(dias).allSatisfy(dia ->
					assertThat(dia.slots().isEmpty() == (dia.motivoSinSlots() != null)).isTrue());
		}
	}

	@Test
	@DisplayName("Sin habilitaciones cargadas, TODOS los profesionales de la sede pueden prestarla")
	void la_lista_vacia_significa_todos() {
		// Es la regla de V28 y la decision con mas consecuencias de 02.07: una oferta recien creada
		// no tiene filas de habilitacion, y si eso significara "nadie puede prestarla", toda oferta
		// naceria sin poder ofrecer un solo turno.
		//
		// La primera version del motor la invertia en silencio y devolvia SIN_PROFESIONAL. Lo
		// destapo el QA manual contra el stack real, no un test: el doble devolvia la lista que el
		// test le daba y nadie cuestionaba que significaba la lista vacia.
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(ofertaDe(30)));
		given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
				.willReturn(List.of());
		given(memberships.findPorRolEnSede(ORG_ID, CONSULTORIO_ID, "PROFESIONAL")).willReturn(List.of(
				new ConsultorioMembershipSnapshot(PROFESIONAL_ID, 5L, ORG_ID, CONSULTORIO_ID,
						"PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true)));
		conDisponibilidad(List.of(atiende(LUNES, "09:00", "10:00")));

		assertThat(buscarUnDia().dias().get(0).slots())
				.as("la oferta sin restringir la puede prestar cualquier profesional de la sede")
				.hasSize(2);
	}

	@Test
	@DisplayName("Un slot ya reservado viaja con cupo cero, no se esconde de la grilla")
	void el_slot_vendido_se_descuenta() {
		// Durante 05.01 y 05.02 esto no pasaba: la sonda existia, devolvia vacio y nadie la
		// reemplazo, asi que la agenda ofrecia huecos ya vendidos y el usuario se comia un 409 al
		// confirmar. No corrompia nada, pero convertia un caso normal en un error.
		//
		// Y el slot NO se filtra: la pantalla tiene que poder decir "completo" en vez de dejar un
		// hueco en la grilla, que el usuario leeria como "no atiende a esa hora".
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(ofertaDe(60)));
		given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
				.willReturn(List.of(habilitacion(PROFESIONAL_ID, null)));
		conDisponibilidad(List.of(atiende(LUNES, "09:00", "11:00")));
		given(reservas.reservasPorInicio(anyLong(), anyLong(), anyLong(), any(), any(), any()))
				.willReturn(Map.of(
						LUNES.atTime(9, 0).atZone(ZONA).toInstant(), 1));

		var slots = buscarUnDia().dias().get(0).slots();

		assertThat(slots).hasSize(2);
		assertThat(slots.get(0).cupoLibre()).as("el de las 09:00 esta tomado").isZero();
		assertThat(slots.get(1).cupoLibre()).as("el de las 10:00 sigue libre").isEqualTo(1);
	}

	@Nested
	@DisplayName("El espacio")
	class Espacios {

		@Test
		@DisplayName("Sin espacio en servicio no hay turnos, aunque el profesional atienda")
		void sin_espacio_en_servicio() {
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(
					oferta(30, true, true, LUNES.minusYears(1), null, true)));
			given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(ESPACIO_ID, null)));
			given(espacios.find(anyLong(),
					anyLong(), any()))
					.willReturn(Optional.of(new EspacioSnapshot(
							ESPACIO_ID, ORG_ID, CONSULTORIO_ID, "Box 1", "BOX", 1,
							Instant.EPOCH, null, true, false)));

			assertThat(buscarUnDia().dias().get(0).motivoSinSlots()).isEqualTo("SIN_ESPACIO");
		}

		@Test
		@DisplayName("El motor no clava un espacio: elegirlo es de la reserva")
		void no_asigna_espacio() {
			// Elegirlo aca, fuera de la transaccion que crea el turno, seria una promesa que dos
			// busquedas concurrentes rompen: las dos verian el mismo box libre.
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(
					oferta(30, true, true, LUNES.minusYears(1), null, true)));
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(PROFESIONAL_ID, null)));
			given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(habilitacion(ESPACIO_ID, null)));
			given(espacios.find(anyLong(),
					anyLong(), any()))
					.willReturn(Optional.of(new EspacioSnapshot(
							ESPACIO_ID, ORG_ID, CONSULTORIO_ID, "Box 1", "BOX", 1,
							Instant.EPOCH, null, true, true)));
			conDisponibilidad(List.of(atiende(LUNES, "09:00", "10:00")));

			assertThat(buscarUnDia().dias().get(0).slots())
					.isNotEmpty()
					.allSatisfy(slot -> assertThat(slot.espacioId()).isNull());
		}
	}

	@Nested
	@DisplayName("Rechazos")
	class Rechazos {

		@Test
		@DisplayName("Una oferta de baja es 409, no 404: existe y el usuario la esta viendo")
		void oferta_de_baja_es_conflicto() {
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(
					oferta(30, true, false, LUNES.minusYears(1), null, false)));

			assertThatThrownBy(this::buscar).isInstanceOf(OfertaNoAgendableException.class);
		}

		@Test
		@DisplayName("Una oferta cuya vigencia no toca la ventana tambien es 409")
		void vigencia_fuera_de_la_ventana() {
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.of(
					oferta(30, true, false, LUNES.plusYears(1), null, true)));

			assertThatThrownBy(this::buscar).isInstanceOf(OfertaNoAgendableException.class);
		}

		@Test
		@DisplayName("Una oferta de otra sede es 404, nunca 403")
		void oferta_de_otra_sede() {
			// Un 403 confirmaria que esa oferta existe, y bastaria probar ids consecutivos para
			// enumerar el catalogo de la competencia. ADR-0018.
			given(ofertas.find(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(Optional.empty());

			assertThatThrownBy(this::buscar).isInstanceOf(OfertaNotAccessibleException.class);
		}

		@Test
		@DisplayName("Una sede de otro tenant es 404 ANTES de evaluar el permiso")
		void sede_de_otro_tenant() {
			given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

			assertThatThrownBy(this::buscar).isInstanceOf(ConsultorioNoAccesibleException.class);
		}

		@Test
		@DisplayName("La ventana se valida DESPUES del permiso, y su tope es el de la agenda")
		void ventana_demasiado_amplia() {
			assertThatThrownBy(() -> service.buscar(actor, CONSULTORIO_ID, OFERTA_ID,
					LUNES, LUNES.plusDays(VentanaDeAgenda.VENTANA_MAXIMA_DIAS + 1), null))
					.isInstanceOf(VentanaDeAgendaDemasiadoAmpliaException.class);
		}

		private void buscar() {
			service.buscar(actor, CONSULTORIO_ID, OFERTA_ID, LUNES, MARTES, null);
		}
	}
}
