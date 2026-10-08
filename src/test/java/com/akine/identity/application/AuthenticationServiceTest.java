package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.exception.InvalidCredentialsException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.EMAIL;
import static com.akine.identity.IdentityFixtures.PASSWORD_VALIDA;
import static com.akine.identity.IdentityFixtures.cuentaActiva;
import static com.akine.identity.IdentityFixtures.cuentaBloqueada;
import static com.akine.identity.IdentityFixtures.cuentaPendiente;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Validacion de credenciales y de estado (RF-M02-002).
 *
 * <p>El test que no se puede perder es
 * {@link #cuando_el_email_no_existe_se_quema_el_mismo_trabajo()}: la anti-enumeracion por tiempo
 * no tiene ningun efecto observable en el cuerpo de la respuesta, asi que si alguien
 * "optimiza" el return temprano, ningun test de contrato se entera y el login vuelve a ser un
 * buscador de direcciones registradas.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

	@Mock
	private CuentaRepositoryPort cuentaRepository;

	@Mock
	private PasswordHasher passwordHasher;

	@Mock
	private AuditTrail auditTrail;

	@InjectMocks
	private AuthenticationService service;

	private List<AuditEntry> auditoria() {
		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
		return captor.getAllValues();
	}

	@Test
	@DisplayName("con credenciales validas la cuenta entra y queda el rastro del login")
	void con_credenciales_validas_la_cuenta_entra() {
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(cuenta));
		given(passwordHasher.matches(PASSWORD_VALIDA, cuenta.getPasswordHash())).willReturn(true);
		given(cuentaRepository.registrarLoginExitoso(eq(CUENTA_ID), any())).willReturn(true);

		Cuenta resultado = service.autenticar(EMAIL, PASSWORD_VALIDA);

		assertThat(resultado).isSameAs(cuenta);
		verify(cuentaRepository).registrarLoginExitoso(eq(CUENTA_ID), any());
		// Por la entidad, el UPDATE movia la version y dos logins simultaneos chocaban.
		verify(cuentaRepository, never()).save(any());
		assertThat(auditoria()).singleElement()
				.satisfies(entrada -> assertThat(entrada.eventType()).isEqualTo("LOGIN_EXITOSO"));
	}

	@Test
	@DisplayName("si la cuenta se bloqueo durante el login, el mismo 401 de siempre")
	void si_la_cuenta_dejo_de_habilitar_durante_el_login_se_rechaza() {
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(cuenta));
		given(passwordHasher.matches(PASSWORD_VALIDA, cuenta.getPasswordHash())).willReturn(true);
		// El UPDATE condicionado no encontro la cuenta habilitada: un bloqueo entro despues de
		// la lectura.
		given(cuentaRepository.registrarLoginExitoso(eq(CUENTA_ID), any())).willReturn(false);

		assertThatThrownBy(() -> service.autenticar(EMAIL, PASSWORD_VALIDA))
				.isInstanceOf(InvalidCredentialsException.class);

		assertThat(auditoria()).singleElement()
				.satisfies(entrada -> assertThat(entrada.eventType())
						.isEqualTo("LOGIN_RECHAZADO_ESTADO"));
	}

	@Test
	@DisplayName("cuando el email no existe se quema el mismo trabajo que en una verificacion")
	void cuando_el_email_no_existe_se_quema_el_mismo_trabajo() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.autenticar(EMAIL, PASSWORD_VALIDA))
				.isInstanceOf(InvalidCredentialsException.class);

		// Sin esta llamada la rama "no existe" responde en microsegundos y el reloj dice lo que
		// el cuerpo de la respuesta se cuida de no decir.
		verify(passwordHasher).dummyVerify();
		assertThat(auditoria()).singleElement().satisfies(entrada -> {
			assertThat(entrada.eventType()).isEqualTo("LOGIN_FALLIDO");
			assertThat(entrada.details()).containsEntry("motivo", "CUENTA_INEXISTENTE");
			assertThat(entrada.entityId())
					.as("no se registra ninguna cuenta: no hubo cuenta")
					.isNull();
		});
	}

	@Test
	@DisplayName("un email vacio toma el mismo camino que uno inexistente")
	void un_email_vacio_toma_el_mismo_camino() {
		assertThatThrownBy(() -> service.autenticar("  ", PASSWORD_VALIDA))
				.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> service.autenticar(null, PASSWORD_VALIDA))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(cuentaRepository, never()).findByEmailNormalizado(anyString());
	}

	@Test
	@DisplayName("una contrasena equivocada suma un fallo y deja el rastro")
	void una_contrasena_equivocada_suma_un_fallo() {
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(cuenta));
		given(passwordHasher.matches(any(), any())).willReturn(false);
		given(cuentaRepository.registrarLoginFallido(eq(CUENTA_ID), any())).willReturn(1);

		assertThatThrownBy(() -> service.autenticar(EMAIL, "la-equivocada"))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(cuentaRepository).registrarLoginFallido(eq(CUENTA_ID), any());
		verify(cuentaRepository, never()).save(any());
		assertThat(auditoria()).singleElement().satisfies(entrada -> {
			assertThat(entrada.eventType()).isEqualTo("LOGIN_FALLIDO");
			assertThat(entrada.details()).containsEntry("motivo", "CREDENCIALES");
		});
	}

	@Test
	@DisplayName("a partir del umbral se emite el evento de actividad sospechosa")
	void a_partir_del_umbral_se_emite_actividad_sospechosa() {
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(cuenta));
		given(passwordHasher.matches(any(), any())).willReturn(false);
		// El total lo devuelve el incremento atomico de la base, no la entidad.
		given(cuentaRepository.registrarLoginFallido(eq(CUENTA_ID), any()))
				.willReturn(AuthenticationService.UMBRAL_ACTIVIDAD_SOSPECHOSA);

		assertThatThrownBy(() -> service.autenticar(EMAIL, "la-equivocada"))
				.isInstanceOf(InvalidCredentialsException.class);

		// La cuenta NO se bloquea: bloquear por intentos habilita un DoS dirigido. Lo que se
		// hace es avisarle a un administrador para que decida.
		assertThat(cuenta.getEstado().permiteLogin()).isTrue();
		assertThat(auditoria()).extracting(AuditEntry::eventType)
				.containsExactly("LOGIN_FALLIDO", "ACTIVIDAD_SOSPECHOSA");
	}

	@Test
	@DisplayName("contrasena correcta sobre cuenta bloqueada: mismo 401, evento distinto")
	void contrasena_correcta_sobre_cuenta_bloqueada() {
		Cuenta bloqueada = cuentaBloqueada();
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(bloqueada));
		given(passwordHasher.matches(PASSWORD_VALIDA, bloqueada.getPasswordHash()))
				.willReturn(true);

		// Responder "403 account-disabled" aca convertiria el login en un oraculo de
		// credenciales validas: quien tenga una lista filtrada de otro sitio la prueba y
		// descubre cuales sirven, sin necesidad de poder entrar.
		assertThatThrownBy(() -> service.autenticar(EMAIL, PASSWORD_VALIDA))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(cuentaRepository, never()).save(any());
		assertThat(auditoria()).singleElement().satisfies(entrada -> {
			assertThat(entrada.eventType()).isEqualTo("LOGIN_RECHAZADO_ESTADO");
			assertThat(entrada.details()).containsEntry("estado", "BLOQUEADA");
		});
	}

	@Test
	@DisplayName("una cuenta pendiente de activacion tampoco entra")
	void una_cuenta_pendiente_no_entra() {
		Cuenta pendiente = cuentaPendiente();
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.of(pendiente));
		given(passwordHasher.matches(any(), any())).willReturn(true);

		assertThatThrownBy(() -> service.autenticar(EMAIL, PASSWORD_VALIDA))
				.isInstanceOf(InvalidCredentialsException.class);
	}
}
