package com.akine.scheduling.application;

import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * La revalidacion del slot bajo el lock de la sede (M12, AKINE-05.02 y 05.03).
 *
 * <h2>Por que existe esta clase</h2>
 *
 * <p>El motor de slots <b>no promete</b> que un slot devuelto se pueda reservar: entre la lectura y
 * la escritura puede entrar cualquier cosa. Esta es la revalidacion que corre ya con el lock de la
 * sede tomado, y es la unica que decide de verdad. Si la reserva confiara en el resultado del motor,
 * dos recepcionistas mirando la misma pantalla venden el mismo turno.
 *
 * <h2>La regla que 05.03 agrego y cuesta cara si se olvida</h2>
 *
 * <p><b>El turno que se esta moviendo sigue vivo en su horario viejo.</b> Sin excluirlo, reprogramar
 * una sesion media hora mas tarde la haria <b>chocar contra si misma</b>: el conteo de ocupacion la
 * cuenta, y el control de solapamiento del profesional tambien.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevalidadorDeSlotTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long OFERTA_ID = 42L;
	private static final long PROFESIONAL_ID = 31L;
	private static final long ESPACIO_ID = 12L;
	private static final long PERSONA_ID = 128L;
	private static final long CUENTA = 99L;
	private static final long TURNO_ID = 301L;

	private static final Instant INICIO = Instant.parse("2026-10-05T12:00:00Z");
	private static final Instant FIN = INICIO.plus(Duration.ofMinutes(45));

	@Mock private TurnoRepositoryPort turnos;
	@Mock private OfertaDirectory ofertas;
	@Mock private DisponibilidadDirectory disponibilidad;
	@Mock private EspacioDirectory espacios;
	@Mock private OcupacionExternaProbe ocupacionExterna;

	private RevalidadorDeSlot revalidador;

	@BeforeEach
	void setUp() {
		revalidador = new RevalidadorDeSlot(
				turnos, ofertas, disponibilidad, espacios, ocupacionExterna);

		given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
				.willReturn(List.of());
		given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(List.of());
		given(disponibilidad.efectiva(anyLong(), any(), anyLong(), any(), any()))
				.willReturn(List.of(diaConFranja(INICIO, FIN)));
		given(turnos.contarVivosEnSlot(anyLong(), anyLong(), any())).willReturn(0L);
		given(turnos.findVivosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(turnos.findVivosDeEspacioQueCruzan(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(espacios.enServicio(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(espacio(true)));
		given(ocupacionExterna.profesionalOcupado(anyLong(), anyLong(), any(), any()))
				.willReturn(false);
		given(ocupacionExterna.espacioOcupado(anyLong(), anyLong(), any(), any()))
				.willReturn(false);
	}

	@Test
	@DisplayName("Con todo libre devuelve el profesional pedido y un espacio en servicio")
	void camino_feliz() {
		RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidar(pedido(null));

		assertThat(asignacion.profesionalId()).isEqualTo(PROFESIONAL_ID);
		assertThat(asignacion.espacioId()).isEqualTo(ESPACIO_ID);
	}

	@Nested
	@DisplayName("El cupo")
	class Cupo {

		@Test
		@DisplayName("Con el slot lleno no se reserva, aunque el motor lo haya ofrecido")
		void slot_completo() {
			// El motor leyo hace un segundo y otro recepcionista confirmo en el medio.
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(SlotCompletoException.class);
		}

		@Test
		@DisplayName("Una oferta grupal admite hasta su capacidad: el segundo turno entra")
		void grupal_con_lugar() {
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);

			assertThat(revalidador.revalidar(pedidoDe(ofertaCon(3)))).isNotNull();
		}

		@Test
		@DisplayName("EL TURNO QUE SE MUEVE NO SE CUENTA A SI MISMO")
		void el_turno_excluido_no_cuenta() {
			// Sin esta exclusion, reprogramar media hora mas tarde dentro del mismo slot haria que
			// el turno chocara contra si mismo y el usuario recibiria un slot-completo imposible de
			// entender.
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);
			given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID))
					.willReturn(Optional.of(turnoVivoEnElSlot()));

			assertThat(revalidador.revalidar(pedido(TURNO_ID))).isNotNull();
		}
	}

	@Nested
	@DisplayName("El profesional")
	class Profesional {

		@Test
		@DisplayName("Si la oferta exige profesional y no se indico, el slot no sirve")
		void sin_profesional_declarado() {
			assertThatThrownBy(() -> revalidador.revalidar(new RevalidadorDeSlot.Pedido(
					ORG_ID, CONSULTORIO_ID, sede(), ofertaCon(1), INICIO, FIN, null, null)))
					.isInstanceOf(SlotNoDisponibleException.class);
		}

		@Test
		@DisplayName("Un profesional que ya no esta habilitado para la oferta: el slot no existe")
		void profesional_deshabilitado() {
			// La habilitacion pudo darse de baja entre que la pantalla dibujo la grilla y el
			// usuario confirmo.
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of(new HabilitacionSnapshot(
							1L, 999L, Instant.EPOCH, null, true)));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(SlotNoDisponibleException.class);
		}

		@Test
		@DisplayName("La lista de habilitaciones VACIA significa TODOS, no ninguno")
		void sin_habilitaciones_pasan_todos() {
			// Es la misma convencion que el resto del repositorio, y invertirla dejaria a toda
			// oferta sin habilitaciones explicitas imposible de reservar.
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID))
					.willReturn(List.of());

			assertThat(revalidador.revalidar(pedido(null)).profesionalId())
					.isEqualTo(PROFESIONAL_ID);
		}

		@Test
		@DisplayName("Fuera de la franja de disponibilidad el slot ya no existe")
		void fuera_de_franja() {
			// Entro un feriado, cambio el horario o se corto el bloque despues de dibujar la grilla.
			given(disponibilidad.efectiva(anyLong(), any(), anyLong(), any(), any()))
					.willReturn(List.of(diaConFranja(
							INICIO.plus(Duration.ofHours(5)), FIN.plus(Duration.ofHours(5)))));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(SlotNoDisponibleException.class);
		}

		@Test
		@DisplayName("Un turno de OTRA oferta que cruza deja al profesional ocupado")
		void conflicto_real() {
			given(turnos.findVivosDeProfesionalQueCruzan(ORG_ID, PROFESIONAL_ID, INICIO, FIN))
					.willReturn(List.of(turnoDeOtraOferta()));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(RecursoOcupadoException.class);
		}

		@Test
		@DisplayName("Otro turno DEL MISMO slot grupal no es conflicto: por eso existe el cupo")
		void el_mismo_slot_no_es_conflicto() {
			// Si contara como solapamiento, una oferta grupal no podria tener dos inscriptos nunca.
			given(turnos.findVivosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(turnoVivoEnElSlot()));

			assertThat(revalidador.revalidar(pedidoDe(ofertaCon(3)))).isNotNull();
		}

		@Test
		@DisplayName("Una CLASE del mismo profesional tambien lo ocupa")
		void ocupacion_externa() {
			// La agenda de turnos y la de clases comparten profesional: sin esta sonda, M28 podria
			// vender la misma hora que M12.
			given(ocupacionExterna.profesionalOcupado(ORG_ID, PROFESIONAL_ID, INICIO, FIN))
					.willReturn(true);

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(RecursoOcupadoException.class);
		}
	}

	@Nested
	@DisplayName("El espacio")
	class Espacio {

		@Test
		@DisplayName("Una oferta que no requiere espacio no elige ninguno")
		void sin_espacio() {
			RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidar(
					pedidoDe(oferta(1, true, false)));

			assertThat(asignacion.espacioId()).isNull();
		}

		@Test
		@DisplayName("Sin espacios habilitados se usa cualquiera en servicio de la sede")
		void sin_habilitaciones_de_espacio() {
			RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidar(pedido(null));

			assertThat(asignacion.espacioId()).isEqualTo(ESPACIO_ID);
		}

		@Test
		@DisplayName("Si todos los espacios estan ocupados, la reserva no entra")
		void todos_los_espacios_ocupados() {
			given(turnos.findVivosDeEspacioQueCruzan(ORG_ID, ESPACIO_ID, INICIO, FIN))
					.willReturn(List.of(turnoDeOtraOferta()));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(RecursoOcupadoException.class);
		}

		@Test
		@DisplayName("Un espacio fuera de servicio no se elige aunque este libre")
		void espacio_fuera_de_servicio() {
			given(espacios.enServicio(anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(espacio(false)));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(RecursoOcupadoException.class);
		}

		@Test
		@DisplayName("La segunda persona de una franja grupal comparte el box de su grupo")
		void grupal_comparte_el_box_del_grupo() {
			// Defecto corregido en E-1: el box tenia "un turno que se cruza" —el del propio grupo—
			// y la segunda inscripcion salia con recurso-ocupado.
			Turno delGrupo = turno(OFERTA_ID, INICIO, TURNO_ID + 5);
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);
			given(turnos.findVivosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(delGrupo));
			given(turnos.findVivosDeEspacioQueCruzan(anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(delGrupo));
			given(turnos.findVivosDeLaOfertaEnVentana(eq(ORG_ID), eq(OFERTA_ID), eq(INICIO), any()))
					.willReturn(List.of(delGrupo));

			assertThat(revalidador.revalidar(pedidoDe(ofertaCon(3))).espacioId())
					.isEqualTo(ESPACIO_ID);
		}

		@Test
		@DisplayName("Una CLASE en el box tambien lo ocupa")
		void espacio_ocupado_por_una_clase() {
			given(ocupacionExterna.espacioOcupado(ORG_ID, ESPACIO_ID, INICIO, FIN))
					.willReturn(true);

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(RecursoOcupadoException.class);
		}
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static RevalidadorDeSlot.Pedido pedido(Long turnoExcluidoId) {
		return new RevalidadorDeSlot.Pedido(ORG_ID, CONSULTORIO_ID, sede(), ofertaCon(1),
				INICIO, FIN, PROFESIONAL_ID, turnoExcluidoId);
	}

	private static RevalidadorDeSlot.Pedido pedidoDe(OfertaSnapshot oferta) {
		return new RevalidadorDeSlot.Pedido(ORG_ID, CONSULTORIO_ID, sede(), oferta,
				INICIO, FIN, PROFESIONAL_ID, null);
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede", "UTC", true);
	}

	private static OfertaSnapshot ofertaCon(int capacidad) {
		return oferta(capacidad, true, true);
	}

	private static OfertaSnapshot oferta(
			int capacidad, boolean requiereProfesional, boolean requiereEspacio) {

		return new OfertaSnapshot(OFERTA_ID, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 45,
				capacidad, capacidad > 1, requiereProfesional, requiereEspacio, false, true,
				LocalDate.of(2020, 1, 1), null, true);
	}

	private static EspacioSnapshot espacio(boolean enServicio) {
		return new EspacioSnapshot(ESPACIO_ID, ORG_ID, CONSULTORIO_ID, "Box 1", "BOX", 1,
				Instant.EPOCH, null, true, enServicio);
	}

	private static DisponibilidadDirectory.DiaDisponible diaConFranja(Instant desde, Instant hasta) {
		return new DisponibilidadDirectory.DiaDisponible(
				LocalDate.ofInstant(desde, java.time.ZoneOffset.UTC),
				null,
				List.of(new DisponibilidadDirectory.Franja(desde, hasta)));
	}

	/** El turno que se esta moviendo: vivo, de esta oferta y en este mismo slot. */
	private static Turno turnoVivoEnElSlot() {
		return turno(OFERTA_ID, INICIO, TURNO_ID);
	}

	/** Un turno de otra oferta que pisa el horario: ese si deja al profesional ocupado. */
	private static Turno turnoDeOtraOferta() {
		return turno(OFERTA_ID + 1, INICIO, TURNO_ID + 1);
	}

	private static Turno turno(long ofertaId, Instant inicio, long id) {
		Turno turno = new Turno(ORG_ID, CONSULTORIO_ID, ofertaId, PERSONA_ID, PROFESIONAL_ID,
				ESPACIO_ID, inicio, inicio.plus(Duration.ofMinutes(45)), CUENTA,
				Instant.EPOCH, null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}
}
