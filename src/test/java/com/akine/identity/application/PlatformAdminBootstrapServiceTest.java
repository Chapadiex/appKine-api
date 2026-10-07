package com.akine.identity.application;

import com.akine.identity.application.PlatformAdminBootstrapService.ResultadoBootstrap;
import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import com.akine.organization.spi.PlatformAdminRoster;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.akine.identity.IdentityFixtures.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlatformAdminBootstrapServiceTest {

	private static final Instant AHORA = Instant.parse("2026-10-07T12:00:00Z");
	private static final long SEMBRADA_ID = 1L;
	private static final long OTRA_ID = 2L;
	private static final String EMAIL_SEMBRADO = "plataforma@akine.app";
	private static final String EMAIL_OPERADOR = "Operaciones@Ejemplo.test";

	@Mock private CuentaRepositoryPort cuentaRepository;
	@Mock private TokenVerificacionRepositoryPort tokenRepository;
	@Mock private PlatformAdminRoster roster;
	@Mock private NotificationOutboxPort outbox;
	@Mock private VerificationLinkBuilder linkBuilder;
	@Mock private TokenGenerator tokenGenerator;
	@Mock private PasswordHasher passwordHasher;
	@Mock private AuditTrail auditTrail;

	private PlatformAdminBootstrapService service;

	@BeforeEach
	void armar() {
		AccountActivationService activacion = new AccountActivationService(cuentaRepository,
				tokenRepository, outbox, linkBuilder, new PasswordPolicy(), passwordHasher,
				tokenGenerator, auditTrail);
		service = new PlatformAdminBootstrapService(cuentaRepository, tokenRepository, roster,
				activacion, auditTrail, () -> AHORA);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "   ")
	@DisplayName("sin la variable no toca nada")
	void sin_variable(String email) {
		assertThat(service.ejecutar(email)).isEqualTo(ResultadoBootstrap.SIN_VARIABLE);
		verifyNoInteractions(roster, cuentaRepository, outbox, auditTrail);
	}

	@ParameterizedTest
	@ValueSource(strings = {"sin-arroba", "dos@@ejemplo.test", "con espacio@ejemplo.test", "a@sinpunto"})
	@DisplayName("una variable sin forma de email no toca nada")
	void email_invalido(String email) {
		assertThat(service.ejecutar(email)).isEqualTo(ResultadoBootstrap.EMAIL_INVALIDO);
		verifyNoInteractions(roster, cuentaRepository, outbox, auditTrail);
	}

	@Test
	@DisplayName("un email de mas de 254 caracteres es invalido")
	void email_demasiado_largo() {
		String largo = "a".repeat(250) + "@ejemplo.test";
		assertThat(service.ejecutar(largo)).isEqualTo(ResultadoBootstrap.EMAIL_INVALIDO);
	}

	@Test
	@DisplayName("primer arranque: re-apunta la cuenta sembrada, la deja pendiente, encola y audita")
	void primer_arranque_emite_el_enlace() {
		Cuenta sembrada = sembrada();
		conRol(sembrada);
		given(cuentaRepository.findByEmailNormalizado("operaciones@ejemplo.test"))
				.willReturn(Optional.empty());
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				SEMBRADA_ID, TipoTokenVerificacion.ACTIVACION)).willReturn(List.of());
		given(tokenGenerator.nuevoToken()).willReturn("token-plano");
		given(tokenRepository.save(any())).willAnswer(inv -> conId(inv.getArgument(0), 77L));
		given(linkBuilder.enlaceDe(TipoTokenVerificacion.ACTIVACION, "token-plano"))
				.willReturn("https://app.ejemplo.test/activar?token=token-plano");

		assertThat(service.ejecutar("  " + EMAIL_OPERADOR + " "))
				.isEqualTo(ResultadoBootstrap.ENLACE_EMITIDO);

		assertThat(sembrada.getEmail()).isEqualTo(EMAIL_OPERADOR);
		assertThat(sembrada.getEmailNormalizado()).isEqualTo("operaciones@ejemplo.test");
		assertThat(sembrada.getEstado()).isEqualTo(EstadoCuenta.PENDIENTE_ACTIVACION);
		verify(cuentaRepository).saveAndFlush(sembrada);

		ArgumentCaptor<NotificationOutboxPort.Notificacion> correo =
				ArgumentCaptor.forClass(NotificationOutboxPort.Notificacion.class);
		verify(outbox).encolar(correo.capture());
		assertThat(correo.getValue().organizationId()).isNull();
		assertThat(correo.getValue().tipo())
				.isEqualTo(NotificationOutboxPort.TipoNotificacion.ACTIVACION_CUENTA);
		assertThat(correo.getValue().destinatario()).isEqualTo(EMAIL_OPERADOR);
		assertThat(correo.getValue().claveIdempotente()).isEqualTo("activacion:77");
		assertThat(correo.getValue().datosPlantilla()).doesNotContainValue("token-plano");

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		AuditEntry evento = auditoria.getValue();
		assertThat(evento.eventType()).isEqualTo("PLATFORM_ADMIN_BOOTSTRAP");
		assertThat(evento.organizationId()).isNull();
		assertThat(evento.actorAccountId()).isNull();
		assertThat(evento.entityId()).isEqualTo(SEMBRADA_ID);
		assertThat(evento.previousState()).isEqualTo("ACTIVA");
		assertThat(evento.newState()).isEqualTo("PENDIENTE_ACTIVACION");
		assertThat(evento.details())
				.containsEntry("emailAnterior", EMAIL_SEMBRADO)
				.containsEntry("emailNuevo", EMAIL_OPERADOR)
				.containsEntry("tokenVerificacionId", "77")
				.doesNotContainValue("token-plano");
	}

	@Test
	@DisplayName("segundo arranque con el mismo email y enlace vigente: no reenvia")
	void segundo_arranque_con_enlace_vigente() {
		Cuenta pendiente = sembrada();
		pendiente.prepararBootstrapDePlataforma(EMAIL_OPERADOR);
		conRol(pendiente);
		given(cuentaRepository.findByEmailNormalizado("operaciones@ejemplo.test"))
				.willReturn(Optional.of(pendiente));
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				SEMBRADA_ID, TipoTokenVerificacion.ACTIVACION))
				.willReturn(List.of(tokenEmitidoEn(AHORA.minus(1, ChronoUnit.HOURS))));

		assertThat(service.ejecutar(EMAIL_OPERADOR)).isEqualTo(ResultadoBootstrap.ENLACE_VIGENTE);

		verify(cuentaRepository, never()).saveAndFlush(any());
		verifyNoInteractions(outbox, auditTrail);
	}

	@Test
	@DisplayName("mismo email pero el enlace vencio: emite uno nuevo")
	void enlace_vencido_se_reemite() {
		Cuenta pendiente = sembrada();
		pendiente.prepararBootstrapDePlataforma(EMAIL_OPERADOR);
		conRol(pendiente);
		given(cuentaRepository.findByEmailNormalizado("operaciones@ejemplo.test"))
				.willReturn(Optional.of(pendiente));
		TokenVerificacion vencido = tokenEmitidoEn(AHORA.minus(30, ChronoUnit.DAYS));
		given(tokenRepository.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
				SEMBRADA_ID, TipoTokenVerificacion.ACTIVACION)).willReturn(List.of(vencido));
		given(tokenGenerator.nuevoToken()).willReturn("otro-token");
		given(tokenRepository.save(any())).willAnswer(inv -> conId(inv.getArgument(0), 78L));

		assertThat(service.ejecutar(EMAIL_OPERADOR)).isEqualTo(ResultadoBootstrap.ENLACE_EMITIDO);

		assertThat(vencido.getInvalidadoEn()).isEqualTo(AHORA);
		verify(outbox).encolar(any());
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().previousState()).isEqualTo("PENDIENTE_ACTIVACION");
	}

	@Test
	@DisplayName("la variable cambio mientras la cuenta seguia pendiente: re-apunta y emite")
	void variable_corregida_re_apunta() {
		Cuenta pendiente = sembrada();
		pendiente.prepararBootstrapDePlataforma("tipeo@mal.test");
		conRol(pendiente);
		given(cuentaRepository.findByEmailNormalizado("operaciones@ejemplo.test"))
				.willReturn(Optional.empty());
		given(tokenGenerator.nuevoToken()).willReturn("token-nuevo");
		given(tokenRepository.save(any())).willAnswer(inv -> conId(inv.getArgument(0), 79L));

		assertThat(service.ejecutar(EMAIL_OPERADOR)).isEqualTo(ResultadoBootstrap.ENLACE_EMITIDO);

		assertThat(pendiente.getEmailNormalizado()).isEqualTo("operaciones@ejemplo.test");
		verify(outbox).encolar(any());
	}

	@Test
	@DisplayName("si un administrador de plataforma ya tiene credencial, no hace nada")
	void ya_hay_admin_con_credencial() {
		Cuenta conCredencial = conId(new Cuenta("admin@ejemplo.test", "Ad", "Min", "{hash}x"), OTRA_ID);
		Cuenta sembrada = sembrada();
		given(roster.cuentasConRolDePlataforma(AHORA)).willReturn(Set.of(SEMBRADA_ID, OTRA_ID));
		given(cuentaRepository.findById(SEMBRADA_ID)).willReturn(Optional.of(sembrada));
		given(cuentaRepository.findById(OTRA_ID)).willReturn(Optional.of(conCredencial));

		assertThat(service.ejecutar(EMAIL_OPERADOR))
				.isEqualTo(ResultadoBootstrap.YA_HAY_ADMIN_CON_CREDENCIAL);

		assertThat(sembrada.getEmail()).isEqualTo(EMAIL_SEMBRADO);
		verify(cuentaRepository, never()).saveAndFlush(any());
		verifyNoInteractions(outbox, auditTrail);
	}

	@Test
	@DisplayName("una cuenta de plataforma bloqueada con credencial cuenta como admin con credencial")
	void admin_bloqueado_con_credencial_tambien_cuenta() {
		Cuenta bloqueada = conId(new Cuenta("admin@ejemplo.test", "Ad", "Min", "{hash}x"), OTRA_ID);
		bloqueada.transicionarA(EstadoCuenta.ACTIVA, null, AHORA);
		bloqueada.transicionarA(EstadoCuenta.BLOQUEADA, "prueba", AHORA);
		given(roster.cuentasConRolDePlataforma(AHORA)).willReturn(Set.of(OTRA_ID));
		given(cuentaRepository.findById(OTRA_ID)).willReturn(Optional.of(bloqueada));

		assertThat(service.ejecutar(EMAIL_OPERADOR))
				.isEqualTo(ResultadoBootstrap.YA_HAY_ADMIN_CON_CREDENCIAL);
	}

	@Test
	@DisplayName("sin ninguna cuenta de plataforma que admita el bootstrap, no hace nada")
	void sin_cuenta_sembrada() {
		Cuenta desactivada = sembrada();
		desactivada.transicionarA(EstadoCuenta.DESACTIVADA, "baja", AHORA);
		given(roster.cuentasConRolDePlataforma(AHORA)).willReturn(Set.of(SEMBRADA_ID, 99L));
		given(cuentaRepository.findById(SEMBRADA_ID)).willReturn(Optional.of(desactivada));
		given(cuentaRepository.findById(99L)).willReturn(Optional.empty());

		assertThat(service.ejecutar(EMAIL_OPERADOR)).isEqualTo(ResultadoBootstrap.SIN_CUENTA_SEMBRADA);
		verifyNoInteractions(outbox, auditTrail);
	}

	@Test
	@DisplayName("dos cuentas de plataforma sin credencial: no elige al azar")
	void candidatas_ambiguas() {
		Cuenta otra = conId(new Cuenta("otra@ejemplo.test", "O", "Tra", null), OTRA_ID);
		given(roster.cuentasConRolDePlataforma(AHORA)).willReturn(Set.of(SEMBRADA_ID, OTRA_ID));
		given(cuentaRepository.findById(SEMBRADA_ID)).willReturn(Optional.of(sembrada()));
		given(cuentaRepository.findById(OTRA_ID)).willReturn(Optional.of(otra));

		assertThat(service.ejecutar(EMAIL_OPERADOR)).isEqualTo(ResultadoBootstrap.CANDIDATAS_AMBIGUAS);
		verifyNoInteractions(outbox, auditTrail);
	}

	@Test
	@DisplayName("si el email ya es de otra cuenta no pisa nada ni otorga el rol")
	void email_de_otra_cuenta() {
		Cuenta sembrada = sembrada();
		conRol(sembrada);
		Cuenta ajena = conId(new Cuenta(EMAIL_OPERADOR, "Aje", "Na", "{hash}y"), OTRA_ID);
		given(cuentaRepository.findByEmailNormalizado("operaciones@ejemplo.test"))
				.willReturn(Optional.of(ajena));

		assertThat(service.ejecutar(EMAIL_OPERADOR)).isEqualTo(ResultadoBootstrap.EMAIL_DE_OTRA_CUENTA);

		assertThat(sembrada.getEmail()).isEqualTo(EMAIL_SEMBRADO);
		assertThat(sembrada.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
		verify(cuentaRepository, never()).saveAndFlush(any());
		verifyNoInteractions(outbox, auditTrail);
	}

	// =================================================================================

	/** La cuenta como la deja V15: ACTIVA y sin credencial. */
	private static Cuenta sembrada() {
		Cuenta cuenta = conId(new Cuenta(EMAIL_SEMBRADO, "Administracion", "de Plataforma", null),
				SEMBRADA_ID);
		cuenta.transicionarA(EstadoCuenta.ACTIVA, null, AHORA);
		return cuenta;
	}

	private void conRol(Cuenta cuenta) {
		given(roster.cuentasConRolDePlataforma(AHORA)).willReturn(Set.of(cuenta.getId()));
		given(cuentaRepository.findById(cuenta.getId())).willReturn(Optional.of(cuenta));
		lenient().when(cuentaRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	private static TokenVerificacion tokenEmitidoEn(Instant emitido) {
		return conId(new TokenVerificacion(SEMBRADA_ID, TipoTokenVerificacion.ACTIVACION,
				TokenDigest.of("previo"), emitido), 50L);
	}
}
