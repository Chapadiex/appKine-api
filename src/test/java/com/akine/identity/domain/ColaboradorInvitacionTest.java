package com.akine.identity.domain;

import com.akine.identity.domain.exception.InvitacionNoPendienteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariantes de la invitacion a colaborar (M05, AKINE-02.03).
 *
 * <p>Lo que se prueba aca es lo que la base <b>no puede</b> sostener sola:
 *
 * <ul>
 *   <li>Que expirar se derive del reloj y no sea un estado. Si alguien lo materializa en la
 *       columna, la base deja de poder distinguir "pendiente y vencida" de "pendiente", y el
 *       unique que impide invitar dos veces a la misma persona pasa a bloquearla para
 *       siempre.</li>
 *   <li>Que los tres estados terminales lo sean de verdad. El {@code CHECK} de la migracion
 *       impide filas incoherentes, pero no impide que un servicio intente resolver dos veces:
 *       eso tiene que fallar aca, con su excepcion, y no con una violacion de integridad.</li>
 *   <li>Que reenviar rote el token conservando el pedido. Es la unica mutacion que toca la
 *       credencial.</li>
 * </ul>
 */
class ColaboradorInvitacionTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 3L;
	private static final long ADMIN_ID = 9L;
	private static final long INVITADO_ID = 42L;
	private static final long MEMBERSHIP_ID = 77L;

	private static final String EMAIL = "kine@centro.test";
	private static final String ROL = "PROFESIONAL";
	private static final String HASH = "a".repeat(64);
	private static final String HASH_NUEVO = "b".repeat(64);

	private static final Instant AHORA = Instant.parse("2026-08-25T12:00:00Z");
	private static final Instant VENCE = AHORA.plus(Duration.ofDays(14));

	private ColaboradorInvitacion nueva() {
		return new ColaboradorInvitacion(
				ORG_ID, CONSULTORIO_ID, EMAIL, ROL, HASH, VENCE, ADMIN_ID);
	}

	@Nested
	@DisplayName("Vencimiento")
	class Vencimiento {

		@Test
		@DisplayName("vencer se deriva del reloj: la columna nunca dice EXPIRADA")
		void vencer_no_es_un_estado() {
			ColaboradorInvitacion invitacion = nueva();

			assertThat(invitacion.estaVencida(AHORA)).isFalse();
			assertThat(invitacion.estaVencida(VENCE.plusSeconds(1))).isTrue();

			// Y lo que importa: el estado NO cambio. Sigue PENDIENTE, que es lo que hace que
			// el unique de la base la siga contando como viva y que el camino correcto sea
			// reenviar y no emitir otra.
			assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		}

		@Test
		@DisplayName("estaViva exige las dos cosas: pendiente y sin vencer")
		void viva_es_pendiente_y_vigente() {
			ColaboradorInvitacion invitacion = nueva();
			assertThat(invitacion.estaViva(AHORA)).isTrue();

			// Vencida por reloj.
			assertThat(invitacion.estaViva(VENCE.plusSeconds(1))).isFalse();

			// Y resuelta, aunque la fecha no haya llegado.
			invitacion.rechazar(null, AHORA);
			assertThat(invitacion.estaViva(AHORA)).isFalse();
		}

		@Test
		@DisplayName("una invitacion aceptada hace meses no es 'vencida' en ningun sentido util")
		void aceptada_con_fecha_pasada_no_es_lo_mismo_que_vencida() {
			ColaboradorInvitacion invitacion = nueva();
			invitacion.aceptar(INVITADO_ID, MEMBERSHIP_ID, AHORA);

			// `estaVencida` dice que si —la fecha paso— y por eso la pantalla nunca puede
			// preguntar solo por eso: tiene que mirar el estado primero.
			assertThat(invitacion.estaVencida(VENCE.plusSeconds(1))).isTrue();
			assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.ACEPTADA);
		}
	}

	@Nested
	@DisplayName("Estados terminales")
	class Terminales {

		@Test
		@DisplayName("aceptar deja la invitacion atada a la membership que creo")
		void aceptar_ata_la_membership() {
			ColaboradorInvitacion invitacion = nueva();
			invitacion.aceptar(INVITADO_ID, MEMBERSHIP_ID, AHORA);

			assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.ACEPTADA);
			assertThat(invitacion.getAceptadaPorAccountId()).isEqualTo(INVITADO_ID);
			assertThat(invitacion.getMembershipId()).isEqualTo(MEMBERSHIP_ID);
			assertThat(invitacion.getResueltaEn()).isEqualTo(AHORA);
		}

		@Test
		@DisplayName("rechazar no exige motivo; cancelar si")
		void el_motivo_es_asimetrico_a_proposito() {
			// Al invitado no se le pide que explique por que no quiere entrar a trabajar.
			ColaboradorInvitacion rechazada = nueva();
			rechazada.rechazar(null, AHORA);
			assertThat(rechazada.getEstado()).isEqualTo(EstadoInvitacion.RECHAZADA);
			assertThat(rechazada.getResolucionNota()).isNull();

			// El administrador si: cancelar es su decision sobre alguien a quien ya le escribio.
			ColaboradorInvitacion cancelada = nueva();
			assertThatThrownBy(() -> cancelada.cancelar("   ", AHORA))
					.isInstanceOf(IllegalArgumentException.class);

			cancelada.cancelar("  Se equivoco de direccion  ", AHORA);
			assertThat(cancelada.getResolucionNota()).isEqualTo("Se equivoco de direccion");
		}

		@Test
		@DisplayName("desde un estado terminal no se sale por ningun camino")
		void los_terminales_son_terminales() {
			ColaboradorInvitacion aceptada = nueva();
			aceptada.aceptar(INVITADO_ID, MEMBERSHIP_ID, AHORA);

			// Los cuatro caminos que mutan tienen que rechazar, y con la misma excepcion: es la
			// que la capa API traduce a 409 invitacion-ya-resuelta. Un 200 silencioso le diria
			// a la segunda persona que su accion tuvo efecto cuando quedo registrada la primera.
			assertThatThrownBy(() -> aceptada.aceptar(INVITADO_ID, MEMBERSHIP_ID, AHORA))
					.isInstanceOf(InvitacionNoPendienteException.class);
			assertThatThrownBy(() -> aceptada.rechazar(null, AHORA))
					.isInstanceOf(InvitacionNoPendienteException.class);
			assertThatThrownBy(() -> aceptada.cancelar("tarde", AHORA))
					.isInstanceOf(InvitacionNoPendienteException.class);
			assertThatThrownBy(() -> aceptada.reenviar(HASH_NUEVO, VENCE))
					.isInstanceOf(InvitacionNoPendienteException.class);
		}

		@Test
		@DisplayName("la maquina de estados solo sale de PENDIENTE")
		void la_tabla_de_transiciones() {
			assertThat(EstadoInvitacion.PENDIENTE.puedePasarA(EstadoInvitacion.ACEPTADA)).isTrue();
			assertThat(EstadoInvitacion.PENDIENTE.puedePasarA(EstadoInvitacion.RECHAZADA)).isTrue();
			assertThat(EstadoInvitacion.PENDIENTE.puedePasarA(EstadoInvitacion.CANCELADA)).isTrue();
			assertThat(EstadoInvitacion.PENDIENTE.puedePasarA(EstadoInvitacion.PENDIENTE)).isFalse();

			assertThat(EstadoInvitacion.PENDIENTE.esTerminal()).isFalse();
			for (EstadoInvitacion terminal : new EstadoInvitacion[] {
					EstadoInvitacion.ACEPTADA, EstadoInvitacion.RECHAZADA,
					EstadoInvitacion.CANCELADA }) {
				assertThat(terminal.esTerminal()).isTrue();
				assertThat(terminal.puedePasarA(EstadoInvitacion.ACEPTADA)).isFalse();
			}
		}
	}

	@Test
	@DisplayName("reenviar rota el token y conserva el pedido")
	void reenviar_rota_el_token() {
		ColaboradorInvitacion invitacion = nueva();
		Instant vencimientoNuevo = VENCE.plus(Duration.ofDays(14));

		invitacion.reenviar(HASH_NUEVO, vencimientoNuevo);

		// El token anterior deja de servir en el mismo acto: la columna es una sola. Dos enlaces
		// validos para la misma invitacion dejarian al invitado eligiendo cual usar.
		assertThat(invitacion.getTokenHash()).isEqualTo(HASH_NUEVO);
		assertThat(invitacion.getExpiraEn()).isEqualTo(vencimientoNuevo);

		// Y lo que NO cambia es lo que hace que el listado siga siendo util: sigue siendo el
		// mismo pedido, del mismo administrador, al mismo email.
		assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		assertThat(invitacion.getInvitadaPorAccountId()).isEqualTo(ADMIN_ID);
		assertThat(invitacion.getEmailNormalizado()).isEqualTo(EMAIL);
		assertThat(invitacion.getRoleCode()).isEqualTo(ROL);
	}
}
