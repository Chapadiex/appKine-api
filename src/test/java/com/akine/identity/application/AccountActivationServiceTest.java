package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.identity.domain.exception.InvalidVerificationTokenException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.PASSWORD_VALIDA;
import static com.akine.identity.IdentityFixtures.conId;
import static com.akine.identity.IdentityFixtures.cuentaPendiente;
import static com.akine.identity.IdentityFixtures.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Confirmacion del enlace que habilita una cuenta (RF-M02-001). */
@ExtendWith(MockitoExtension.class)
class AccountActivationServiceTest {

	private static final String TOKEN_PLANO = "token-de-activacion";

	@Mock
	private CuentaRepositoryPort cuentaRepository;

	@Mock
	private TokenVerificacionRepositoryPort tokenRepository;

	@Mock
	private PasswordHasher passwordHasher;

	@Mock
	private AuditTrail auditTrail;

	private AccountActivationService service;

	private AccountActivationService service() {
		if (service == null) {
			service = new AccountActivationService(cuentaRepository, tokenRepository,
					new PasswordPolicy(), passwordHasher, auditTrail);
		}
		return service;
	}

	private void tokenVivo(TokenVerificacion token) {
		given(tokenRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(token));
	}

	@Test
	@DisplayName("el alta self-service se activa sin pedir contrasena de nuevo")
	void el_alta_self_service_se_activa_sin_pedir_contrasena() {
		Cuenta cuenta = cuentaPendiente();
		tokenVivo(token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				CUENTA_ID, TipoTokenVerificacion.ACTIVACION)).willReturn(List.of());

		Cuenta resultado = service().activar(TOKEN_PLANO, null);

		assertThat(resultado.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
		// La credencial ya existe: aceptar una nueva aca seria un camino de toma de cuenta que
		// esquiva el reset, que si revoca todas las sesiones.
		verifyNoInteractions(passwordHasher);
		verify(cuentaRepository).save(cuenta);
		verify(auditTrail).record(any());
	}

	@Test
	@DisplayName("una cuenta invitada fija su credencial al activarse")
	void una_cuenta_invitada_fija_su_credencial() {
		Cuenta invitada = conId(
				new Cuenta("luis@ejemplo.test", "Luis", "Paz", null), CUENTA_ID);
		tokenVivo(token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(invitada));
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				CUENTA_ID, TipoTokenVerificacion.ACTIVACION)).willReturn(List.of());
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}nuevo");

		Cuenta resultado = service().activar(TOKEN_PLANO, PASSWORD_VALIDA);

		assertThat(resultado.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
		assertThat(resultado.getPasswordHash()).isEqualTo("{h}nuevo");
	}

	@Test
	@DisplayName("una cuenta invitada sin contrasena valida no se activa")
	void una_cuenta_invitada_sin_contrasena_no_se_activa() {
		Cuenta invitada = conId(
				new Cuenta("luis@ejemplo.test", "Luis", "Paz", null), CUENTA_ID);
		tokenVivo(token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(invitada));

		assertThatThrownBy(() -> service().activar(TOKEN_PLANO, "corta"))
				.isInstanceOf(com.akine.identity.domain.exception.PasswordPolicyViolationException.class);

		assertThat(invitada.getEstado()).isEqualTo(EstadoCuenta.PENDIENTE_ACTIVACION);
		verify(cuentaRepository, never()).save(any());
	}

	@Test
	@DisplayName("los demas enlaces de activacion dejan de servir")
	void los_demas_enlaces_dejan_de_servir() {
		Cuenta cuenta = cuentaPendiente();
		TokenVerificacion usado = token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO);
		TokenVerificacion otroReenvio = conId(new TokenVerificacion(
				CUENTA_ID, TipoTokenVerificacion.ACTIVACION, TokenDigest.of("otro"),
				Instant.now()), 501L);
		tokenVivo(usado);
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				CUENTA_ID, TipoTokenVerificacion.ACTIVACION))
				.willReturn(List.of(usado, otroReenvio));

		service().activar(TOKEN_PLANO, null);

		// Si se reenvio el correo tres veces quedaron tres enlaces vivos, y el que no se uso
		// sigue siendo una llave.
		assertThat(otroReenvio.getInvalidadoEn()).isNotNull();
		assertThat(usado.getUsadoEn()).isNotNull();
		verify(tokenRepository).saveAll(List.of(otroReenvio));
	}

	@Test
	@DisplayName("token inexistente, de otro tipo, vencido o vacio: el mismo rechazo")
	void los_cuatro_rechazos_son_uno_solo() {
		AccountActivationService service = service();

		assertThatThrownBy(() -> service.activar(null, null))
				.isInstanceOf(InvalidVerificationTokenException.class);
		assertThatThrownBy(() -> service.activar("  ", null))
				.isInstanceOf(InvalidVerificationTokenException.class);

		given(tokenRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.empty());
		assertThatThrownBy(() -> service.activar(TOKEN_PLANO, null))
				.isInstanceOf(InvalidVerificationTokenException.class);
	}

	@Test
	@DisplayName("un token de reset presentado en la activacion no sirve")
	void un_token_de_reset_no_sirve_para_activar() {
		tokenVivo(token(TipoTokenVerificacion.RESET, TOKEN_PLANO));

		assertThatThrownBy(() -> service().activar(TOKEN_PLANO, null))
				.isInstanceOf(InvalidVerificationTokenException.class);
	}

	@Test
	@DisplayName("un token que apunta a una cuenta inexistente no activa nada")
	void un_token_huerfano_no_activa_nada() {
		tokenVivo(token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service().activar(TOKEN_PLANO, null))
				.isInstanceOf(InvalidVerificationTokenException.class);
	}
}
