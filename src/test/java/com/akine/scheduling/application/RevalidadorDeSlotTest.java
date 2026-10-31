package com.akine.scheduling.application;

import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.EspacioNoElegibleException;
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
		given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(0L);
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
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOf(SlotCompletoException.class);
		}

		@Test
		@DisplayName("Una oferta grupal admite hasta su capacidad: el segundo turno entra")
		void grupal_con_lugar() {
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);

			assertThat(revalidador.revalidar(pedidoDe(ofertaCon(3)))).isNotNull();
		}

		@Test
		@DisplayName("EL TURNO QUE SE MUEVE NO SE CUENTA A SI MISMO")
		void el_turno_excluido_no_cuenta() {
			// Sin esta exclusion, reprogramar media hora mas tarde dentro del mismo slot haria que
			// el turno chocara contra si mismo y el usuario recibiria un slot-completo imposible de
			// entender.
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);
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
		@DisplayName("A-8b: una oferta sin profesional fuera del horario de la sede da el mismo 409 de slot no disponible")
		void sin_profesional_fuera_del_horario_de_la_sede() {
			given(disponibilidad.fueraDelHorarioDeSede(anyLong(), any(), any(), any()))
					.willReturn(true);

			assertThatThrownBy(() -> revalidador.revalidar(pedidoDe(oferta(1, false, false))))
					.isInstanceOf(SlotNoDisponibleException.class);
		}

		@Test
		@DisplayName("A-8b: una oferta sin profesional dentro del horario (o sin horario de sede) se reserva igual que antes")
		void sin_profesional_dentro_del_horario_de_la_sede() {
			given(disponibilidad.fueraDelHorarioDeSede(anyLong(), any(), any(), any()))
					.willReturn(false);

			assertThat(revalidador.revalidar(pedidoDe(oferta(1, false, false))).profesionalId())
					.isNull();
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
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);
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
		@DisplayName("Quien se suma al grupo y termina despues no hereda un box que a esa hora es de otro")
		void el_box_del_grupo_ocupado_por_otro_mas_tarde() {
			// La duracion cambio con el grupo armado: el nuevo termina despues que el resto y se cruza con el
			// turno siguiente de ese box. Un grupo ocupa un solo box, asi que no se lo reparte.
			Turno delGrupo = turno(OFERTA_ID, INICIO, TURNO_ID + 5);
			Turno siguiente = turno(OFERTA_ID + 1, INICIO.plusSeconds(1_800), TURNO_ID + 6);
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);
			given(turnos.findVivosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(delGrupo));
			given(turnos.findVivosDeEspacioQueCruzan(anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(delGrupo, siguiente));
			given(turnos.findVivosDeLaOfertaEnVentana(eq(ORG_ID), eq(OFERTA_ID), eq(INICIO), any()))
					.willReturn(List.of(delGrupo));

			assertThatThrownBy(() -> revalidador.revalidar(pedidoDe(ofertaCon(3))))
					.isInstanceOfSatisfying(RecursoOcupadoException.class,
							ocupado -> assertThat(ocupado.getRecurso()).isEqualTo("espacio"));
		}

		@Test
		@DisplayName("Dos profesionales a la misma hora son dos grupos: el segundo no cae en el box del primero")
		void otro_profesional_no_comparte_el_box() {
			Turno delOtro = new Turno(ORG_ID, CONSULTORIO_ID, OFERTA_ID, PERSONA_ID, 999L, ESPACIO_ID,
					INICIO, FIN, CUENTA, Instant.EPOCH, null, null);
			ReflectionTestUtils.setField(delOtro, "id", TURNO_ID + 5);
			given(turnos.findVivosDeLaOfertaEnVentana(eq(ORG_ID), eq(OFERTA_ID), eq(INICIO), any())).willReturn(List.of(delOtro));
			given(turnos.findVivosDeEspacioQueCruzan(ORG_ID, ESPACIO_ID, INICIO, FIN)).willReturn(List.of(delOtro));
			given(espacios.enServicio(anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(espacio(true), espacioDe(ESPACIO_ID + 1)));

			assertThat(revalidador.revalidar(pedido(null)).espacioId()).isEqualTo(ESPACIO_ID + 1);

			given(espacios.enServicio(anyLong(), anyLong(), any(), any())).willReturn(List.of(espacio(true)));
			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.as("con un solo box el segundo se queda sin lugar, en vez de quedar encima del primero")
					.isInstanceOfSatisfying(RecursoOcupadoException.class,
							ocupado -> assertThat(ocupado.getRecurso()).isEqualTo("espacio"));
		}

		@Test
		@DisplayName("El box de un grupo dado de baja mientras se reservaba no se hereda bajo el lock; consultar no lo relee")
		void box_del_grupo_dado_de_baja() {
			Turno delGrupo = turno(OFERTA_ID, INICIO, TURNO_ID + 5);
			given(turnos.findVivosDeLaOfertaEnVentana(eq(ORG_ID), eq(OFERTA_ID), eq(INICIO), any())).willReturn(List.of(delGrupo));
			given(espacios.inactivoBajoLockCompartido(ORG_ID, ESPACIO_ID)).willReturn(true);
			given(espacios.enServicio(anyLong(), anyLong(), any(), any())).willReturn(List.of(espacioDe(ESPACIO_ID + 1)));

			assertThat(revalidador.revalidar(pedidoDe(ofertaCon(3))).espacioId()).isEqualTo(ESPACIO_ID + 1);
			assertThat(revalidador.evaluarSinLock(pedidoDe(ofertaCon(3))).espacioId()).isEqualTo(ESPACIO_ID);
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

	@Nested
	@DisplayName("El turno que se mueve (05.03)")
	class TurnoExcluido {

		@Test
		@DisplayName("Un turno que viene de OTRO horario no descuenta cupo: restarlo dejaria entrar uno de mas")
		void el_que_viene_de_otro_horario_no_descuenta() {
			Turno enOtroHorario = turno(OFERTA_ID, INICIO.plus(Duration.ofDays(1)), TURNO_ID);
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);
			given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.of(enOtroHorario));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(TURNO_ID)))
					.isInstanceOf(SlotCompletoException.class);
		}

		@Test
		@DisplayName("Tampoco descuenta si ya no existe, si es de otra oferta o si es de otro profesional")
		void el_que_no_estaba_en_este_slot_no_descuenta() {
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);

			given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.empty());
			assertThatThrownBy(() -> revalidador.revalidar(pedido(TURNO_ID))).isInstanceOf(SlotCompletoException.class);

			given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.of(turnoDeOtraOferta()));
			assertThatThrownBy(() -> revalidador.revalidar(pedido(TURNO_ID))).isInstanceOf(SlotCompletoException.class);

			Turno deOtroProfesional = new Turno(ORG_ID, CONSULTORIO_ID, OFERTA_ID, PERSONA_ID, 999L, null,
					INICIO, FIN, CUENTA, Instant.EPOCH, null, null);
			given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.of(deOtroProfesional));
			assertThatThrownBy(() -> revalidador.revalidar(pedido(TURNO_ID))).isInstanceOf(SlotCompletoException.class);
		}

		@Test
		@DisplayName("No choca contra si mismo ni por el profesional ni por el box")
		void no_choca_contra_si_mismo() {
			Turno elMismo = turno(OFERTA_ID + 1, INICIO.minus(Duration.ofMinutes(30)), TURNO_ID);
			given(turnos.findVivosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any())).willReturn(List.of(elMismo));
			given(turnos.findVivosDeEspacioQueCruzan(anyLong(), anyLong(), any(), any())).willReturn(List.of(elMismo));

			assertThat(revalidador.revalidar(pedido(TURNO_ID)).espacioId()).isEqualTo(ESPACIO_ID);
		}
	}

	@Nested
	@DisplayName("El choque con otro turno")
	class Choque {

		@Test
		@DisplayName("Un profesional ocupado por un turno dice con cual y cuando: la pantalla ofrece alternativas con eso")
		void con_los_datos_del_choque() {
			Turno otro = turnoDeOtraOferta();
			given(turnos.findVivosDeProfesionalQueCruzan(ORG_ID, PROFESIONAL_ID, INICIO, FIN)).willReturn(List.of(otro));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOfSatisfying(RecursoOcupadoException.class, ocupado -> {
						assertThat(ocupado.getRecurso()).isEqualTo("profesional");
						assertThat(ocupado.getTurnoEnConflictoId()).isEqualTo(otro.getId());
						assertThat(ocupado.getConflictoInicio()).isEqualTo(otro.getInicio());
						assertThat(ocupado.getConflictoFin()).isEqualTo(otro.getFin());
					});
		}

		@Test
		@DisplayName("Un profesional habilitado por una fila vencida es lo mismo que uno no habilitado")
		void habilitacion_vencida() {
			given(ofertas.profesionalesHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(List.of(
					new HabilitacionSnapshot(1L, PROFESIONAL_ID, Instant.EPOCH, INICIO.minusSeconds(1), true)));

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOfSatisfying(SlotNoDisponibleException.class,
							sinHueco -> assertThat(sinHueco.getMotivo()).contains("habilitado"));
		}

		@Test
		@DisplayName("Una oferta sin profesional cuenta el cupo por oferta, y no pregunta por nadie")
		void oferta_sin_profesional_cuenta_por_oferta() {
			given(turnos.contarVivosEnSlot(ORG_ID, OFERTA_ID, INICIO)).willReturn(1L);

			assertThatThrownBy(() -> revalidador.revalidar(pedidoDe(oferta(1, false, false))))
					.isInstanceOf(SlotCompletoException.class);
			org.mockito.Mockito.verify(turnos, org.mockito.Mockito.never())
					.findVivosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any());
		}
	}

	@Nested
	@DisplayName("El box elegido a mano (TURNO - Tecnico §17)")
	class BoxElegido {

		@Test
		@DisplayName("Un box libre y habilitado queda asignado")
		void box_libre() {
			conEspacio(espacio(true));

			assertThat(revalidador.revalidar(conBoxElegido()).espacioId()).isEqualTo(ESPACIO_ID);
		}

		@Test
		@DisplayName("Elegirlo no habilita un box que la oferta no admite: rechazo de espacio no elegible")
		void box_que_la_oferta_no_admite() {
			conEspacio(espacio(true));
			given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(List.of(
					new HabilitacionSnapshot(1L, ESPACIO_ID + 1, Instant.EPOCH, null, true)));

			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido()))
					.isInstanceOfSatisfying(EspacioNoElegibleException.class,
							noElegible -> assertThat(noElegible.getEspacioId()).isEqualTo(ESPACIO_ID));
		}

		@Test
		@DisplayName("Tampoco uno habilitado de manera vencida")
		void habilitacion_de_box_vencida() {
			conEspacio(espacio(true));
			given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(List.of(
					new HabilitacionSnapshot(1L, ESPACIO_ID, Instant.EPOCH, INICIO.minusSeconds(1), true)));

			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido())).isInstanceOf(EspacioNoElegibleException.class);
		}

		@Test
		@DisplayName("Ni uno que no existe, esta de baja, fuera de servicio o es de otra sede")
		void box_no_utilizable() {
			given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.empty());
			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido())).isInstanceOf(EspacioNoElegibleException.class);

			conEspacio(espacio(false));
			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido())).isInstanceOf(EspacioNoElegibleException.class);

			conEspacio(new EspacioSnapshot(ESPACIO_ID, ORG_ID, CONSULTORIO_ID, "Box 1", "BOX", 1, Instant.EPOCH, null, false, true));
			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido())).isInstanceOf(EspacioNoElegibleException.class);

			conEspacio(new EspacioSnapshot(ESPACIO_ID, ORG_ID, 99L, "Box 1", "BOX", 1, Instant.EPOCH, null, true, true));
			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido())).isInstanceOf(EspacioNoElegibleException.class);
		}

		@Test
		@DisplayName("Ocupado por un turno devuelve con quien choca")
		void box_ocupado_por_un_turno() {
			conEspacio(espacio(true));
			Turno ocupante = turnoDeOtraOferta();
			given(turnos.findVivosDeEspacioQueCruzan(ORG_ID, ESPACIO_ID, INICIO, FIN)).willReturn(List.of(ocupante));

			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido()))
					.isInstanceOfSatisfying(RecursoOcupadoException.class, ocupado -> {
						assertThat(ocupado.getRecurso()).isEqualTo("espacio");
						assertThat(ocupado.getTurnoEnConflictoId()).isEqualTo(ocupante.getId());
					});
		}

		@Test
		@DisplayName("Ocupado por una clase es igual de ocupado, aunque no haya turno que nombrar")
		void box_ocupado_por_una_clase() {
			conEspacio(espacio(true));
			given(ocupacionExterna.espacioOcupado(ORG_ID, ESPACIO_ID, INICIO, FIN)).willReturn(true);

			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido()))
					.isInstanceOfSatisfying(RecursoOcupadoException.class,
							ocupado -> assertThat(ocupado.getTurnoEnConflictoId()).isNull());
		}

		@Test
		@DisplayName("Dado de baja mientras se reservaba: lo atrapa la relectura con lock, y consultar no la hace")
		void baja_concurrente_del_box() {
			conEspacio(espacio(true));
			given(espacios.inactivoBajoLockCompartido(ORG_ID, ESPACIO_ID)).willReturn(true);

			assertThatThrownBy(() -> revalidador.revalidar(conBoxElegido())).isInstanceOf(EspacioNoElegibleException.class);

			assertThat(revalidador.evaluarSinLock(conBoxElegido()).espacioId())
					.as("sin lock no toma el compartido: es lo que pregunta una pantalla")
					.isEqualTo(ESPACIO_ID);
		}
	}

	@Nested
	@DisplayName("Los boxes habilitados de la oferta")
	class BoxesHabilitados {

		@Test
		@DisplayName("Toma el primero utilizable y libre, salteando el vencido, el de otra sede y el ocupado")
		void primero_utilizable_y_libre() {
			long vencido = 21L;
			long deOtraSede = 22L;
			long ocupado = 23L;
			long ocupadoPorClase = 24L;
			long libre = 25L;
			given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(List.of(
					new HabilitacionSnapshot(1L, vencido, Instant.EPOCH, INICIO.minusSeconds(1), true),
					new HabilitacionSnapshot(2L, deOtraSede, Instant.EPOCH, null, true),
					new HabilitacionSnapshot(3L, ocupado, Instant.EPOCH, null, true),
					new HabilitacionSnapshot(4L, ocupadoPorClase, Instant.EPOCH, null, true),
					new HabilitacionSnapshot(5L, libre, Instant.EPOCH, null, true)));
			given(espacios.find(ORG_ID, deOtraSede, INICIO)).willReturn(Optional.of(
					new EspacioSnapshot(deOtraSede, ORG_ID, 99L, "Otra", "BOX", 1, Instant.EPOCH, null, true, true)));
			given(espacios.find(ORG_ID, ocupado, INICIO)).willReturn(Optional.of(espacioDe(ocupado)));
			given(espacios.find(ORG_ID, ocupadoPorClase, INICIO)).willReturn(Optional.of(espacioDe(ocupadoPorClase)));
			given(espacios.find(ORG_ID, libre, INICIO)).willReturn(Optional.of(espacioDe(libre)));
			given(turnos.findVivosDeEspacioQueCruzan(ORG_ID, ocupado, INICIO, FIN)).willReturn(List.of(turnoDeOtraOferta()));
			given(ocupacionExterna.espacioOcupado(ORG_ID, ocupadoPorClase, INICIO, FIN)).willReturn(true);

			assertThat(revalidador.revalidar(pedido(null)).espacioId()).isEqualTo(libre);
		}

		@Test
		@DisplayName("Si ninguno sirve es recurso-ocupado: el horario existe, lo que falta es un box")
		void ninguno_sirve() {
			given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(List.of(
					new HabilitacionSnapshot(1L, ESPACIO_ID, Instant.EPOCH, null, true)));
			given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.empty());

			assertThatThrownBy(() -> revalidador.revalidar(pedido(null)))
					.isInstanceOfSatisfying(RecursoOcupadoException.class,
							ocupado -> assertThat(ocupado.getRecurso()).isEqualTo("espacio"));
		}

		@Test
		@DisplayName("Un box dado de baja mientras se reservaba no se elige, bajo el lock; pasa al siguiente")
		void baja_concurrente_pasa_al_siguiente() {
			EspacioSnapshot otro = espacioDe(ESPACIO_ID + 1);
			given(espacios.enServicio(anyLong(), anyLong(), any(), any())).willReturn(List.of(espacio(true), otro));
			given(espacios.inactivoBajoLockCompartido(ORG_ID, ESPACIO_ID)).willReturn(true);

			assertThat(revalidador.revalidar(pedido(null)).espacioId()).isEqualTo(ESPACIO_ID + 1);
			assertThat(revalidador.evaluarSinLock(pedido(null)).espacioId())
					.as("sin lock no se relee")
					.isEqualTo(ESPACIO_ID);
		}
	}

	@Nested
	@DisplayName("Sobreturno (§5): solo salva la ocupacion")
	class Sobreturno {

		@Test
		@DisplayName("Entra con el horario lleno y el profesional ocupado, y sin box libre queda sin box")
		void salva_la_ocupacion() {
			given(turnos.contarVivosEnSlotDelProfesional(anyLong(), anyLong(), anyLong(), any())).willReturn(1L);
			given(turnos.findVivosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any())).willReturn(List.of(turnoDeOtraOferta()));
			given(ocupacionExterna.profesionalOcupado(anyLong(), anyLong(), any(), any())).willReturn(true);
			given(turnos.findVivosDeEspacioQueCruzan(anyLong(), anyLong(), any(), any())).willReturn(List.of(turnoDeOtraOferta()));

			RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidar(sobreturno());

			assertThat(asignacion.profesionalId()).isEqualTo(PROFESIONAL_ID);
			assertThat(asignacion.espacioId()).isNull();
		}

		@Test
		@DisplayName("No salva un horario fuera de la disponibilidad")
		void no_salva_un_horario_que_no_existe() {
			given(disponibilidad.efectiva(anyLong(), any(), anyLong(), any(), any())).willReturn(List.of());

			assertThatThrownBy(() -> revalidador.revalidar(sobreturno())).isInstanceOf(SlotNoDisponibleException.class);
		}

		@Test
		@DisplayName("No salva un box que la oferta no admite")
		void no_salva_un_box_no_elegible() {
			given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.empty());

			assertThatThrownBy(() -> revalidador.revalidar(new RevalidadorDeSlot.Pedido(ORG_ID, CONSULTORIO_ID, sede(), ofertaCon(1),
					INICIO, FIN, PROFESIONAL_ID, null, ESPACIO_ID, true)))
					.isInstanceOf(EspacioNoElegibleException.class);
		}

		private RevalidadorDeSlot.Pedido sobreturno() {
			return new RevalidadorDeSlot.Pedido(ORG_ID, CONSULTORIO_ID, sede(), ofertaCon(1), INICIO, FIN, PROFESIONAL_ID, null, null, true);
		}
	}

	@Nested
	@DisplayName("Los boxes para la opcion Cambiar")
	class EspaciosLibres {

		@Test
		@DisplayName("Una oferta sin box no ofrece ninguno, y no pregunta")
		void oferta_sin_box() {
			assertThat(revalidador.espaciosLibres(pedidoDe(oferta(1, true, false)))).isEmpty();

			org.mockito.Mockito.verifyNoInteractions(espacios);
		}

		@Test
		@DisplayName("Sin habilitaciones son los de la sede en servicio, sin el ocupado ni el fuera de servicio")
		void los_de_la_sede() {
			long ocupado = 21L;
			long ocupadoPorClase = 22L;
			given(espacios.enServicio(anyLong(), anyLong(), any(), any())).willReturn(List.of(
					espacio(true), espacioDe(ocupado), espacioDe(ocupadoPorClase),
					new EspacioSnapshot(23L, ORG_ID, CONSULTORIO_ID, "En obra", "BOX", 1, Instant.EPOCH, null, true, false),
					new EspacioSnapshot(24L, ORG_ID, 99L, "Otra sede", "BOX", 1, Instant.EPOCH, null, true, true)));
			given(turnos.findVivosDeEspacioQueCruzan(ORG_ID, ocupado, INICIO, FIN)).willReturn(List.of(turnoDeOtraOferta()));
			given(ocupacionExterna.espacioOcupado(ORG_ID, ocupadoPorClase, INICIO, FIN)).willReturn(true);

			assertThat(revalidador.espaciosLibres(pedido(null))).extracting(EspacioSnapshot::id).containsExactly(ESPACIO_ID);
		}

		@Test
		@DisplayName("Con habilitaciones son las vigentes que resuelven, y el turno que se mueve no ocupa su propio box")
		void los_habilitados() {
			Turno elMismo = turno(OFERTA_ID, INICIO, TURNO_ID);
			given(ofertas.espaciosHabilitados(ORG_ID, CONSULTORIO_ID, OFERTA_ID)).willReturn(List.of(
					new HabilitacionSnapshot(1L, ESPACIO_ID, Instant.EPOCH, null, true),
					new HabilitacionSnapshot(2L, 21L, Instant.EPOCH, INICIO.minusSeconds(1), true),
					new HabilitacionSnapshot(3L, 22L, Instant.EPOCH, null, true)));
			given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.of(espacio(true)));
			given(espacios.find(ORG_ID, 22L, INICIO)).willReturn(Optional.empty());
			given(turnos.findVivosDeEspacioQueCruzan(ORG_ID, ESPACIO_ID, INICIO, FIN)).willReturn(List.of(elMismo));

			assertThat(revalidador.espaciosLibres(pedido(TURNO_ID))).extracting(EspacioSnapshot::id).containsExactly(ESPACIO_ID);
		}
	}

	@Nested
	@DisplayName("Los feriados de una serie (U-22)")
	class Feriados {

		@Test
		@DisplayName("Son los dias vacios por feriado, no los vacios por otra razon, en una sola consulta")
		void solo_los_feriados() {
			LocalDate lunes = LocalDate.of(2026, 10, 5);
			LocalDate martes = lunes.plusDays(1);
			LocalDate miercoles = lunes.plusDays(2);
			given(disponibilidad.efectiva(ORG_ID, sede(), PROFESIONAL_ID, lunes, lunes.plusDays(3))).willReturn(List.of(
					new DisponibilidadDirectory.DiaDisponible(lunes, "FERIADO", List.of()),
					new DisponibilidadDirectory.DiaDisponible(martes, "CIERRE", List.of()),
					diaConFranja(miercoles.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
							miercoles.atStartOfDay(java.time.ZoneOffset.UTC).plusHours(1).toInstant())));

			assertThat(revalidador.feriadosQueCierran(ORG_ID, sede(), ofertaCon(1), PROFESIONAL_ID, lunes, lunes.plusDays(3)))
					.containsExactly(lunes);
		}

		@Test
		@DisplayName("Sin profesional no hay nada que omitir: esa reserva no pasa por la disponibilidad")
		void sin_profesional_no_omite_nada() {
			LocalDate lunes = LocalDate.of(2026, 10, 5);

			assertThat(revalidador.feriadosQueCierran(ORG_ID, sede(), oferta(1, false, false), null, lunes, lunes.plusDays(1))).isEmpty();
			assertThat(revalidador.feriadosQueCierran(ORG_ID, sede(), ofertaCon(1), null, lunes, lunes.plusDays(1))).isEmpty();
			org.mockito.Mockito.verifyNoInteractions(disponibilidad);
		}
	}

	private void conEspacio(EspacioSnapshot espacio) {
		given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.of(espacio));
	}

	private static RevalidadorDeSlot.Pedido conBoxElegido() {
		return new RevalidadorDeSlot.Pedido(ORG_ID, CONSULTORIO_ID, sede(), ofertaCon(1), INICIO, FIN,
				PROFESIONAL_ID, null, ESPACIO_ID, false);
	}

	private static EspacioSnapshot espacioDe(long id) {
		return new EspacioSnapshot(id, ORG_ID, CONSULTORIO_ID, "Box " + id, "BOX", 1, Instant.EPOCH, null, true, true);
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
