package com.akine.identity.domain;

import com.akine.identity.domain.exception.AccountNotFoundException;
import com.akine.identity.domain.exception.InvalidAccountTransitionException;
import com.akine.identity.domain.exception.InvalidCredentialsException;
import com.akine.identity.domain.exception.InvalidVerificationTokenException;
import com.akine.identity.domain.exception.PasswordPolicyViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El modelo de identidad: cuenta, maquina de estados, tokens y valores.
 *
 * <p>Son reglas puras, sin base y sin Spring. Que se puedan recorrer exhaustivamente en
 * milisegundos es la razon por la que viven en {@code domain} y no repartidas en servicios.
 */
class IdentityDomainTest {

	private static final Instant AHORA = Instant.parse("2026-03-01T10:00:00Z");

	@Nested
	@DisplayName("Maquina de estados")
	class MaquinaDeEstados {

		@Test
		@DisplayName("toda cuenta nace PENDIENTE_ACTIVACION y solo asi")
		void toda_cuenta_nace_pendiente() {
			assertThat(AccountStateMachine.ESTADO_INICIAL)
					.isEqualTo(EstadoCuenta.PENDIENTE_ACTIVACION);
			assertThat(AccountStateMachine.isAllowed(null, EstadoCuenta.PENDIENTE_ACTIVACION))
					.isTrue();
			assertThat(AccountStateMachine.isAllowed(null, EstadoCuenta.ACTIVA)).isFalse();
			assertThat(AccountStateMachine.allowedTargets(null))
					.containsExactly(EstadoCuenta.PENDIENTE_ACTIVACION);
		}

		@Test
		@DisplayName("la tabla de transiciones es exactamente la del diseño")
		void la_tabla_es_la_del_diseno() {
			assertThat(AccountStateMachine.allowedTargets(EstadoCuenta.PENDIENTE_ACTIVACION))
					.containsExactlyInAnyOrder(EstadoCuenta.ACTIVA, EstadoCuenta.DESACTIVADA);
			assertThat(AccountStateMachine.allowedTargets(EstadoCuenta.ACTIVA))
					.containsExactlyInAnyOrder(EstadoCuenta.BLOQUEADA, EstadoCuenta.DESACTIVADA);
			assertThat(AccountStateMachine.allowedTargets(EstadoCuenta.BLOQUEADA))
					.containsExactlyInAnyOrder(EstadoCuenta.ACTIVA, EstadoCuenta.DESACTIVADA);
			assertThat(AccountStateMachine.allowedTargets(EstadoCuenta.DESACTIVADA)).isEmpty();
		}

		@ParameterizedTest
		@EnumSource(EstadoCuenta.class)
		@DisplayName("repetir un estado nunca esta permitido: no es idempotencia, es una carrera")
		void repetir_un_estado_nunca_esta_permitido(EstadoCuenta estado) {
			assertThat(AccountStateMachine.isAllowed(estado, estado)).isFalse();
		}

		@Test
		@DisplayName("DESACTIVADA es terminal: no se vuelve desde la aplicacion")
		void desactivada_es_terminal() {
			assertThat(AccountStateMachine.isAllowed(EstadoCuenta.DESACTIVADA, EstadoCuenta.ACTIVA))
					.isFalse();
			assertThatThrownBy(() -> AccountStateMachine.assertTransitionAllowed(
					EstadoCuenta.DESACTIVADA, EstadoCuenta.ACTIVA))
					.isInstanceOf(InvalidAccountTransitionException.class);
		}

		@Test
		@DisplayName("bloquear y desactivar exigen motivo y cortan sesiones; activar no")
		void bloquear_y_desactivar_exigen_motivo_y_cortan_sesiones() {
			assertThat(AccountStateMachine.requiresReason(EstadoCuenta.BLOQUEADA)).isTrue();
			assertThat(AccountStateMachine.requiresReason(EstadoCuenta.DESACTIVADA)).isTrue();
			assertThat(AccountStateMachine.requiresReason(EstadoCuenta.ACTIVA)).isFalse();

			assertThat(AccountStateMachine.revokesSessions(EstadoCuenta.BLOQUEADA)).isTrue();
			assertThat(AccountStateMachine.revokesSessions(EstadoCuenta.DESACTIVADA)).isTrue();
			assertThat(AccountStateMachine.revokesSessions(EstadoCuenta.ACTIVA)).isFalse();
		}

		@Test
		@DisplayName("un destino nulo no esta permitido")
		void un_destino_nulo_no_esta_permitido() {
			assertThat(AccountStateMachine.isAllowed(EstadoCuenta.ACTIVA, null)).isFalse();
		}

		@Test
		@DisplayName("solo ACTIVA habilita el login")
		void solo_activa_habilita_el_login() {
			assertThat(EstadoCuenta.ACTIVA.permiteLogin()).isTrue();
			assertThat(EstadoCuenta.PENDIENTE_ACTIVACION.permiteLogin()).isFalse();
			assertThat(EstadoCuenta.BLOQUEADA.permiteLogin()).isFalse();
			assertThat(EstadoCuenta.DESACTIVADA.permiteLogin()).isFalse();
		}
	}

	@Nested
	@DisplayName("Cuenta")
	class CuentaTest {

		@Test
		@DisplayName("nace pendiente, con el email normalizado y sin intentos fallidos")
		void nace_pendiente() {
			Cuenta cuenta = new Cuenta("  Ana.Gomez@Ejemplo.TEST ", "Ana", "Gomez", "{h}x");

			assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.PENDIENTE_ACTIVACION);
			assertThat(cuenta.getEmail()).isEqualTo("Ana.Gomez@Ejemplo.TEST");
			assertThat(cuenta.getEmailNormalizado()).isEqualTo("ana.gomez@ejemplo.test");
			assertThat(cuenta.getIntentosFallidos()).isZero();
			assertThat(cuenta.isActive()).isTrue();
			assertThat(cuenta.getDeletedAt()).isNull();
			assertThat(cuenta.getUltimoLoginEn()).isNull();
			assertThat(cuenta.puedeAutenticarse())
					.as("pendiente de activacion no habilita login")
					.isFalse();
		}

		@Test
		@DisplayName("una cuenta invitada nace sin credencial y no puede autenticarse")
		void una_cuenta_invitada_nace_sin_credencial() {
			Cuenta invitada = new Cuenta("otra@ejemplo.test", "Luis", "Paz", null);
			invitada.transicionarA(EstadoCuenta.ACTIVA, null, AHORA);

			assertThat(invitada.getPasswordHash()).isNull();
			assertThat(invitada.puedeAutenticarse()).isFalse();

			invitada.fijarPasswordHash("{h}nuevo");
			assertThat(invitada.puedeAutenticarse()).isTrue();
		}

		@Test
		@DisplayName("bloquear deja la marca y el motivo; desbloquear los limpia")
		void bloquear_deja_marca_y_desbloquear_la_limpia() {
			Cuenta cuenta = activa();
			cuenta.registrarLoginFallido();

			cuenta.transicionarA(EstadoCuenta.BLOQUEADA, "sospecha", AHORA);
			assertThat(cuenta.getBloqueadaEn()).isEqualTo(AHORA);
			assertThat(cuenta.getBloqueadaMotivo()).isEqualTo("sospecha");
			assertThat(cuenta.puedeAutenticarse()).isFalse();

			cuenta.transicionarA(EstadoCuenta.ACTIVA, null, AHORA.plusSeconds(60));
			assertThat(cuenta.getBloqueadaEn()).isNull();
			assertThat(cuenta.getBloqueadaMotivo()).isNull();
			assertThat(cuenta.getIntentosFallidos()).isZero();
		}

		@Test
		@DisplayName("desactivar es baja logica: la fila queda, el acceso no")
		void desactivar_es_baja_logica() {
			Cuenta cuenta = activa();

			cuenta.transicionarA(EstadoCuenta.DESACTIVADA, "baja del profesional", AHORA);

			assertThat(cuenta.isActive()).isFalse();
			assertThat(cuenta.getDeletedAt()).isEqualTo(AHORA);
			assertThat(cuenta.puedeAutenticarse()).isFalse();
		}

		@Test
		@DisplayName("sin motivo, la transicion que lo exige no se aplica")
		void sin_motivo_la_transicion_no_se_aplica() {
			Cuenta cuenta = activa();

			assertThatThrownBy(() -> cuenta.transicionarA(EstadoCuenta.BLOQUEADA, "  ", AHORA))
					.isInstanceOf(IllegalArgumentException.class);
			assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
		}

		@Test
		@DisplayName("una transicion prohibida no cambia el estado")
		void una_transicion_prohibida_no_cambia_el_estado() {
			Cuenta cuenta = activa();

			assertThatThrownBy(() -> cuenta.transicionarA(EstadoCuenta.ACTIVA, null, AHORA))
					.isInstanceOf(InvalidAccountTransitionException.class);
			assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
		}

		@Test
		@DisplayName("el contador de fallos sube y se limpia al entrar bien")
		void el_contador_de_fallos_sube_y_se_limpia() {
			Cuenta cuenta = activa();

			assertThat(cuenta.registrarLoginFallido()).isEqualTo(1);
			assertThat(cuenta.registrarLoginFallido()).isEqualTo(2);

			cuenta.registrarLoginExitoso(AHORA);
			assertThat(cuenta.getIntentosFallidos()).isZero();
			assertThat(cuenta.getUltimoLoginEn()).isEqualTo(AHORA);
		}

		@Test
		@DisplayName("fijar una credencial vacia es un error, no una credencial")
		void fijar_una_credencial_vacia_falla() {
			Cuenta cuenta = activa();

			assertThatThrownBy(() -> cuenta.fijarPasswordHash(null))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> cuenta.fijarPasswordHash("  "))
					.isInstanceOf(IllegalArgumentException.class);
		}

		private Cuenta activa() {
			Cuenta cuenta = conId(
					new Cuenta("ana@ejemplo.test", "Ana", "Gomez", "{h}x"), CUENTA_ID);
			cuenta.transicionarA(EstadoCuenta.ACTIVA, null, AHORA.minusSeconds(3600));
			return cuenta;
		}
	}

	@Nested
	@DisplayName("Tokens de verificacion")
	class TokensDeVerificacion {

		@Test
		@DisplayName("cada tipo tiene su vigencia: activacion 7 dias, reset 30 minutos")
		void cada_tipo_tiene_su_vigencia() {
			assertThat(TipoTokenVerificacion.ACTIVACION.vigencia()).isEqualTo(Duration.ofDays(7));
			assertThat(TipoTokenVerificacion.RESET.vigencia()).isEqualTo(Duration.ofMinutes(30));

			TokenVerificacion reset = new TokenVerificacion(
					CUENTA_ID, TipoTokenVerificacion.RESET, TokenDigest.of("x"), AHORA);
			assertThat(reset.getExpiraEn()).isEqualTo(AHORA.plus(30, ChronoUnit.MINUTES));
			assertThat(reset.getCuentaId()).isEqualTo(CUENTA_ID);
			assertThat(reset.getCreatedAt()).isEqualTo(AHORA);
		}

		@Test
		@DisplayName("un token sirve mientras no se uso, no se invalido y no vencio")
		void un_token_sirve_mientras_esta_vivo() {
			TokenVerificacion token = new TokenVerificacion(
					CUENTA_ID, TipoTokenVerificacion.RESET, TokenDigest.of("x"), AHORA);

			assertThat(token.esUtilizableEn(AHORA.plusSeconds(60))).isTrue();
			assertThat(token.esUtilizableEn(AHORA.plus(31, ChronoUnit.MINUTES))).isFalse();
		}

		@Test
		@DisplayName("consumir dos veces el mismo token es un error de programacion")
		void consumir_dos_veces_falla() {
			TokenVerificacion token = new TokenVerificacion(
					CUENTA_ID, TipoTokenVerificacion.ACTIVACION, TokenDigest.of("x"), AHORA);

			token.consumir(AHORA.plusSeconds(10));
			assertThat(token.getUsadoEn()).isEqualTo(AHORA.plusSeconds(10));
			assertThat(token.esUtilizableEn(AHORA.plusSeconds(20))).isFalse();

			assertThatThrownBy(() -> token.consumir(AHORA.plusSeconds(20)))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("invalidar un token ya consumido no lo pisa")
		void invalidar_un_token_consumido_no_lo_pisa() {
			TokenVerificacion token = new TokenVerificacion(
					CUENTA_ID, TipoTokenVerificacion.ACTIVACION, TokenDigest.of("x"), AHORA);
			token.consumir(AHORA.plusSeconds(10));

			token.invalidar(AHORA.plusSeconds(20));

			assertThat(token.getInvalidadoEn())
					.as("un token consumido conserva su historia: no se reescribe")
					.isNull();
		}

		@Test
		@DisplayName("invalidar deja el token inservible")
		void invalidar_deja_el_token_inservible() {
			TokenVerificacion token = new TokenVerificacion(
					CUENTA_ID, TipoTokenVerificacion.ACTIVACION, TokenDigest.of("x"), AHORA);

			token.invalidar(AHORA.plusSeconds(5));

			assertThat(token.getInvalidadoEn()).isEqualTo(AHORA.plusSeconds(5));
			assertThat(token.esUtilizableEn(AHORA.plusSeconds(6))).isFalse();
		}
	}

	@Nested
	@DisplayName("Refresh")
	class Refresh {

		@Test
		@DisplayName("es canjeable mientras no se uso, no se revoco y no vencio")
		void es_canjeable_mientras_esta_vivo() {
			RefreshToken token = refresh();

			assertThat(token.esCanjeableEn(AHORA.plusSeconds(60))).isTrue();
			assertThat(token.esCanjeableEn(AHORA.plus(13, ChronoUnit.HOURS))).isFalse();
		}

		@Test
		@DisplayName("rotar marca el usado y apunta al sucesor")
		void rotar_marca_el_usado_y_apunta_al_sucesor() {
			RefreshToken token = refresh();

			token.marcarRotado(77L, AHORA.plusSeconds(30));

			assertThat(token.getUsadoEn()).isEqualTo(AHORA.plusSeconds(30));
			assertThat(token.getReemplazadoPorId()).isEqualTo(77L);
			assertThat(token.esCanjeableEn(AHORA.plusSeconds(31))).isFalse();
		}

		@Test
		@DisplayName("revocar dos veces conserva el primer motivo")
		void revocar_dos_veces_conserva_el_primer_motivo() {
			RefreshToken token = refresh();

			token.revocar(MotivoRevocacion.BLOQUEO, AHORA.plusSeconds(10));
			token.revocar(MotivoRevocacion.LOGOUT, AHORA.plusSeconds(20));

			assertThat(token.getMotivoRevocacion()).isEqualTo(MotivoRevocacion.BLOQUEO);
			assertThat(token.getRevocadoEn()).isEqualTo(AHORA.plusSeconds(10));
		}

		@Test
		@DisplayName("ip y user agent se truncan en vez de romper la insercion")
		void ip_y_user_agent_se_truncan() {
			RefreshToken token = new RefreshToken(
					CUENTA_ID, "familia", "hash", AHORA, AHORA.plusSeconds(3600),
					null, null, "x".repeat(60), "u".repeat(300));

			assertThat(token.getIp()).hasSize(45);
			assertThat(token.getUserAgent()).hasSize(200);
			assertThat(token.getContextOrganizationId()).isNull();
		}

		@Test
		@DisplayName("el contexto se recuerda para poder reemitir el access igual")
		void el_contexto_se_recuerda() {
			RefreshToken token = refresh();

			token.recordarContexto(10L, 20L);

			assertThat(token.getContextOrganizationId()).isEqualTo(10L);
			assertThat(token.getContextConsultorioId()).isEqualTo(20L);
			assertThat(token.getFamiliaId()).isEqualTo("familia");
			assertThat(token.getTokenHash()).isEqualTo("hash");
			assertThat(token.getEmitidoEn()).isEqualTo(AHORA);
		}

		private RefreshToken refresh() {
			return new RefreshToken(CUENTA_ID, "familia", "hash", AHORA,
					AHORA.plus(12, ChronoUnit.HOURS), null, null, "10.0.0.1", "agente");
		}
	}

	@Nested
	@DisplayName("Valores")
	class Valores {

		@Test
		@DisplayName("el email se normaliza a minusculas y sin espacios")
		void el_email_se_normaliza() {
			assertThat(EmailNormalizado.of("  Ana.Gomez@Ejemplo.TEST  "))
					.isEqualTo("ana.gomez@ejemplo.test");
		}

		@Test
		@DisplayName("el digest del token es SHA-256 en hexadecimal, estable y de 64 caracteres")
		void el_digest_es_sha256_hexadecimal() {
			String digest = TokenDigest.of("un-token-cualquiera");

			assertThat(digest).hasSize(TokenDigest.LARGO_HEX);
			assertThat(digest).matches("[0-9a-f]{64}");
			assertThat(digest).isEqualTo(TokenDigest.of("un-token-cualquiera"));
			assertThat(digest).isNotEqualTo(TokenDigest.of("otro-token-cualquiera"));
		}

		@Test
		@DisplayName("no se hashea un token vacio")
		void no_se_hashea_un_token_vacio() {
			assertThatThrownBy(() -> TokenDigest.of(null))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> TokenDigest.of("  "))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("el registro de idempotencia distingue completado de duplicado")
		void el_registro_de_idempotencia_distingue_los_dos_desenlaces() {
			OnboardingRegistro completado = OnboardingRegistro.completado(
					"clave-1", "ana@ejemplo.test", CUENTA_ID, 10L, 20L, AHORA);
			OnboardingRegistro duplicado = OnboardingRegistro.duplicado(
					"clave-2", "ana@ejemplo.test", AHORA);

			assertThat(completado.getEstado()).isEqualTo(EstadoOnboarding.COMPLETADO);
			assertThat(completado.getCuentaId()).isEqualTo(CUENTA_ID);
			assertThat(completado.getOrganizationId()).isEqualTo(10L);
			assertThat(completado.getConsultorioId()).isEqualTo(20L);
			assertThat(completado.getClaveIdempotencia()).isEqualTo("clave-1");
			assertThat(completado.getEmailNormalizado()).isEqualTo("ana@ejemplo.test");
			assertThat(completado.getCreatedAt()).isEqualTo(AHORA);

			// El duplicado no guarda el id de la cuenta ajena: el alta de un tercero no puede
			// dejar rastro de la cuenta de otra persona.
			assertThat(duplicado.getEstado()).isEqualTo(EstadoOnboarding.DUPLICADO);
			assertThat(duplicado.getCuentaId()).isNull();
			assertThat(duplicado.getOrganizationId()).isNull();
		}
	}

	@Nested
	@DisplayName("Excepciones")
	class Excepciones {

		@Test
		@DisplayName("la transicion invalida dice de donde a donde, para el 409")
		void la_transicion_invalida_dice_de_donde_a_donde() {
			InvalidAccountTransitionException excepcion = new InvalidAccountTransitionException(
					EstadoCuenta.BLOQUEADA, EstadoCuenta.BLOQUEADA);

			assertThat(excepcion.getDesde()).isEqualTo(EstadoCuenta.BLOQUEADA);
			assertThat(excepcion.getHacia()).isEqualTo(EstadoCuenta.BLOQUEADA);
			assertThat(excepcion.getMessage()).contains("BLOQUEADA");
		}

		@Test
		@DisplayName("las excepciones de rechazo no filtran nada en su mensaje")
		void las_excepciones_de_rechazo_no_filtran_nada() {
			assertThat(new InvalidCredentialsException().getMessage())
					.doesNotContain("@", "password");
			assertThat(new InvalidVerificationTokenException().getMessage())
					.doesNotContain("expirado", "usado");
			assertThat(new AccountNotFoundException(CUENTA_ID).getMessage())
					.contains(String.valueOf(CUENTA_ID));
			assertThat(new PasswordPolicyViolationException("muy corta").getMessage())
					.isNotBlank();
		}
	}
}
