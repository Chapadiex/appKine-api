package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.MotivoRevocacion;
import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.identity.domain.exception.InvalidVerificationTokenException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.RefreshTokenRepositoryPort;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.EMAIL;
import static com.akine.identity.IdentityFixtures.PASSWORD_VALIDA;
import static com.akine.identity.IdentityFixtures.conId;
import static com.akine.identity.IdentityFixtures.cuentaActiva;
import static com.akine.identity.IdentityFixtures.cuentaBloqueada;
import static com.akine.identity.IdentityFixtures.refreshVivo;
import static com.akine.identity.IdentityFixtures.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Restablecimiento de contrasena (RF-M02-003).
 *
 * <p>Los dos tests con consecuencia real son
 * {@link #cada_pedido_invalida_los_tokens_anteriores()} —sin eso, tres clicks impacientes dejan
 * tres llaves vivas y basta comprometer el correo mas viejo— y
 * {@link #confirmar_revoca_todas_las_sesiones()} —sin eso el reset no expulsa a quien ya entro,
 * que es justo el caso en el que mas se lo necesita—.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

	private static final String TOKEN_PLANO = "token-de-reset";
	private static final String ENLACE = "https://app.akine.test/restablecer?token=x";

	@Mock
	private CuentaRepositoryPort cuentaRepository;

	@Mock
	private TokenVerificacionRepositoryPort tokenRepository;

	@Mock
	private RefreshTokenRepositoryPort refreshTokenRepository;

	@Mock
	private NotificationOutboxPort notificationOutbox;

	@Mock
	private VerificationLinkBuilder linkBuilder;

	@Mock
	private PasswordHasher passwordHasher;

	@Mock
	private TokenGenerator tokenGenerator;

	@Mock
	private AuditTrail auditTrail;

	private PasswordResetService service;

	private PasswordResetService service() {
		if (service == null) {
			service = new PasswordResetService(cuentaRepository, tokenRepository,
					refreshTokenRepository, notificationOutbox, linkBuilder,
					new PasswordPolicy(), passwordHasher, tokenGenerator, auditTrail);
		}
		return service;
	}

	private NotificationOutboxPort.Notificacion capturarNotificacion() {
		ArgumentCaptor<NotificationOutboxPort.Notificacion> captor =
				ArgumentCaptor.forClass(NotificationOutboxPort.Notificacion.class);
		verify(notificationOutbox).encolar(captor.capture());
		return captor.getValue();
	}

	// =================================================================================
	// Pedido
	// =================================================================================

	@Test
	@DisplayName("el pedido emite el token y encola el correo con el enlace, no con el token")
	void el_pedido_emite_el_token_y_encola_el_correo() {
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(cuenta));
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				CUENTA_ID, TipoTokenVerificacion.RESET)).willReturn(List.of());
		given(tokenGenerator.nuevoToken()).willReturn(TOKEN_PLANO);
		given(tokenRepository.save(any()))
				.willReturn(token(TipoTokenVerificacion.RESET, TOKEN_PLANO));
		given(linkBuilder.enlaceDe(TipoTokenVerificacion.RESET, TOKEN_PLANO)).willReturn(ENLACE);

		service().solicitar(EMAIL);

		NotificationOutboxPort.Notificacion notificacion = capturarNotificacion();
		assertThat(notificacion.tipo())
				.isEqualTo(NotificationOutboxPort.TipoNotificacion.RESET_PASSWORD);
		assertThat(notificacion.enlaceSeguro()).isEqualTo(ENLACE);
		assertThat(notificacion.datosPlantilla())
				.as("el payload consultable no lleva ni el token ni el enlace (T-11)")
				.doesNotContainValue(TOKEN_PLANO)
				.doesNotContainValue(ENLACE);
		assertThat(notificacion.claveIdempotente()).isEqualTo("reset:500");
	}

	@Test
	@DisplayName("cada pedido invalida los tokens de reset anteriores")
	void cada_pedido_invalida_los_tokens_anteriores() {
		Cuenta cuenta = cuentaActiva();
		TokenVerificacion anterior = conId(new TokenVerificacion(
				CUENTA_ID, TipoTokenVerificacion.RESET, TokenDigest.of("viejo"),
				Instant.now()), 499L);
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(cuenta));
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				CUENTA_ID, TipoTokenVerificacion.RESET)).willReturn(List.of(anterior));
		given(tokenGenerator.nuevoToken()).willReturn(TOKEN_PLANO);
		given(tokenRepository.save(any()))
				.willReturn(token(TipoTokenVerificacion.RESET, TOKEN_PLANO));

		service().solicitar(EMAIL);

		assertThat(anterior.getInvalidadoEn()).isNotNull();
		verify(tokenRepository).saveAll(List.of(anterior));
	}

	@Test
	@DisplayName("un email sin cuenta no emite nada y no lanza: la respuesta es siempre la misma")
	void un_email_sin_cuenta_no_emite_nada() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.empty());

		service().solicitar(EMAIL);
		service().solicitar(null);
		service().solicitar("  ");

		verifyNoInteractions(tokenGenerator, notificationOutbox, auditTrail);
	}

	@Test
	@DisplayName("una cuenta bloqueada no recupera el acceso por correo")
	void una_cuenta_bloqueada_no_recupera_por_correo() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL))
				.willReturn(Optional.of(cuentaBloqueada()));

		service().solicitar(EMAIL);

		// El bloqueo lo levanta un administrador, no un mail.
		verifyNoInteractions(tokenGenerator, notificationOutbox);
	}

	// =================================================================================
	// Confirmacion
	// =================================================================================

	@Test
	@DisplayName("confirmar fija la credencial nueva y revoca todas las sesiones")
	void confirmar_revoca_todas_las_sesiones() {
		Cuenta cuenta = cuentaActiva();
		TokenVerificacion tokenReset = token(TipoTokenVerificacion.RESET, TOKEN_PLANO);
		given(tokenRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(tokenReset));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}nuevo");
		List<RefreshToken> vivos = List.of(refreshVivo(1L), refreshVivo(2L));
		given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
				.willReturn(vivos);

		Cuenta resultado = service().confirmar(TOKEN_PLANO, PASSWORD_VALIDA);

		assertThat(resultado.getPasswordHash()).isEqualTo("{h}nuevo");
		assertThat(tokenReset.getUsadoEn()).isNotNull();
		assertThat(vivos).allSatisfy(sesion ->
				assertThat(sesion.getMotivoRevocacion())
						.isEqualTo(MotivoRevocacion.RESET_PASSWORD));

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().details()).containsEntry("sesionesRevocadas", "2");
	}

	@Test
	@DisplayName("una contrasena que no cumple la politica no cambia nada")
	void una_contrasena_invalida_no_cambia_nada() {
		Cuenta cuenta = cuentaActiva();
		String hashOriginal = cuenta.getPasswordHash();
		given(tokenRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(token(TipoTokenVerificacion.RESET, TOKEN_PLANO)));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));

		assertThatThrownBy(() -> service().confirmar(TOKEN_PLANO, "corta"))
				.isInstanceOf(com.akine.identity.domain.exception.PasswordPolicyViolationException.class);

		assertThat(cuenta.getPasswordHash()).isEqualTo(hashOriginal);
		verify(cuentaRepository, never()).save(any());
	}

	@Test
	@DisplayName("una cuenta bloqueada entre el pedido y la confirmacion no recupera el acceso")
	void una_cuenta_bloqueada_en_el_medio_no_recupera_el_acceso() {
		given(tokenRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(token(TipoTokenVerificacion.RESET, TOKEN_PLANO)));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuentaBloqueada()));

		assertThatThrownBy(() -> service().confirmar(TOKEN_PLANO, PASSWORD_VALIDA))
				.isInstanceOf(InvalidVerificationTokenException.class);
	}

	@Test
	@DisplayName("un token de activacion, vacio o inexistente no confirma un reset")
	void un_token_que_no_sirve_no_confirma() {
		PasswordResetService service = service();

		assertThatThrownBy(() -> service.confirmar(null, PASSWORD_VALIDA))
				.isInstanceOf(InvalidVerificationTokenException.class);

		given(tokenRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO)));
		assertThatThrownBy(() -> service.confirmar(TOKEN_PLANO, PASSWORD_VALIDA))
				.isInstanceOf(InvalidVerificationTokenException.class);
	}

	@Test
	@DisplayName("sin sesiones vivas el reset igual se completa")
	void sin_sesiones_vivas_el_reset_se_completa() {
		Cuenta cuenta = cuentaActiva();
		given(tokenRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(token(TipoTokenVerificacion.RESET, TOKEN_PLANO)));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}nuevo");
		given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
				.willReturn(List.of());

		assertThat(service().confirmar(TOKEN_PLANO, PASSWORD_VALIDA)).isSameAs(cuenta);
		verify(refreshTokenRepository, never()).saveAll(any());
	}
}
