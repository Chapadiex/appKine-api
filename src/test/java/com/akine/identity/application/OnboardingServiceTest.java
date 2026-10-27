package com.akine.identity.application;

import com.akine.identity.domain.AceptacionTerminos;
import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.TerminosSettings;
import com.akine.identity.domain.exception.TerminosNoAceptadosException;
import com.akine.identity.domain.port.AceptacionTerminosRepositoryPort;
import com.akine.identity.domain.OnboardingRegistro;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.identity.domain.port.OnboardingRegistroRepositoryPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import com.akine.organization.spi.InitialOrganizationProvisioning;
import com.akine.organization.spi.ProvisioningResult;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.EMAIL;
import static com.akine.identity.IdentityFixtures.ORG_ID;
import static com.akine.identity.IdentityFixtures.PASSWORD_VALIDA;
import static com.akine.identity.IdentityFixtures.cuentaPendiente;
import static com.akine.identity.IdentityFixtures.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Alta self-service (RF-M02-001, ADR-0008).
 *
 * <p>Lo que estos tests protegen no es el camino feliz sino la uniformidad: el email que ya
 * existe tiene que producir un desenlace INDISTINGUIBLE del alta exitosa, y el hasheo tiene que
 * ocurrir siempre —incluso cuando no se crea nada— porque si no, el tiempo de respuesta delata
 * lo que el cuerpo se cuida de no decir.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

	private static final String CLAVE = "clave-idempotencia-1";
	private static final String TOKEN_PLANO = "token-de-activacion";
	private static final String ENLACE = "https://app.akine.test/activar?token=x";

	@Mock
	private CuentaRepositoryPort cuentaRepository;

	@Mock
	private OnboardingRegistroRepositoryPort onboardingRepository;

	@Mock
	private TokenVerificacionRepositoryPort tokenRepository;

	@Mock
	private InitialOrganizationProvisioning organizationProvisioning;

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

	@Mock
	private AceptacionTerminosRepositoryPort aceptacionRepository;

	/** Sin version vigente por defecto: el alta de antes de DP-45. */
	private TerminosSettings terminos = TerminosSettings.sinTerminos();

	/**
	 * Ejecuta el callback en el hilo, sin transaccion real.
	 *
	 * <p>Lo que se testea es el contenido de la transaccion, no el gestor de transacciones de
	 * Spring. Que el hasheo quede FUERA se verifica por otro lado: por el orden de las llamadas.
	 */
	private TransactionTemplate transactionTemplate() {
		return new TransactionTemplate() {
			@Override
			public <T> T execute(TransactionCallback<T> action) {
				return action.doInTransaction(null);
			}
		};
	}

	private OnboardingService service() {
		return new OnboardingService(transactionTemplate(), cuentaRepository,
				onboardingRepository, tokenRepository, organizationProvisioning,
				notificationOutbox, linkBuilder, new PasswordPolicy(), passwordHasher,
				tokenGenerator, auditTrail, new TerminosService(terminos, aceptacionRepository));
	}

	private static RegistroCuentaCommand comando() {
		return new RegistroCuentaCommand(CLAVE, "hash-del-request", EMAIL, PASSWORD_VALIDA,
				"Ana", "Gomez", "Centro Kine Norte", "centro-kine-norte", "Sede Centro",
				"BASICO");
	}

	private void sinReintentoPrevio() {
		given(onboardingRepository.findByClaveIdempotencia(CLAVE)).willReturn(Optional.empty());
	}

	private NotificationOutboxPort.Notificacion capturarNotificacion() {
		ArgumentCaptor<NotificationOutboxPort.Notificacion> captor =
				ArgumentCaptor.forClass(NotificationOutboxPort.Notificacion.class);
		verify(notificationOutbox).encolar(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("el alta crea cuenta y tenant, y encola la activacion con el enlace armado")
	void el_alta_crea_cuenta_y_tenant() {
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.empty());
		given(cuentaRepository.saveAndFlush(any())).willReturn(cuentaPendiente());
		given(organizationProvisioning.provision(any()))
				.willReturn(new ProvisioningResult(ORG_ID, 20L, 60L, true));
		given(tokenGenerator.nuevoToken()).willReturn(TOKEN_PLANO);
		given(tokenRepository.save(any()))
				.willReturn(token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO));
		given(linkBuilder.enlaceDe(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO))
				.willReturn(ENLACE);

		ResultadoRegistro resultado = service().registrar(comando());

		assertThat(resultado.cuentaCreada()).isTrue();
		assertThat(resultado.cuentaId()).isEqualTo(CUENTA_ID);
		assertThat(resultado.organizationId()).isEqualTo(ORG_ID);
		assertThat(resultado.consultorioId()).isEqualTo(20L);

		NotificationOutboxPort.Notificacion notificacion = capturarNotificacion();
		assertThat(notificacion.tipo())
				.isEqualTo(NotificationOutboxPort.TipoNotificacion.ACTIVACION_CUENTA);
		assertThat(notificacion.enlaceSeguro()).isEqualTo(ENLACE);
		assertThat(notificacion.datosPlantilla())
				.containsEntry("nombre", "Ana")
				.doesNotContainValue(TOKEN_PLANO);

		verify(onboardingRepository).saveAndFlush(any(OnboardingRegistro.class));
		verify(auditTrail).record(any());
	}

	@Test
	@DisplayName("un email ya registrado no crea nada y avisa por correo, sin decirlo al cliente")
	void un_email_ya_registrado_no_crea_nada() {
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL))
				.willReturn(Optional.of(cuentaPendiente()));

		ResultadoRegistro resultado = service().registrar(comando());

		assertThat(resultado.cuentaCreada()).isFalse();
		assertThat(resultado.cuentaId()).isNull();

		// La cuenta existente no se toca, ni siquiera un contador.
		verify(cuentaRepository, never()).saveAndFlush(any());
		verify(cuentaRepository, never()).save(any());
		// Del spi de organization se usa SOLO la validacion, que corre antes de mirar el email
		// y es lo que hace que la respuesta sea la misma exista o no la cuenta (ADR-0018). Lo
		// que no puede ocurrir es que se provisione un tenant.
		verify(organizationProvisioning).validateTenantRequest(any(), any());
		verify(organizationProvisioning, never()).provision(any());
		verifyNoInteractions(tokenGenerator);
		// Y no se audita: auditar aca construiria el indice de direcciones registradas que el
		// 202 uniforme evita, en la tabla que mas gente consulta.
		verifyNoInteractions(auditTrail);

		assertThat(capturarNotificacion().tipo())
				.isEqualTo(NotificationOutboxPort.TipoNotificacion.CUENTA_YA_REGISTRADA);
		assertThat(capturarNotificacion().enlaceSeguro()).isNull();
	}

	@Test
	@DisplayName("la contrasena se hashea SIEMPRE, incluso cuando no se crea nada")
	void la_contrasena_se_hashea_siempre() {
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL))
				.willReturn(Optional.of(cuentaPendiente()));

		service().registrar(comando());

		// Saltarse el hasheo en el camino "ya existe" haria que ese camino responda
		// notoriamente mas rapido, y la diferencia de tiempo seria un oraculo de direcciones.
		verify(passwordHasher).hash(PASSWORD_VALIDA);
	}

	@Test
	@DisplayName("dos altas simultaneas del mismo email: el perdedor toma el camino duplicado")
	void dos_altas_simultaneas_del_mismo_email() {
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.empty());
		given(cuentaRepository.saveAndFlush(any()))
				.willThrow(new DataIntegrityViolationException("uk_cuenta_email_normalizado"));

		// Quien decide es el unique, no un SELECT previo: un chequeo sin la restriccion detras
		// es la misma carrera con otro nombre.
		ResultadoRegistro resultado = service().registrar(comando());

		assertThat(resultado.cuentaCreada()).isFalse();
		assertThat(capturarNotificacion().tipo())
				.isEqualTo(NotificationOutboxPort.TipoNotificacion.CUENTA_YA_REGISTRADA);
	}

	@Test
	@DisplayName("reintentar con la misma clave devuelve el mismo desenlace sin repetir efectos")
	void reintentar_con_la_misma_clave_no_repite_efectos() {
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(onboardingRepository.findByClaveIdempotencia(CLAVE))
				.willReturn(Optional.of(OnboardingRegistro.completado(
						CLAVE, EMAIL, CUENTA_ID, ORG_ID, 20L, "hash-del-request", Instant.now())));

		ResultadoRegistro resultado = service().registrar(comando());

		assertThat(resultado.cuentaCreada())
				.as("el reintento no CREA: devuelve lo que ya se creo")
				.isFalse();
		assertThat(resultado.cuentaId()).isEqualTo(CUENTA_ID);
		assertThat(resultado.organizationId()).isEqualTo(ORG_ID);
		verifyNoInteractions(notificationOutbox, organizationProvisioning, auditTrail);
	}

	@Test
	@DisplayName("reintentar un duplicado no manda otro correo: no es un amplificador de spam")
	void reintentar_un_duplicado_no_manda_otro_correo() {
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(onboardingRepository.findByClaveIdempotencia(CLAVE))
				.willReturn(Optional.of(OnboardingRegistro.duplicado(CLAVE, EMAIL, "hash-del-request", Instant.now())));

		ResultadoRegistro resultado = service().registrar(comando());

		assertThat(resultado.cuentaCreada()).isFalse();
		assertThat(resultado.cuentaId()).isNull();
		verifyNoInteractions(notificationOutbox);
	}

	@Test
	@DisplayName("7b. la misma clave con otro contenido es conflicto, exista o no la cuenta")
	void la_misma_clave_con_otro_contenido_es_conflicto() {
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(onboardingRepository.findByClaveIdempotencia(CLAVE))
				.willReturn(Optional.of(OnboardingRegistro.completado(
						CLAVE, EMAIL, CUENTA_ID, ORG_ID, 20L, "otro-hash", Instant.now())))
				.willReturn(Optional.of(OnboardingRegistro.duplicado(
						CLAVE, EMAIL, "otro-hash", Instant.now())));

		// COMPLETADO y DUPLICADO dan lo mismo: si no, el 409 diria si el email tenia cuenta.
		assertThatThrownBy(() -> service().registrar(comando()))
				.isInstanceOf(IdempotencyKeyConflictException.class);
		assertThatThrownBy(() -> service().registrar(comando()))
				.isInstanceOf(IdempotencyKeyConflictException.class);
		verifyNoInteractions(notificationOutbox, organizationProvisioning, auditTrail);
	}

	@Test
	@DisplayName("7b. un registro sin hash (anterior a V87) se acepta como replay")
	void un_registro_sin_hash_se_acepta_como_replay() {
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(onboardingRepository.findByClaveIdempotencia(CLAVE))
				.willReturn(Optional.of(OnboardingRegistro.completado(
						CLAVE, EMAIL, CUENTA_ID, ORG_ID, 20L, null, Instant.now())));

		ResultadoRegistro resultado = service().registrar(comando());

		assertThat(resultado.cuentaCreada()).isFalse();
		assertThat(resultado.cuentaId()).isEqualTo(CUENTA_ID);
	}

	@Test
	@DisplayName("7b. si otro hilo gano la clave con otro contenido, el perdedor recibe conflicto")
	void si_otro_hilo_gano_la_clave_con_otro_contenido_es_conflicto() {
		given(onboardingRepository.findByClaveIdempotencia(CLAVE))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(OnboardingRegistro.duplicado(
						CLAVE, EMAIL, "hash-del-ganador", Instant.now())));
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL))
				.willReturn(Optional.of(cuentaPendiente()));
		given(onboardingRepository.saveAndFlush(any()))
				.willThrow(new DataIntegrityViolationException("uk_onboarding_registro_clave"));

		assertThatThrownBy(() -> service().registrar(comando()))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	@Test
	@DisplayName("la huella del alta ignora la contrasena y normaliza mayusculas y espacios")
	void la_huella_ignora_la_contrasena_y_normaliza() {
		String base = HuellaDeRegistro.de("Ana@Ejemplo.test", "Ana", "Gomez", "Centro",
				null, null, "BASICO");

		assertThat(base).hasSize(64);
		assertThat(HuellaDeRegistro.de(" ana@ejemplo.test ", "ANA", "gomez", "centro",
				"", "  ", "basico")).isEqualTo(base);
		assertThat(HuellaDeRegistro.de("otra@ejemplo.test", "Ana", "Gomez", "Centro",
				null, null, "BASICO")).isNotEqualTo(base);
	}

	@Test
	@DisplayName("si otro hilo gano la clave de idempotencia, no se relanza")
	void si_otro_hilo_gano_la_clave_no_se_relanza() {
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL))
				.willReturn(Optional.of(cuentaPendiente()));
		given(onboardingRepository.saveAndFlush(any()))
				.willThrow(new DataIntegrityViolationException("uk_onboarding_registro_clave"));

		// El ganador ya produjo el efecto correcto y el desenlace es indistinguible: lo unico
		// que hay que garantizar es no duplicar.
		assertThat(service().registrar(comando()).cuentaCreada()).isFalse();
	}

	@Test
	@DisplayName("una contrasena que no cumple la politica corta antes de tocar la base")
	void una_contrasena_invalida_corta_antes_de_tocar_la_base() {
		RegistroCuentaCommand invalido = new RegistroCuentaCommand(CLAVE, "hash", EMAIL, "corta",
				"Ana", "Gomez", "Centro", "centro", "Sede", "BASICO");

		assertThatThrownBy(() -> service().registrar(invalido))
				.isInstanceOf(com.akine.identity.domain.exception.PasswordPolicyViolationException.class);

		verifyNoInteractions(cuentaRepository, onboardingRepository, notificationOutbox);
	}

	@Test
	@DisplayName("el comando exige clave, email y nombre de organizacion")
	void el_comando_exige_lo_minimo() {
		assertThatThrownBy(() -> new RegistroCuentaCommand(null, "h", EMAIL, PASSWORD_VALIDA,
				"Ana", "Gomez", "Centro", "centro", "Sede", "BASICO"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RegistroCuentaCommand(CLAVE, "h", "  ", PASSWORD_VALIDA,
				"Ana", "Gomez", "Centro", "centro", "Sede", "BASICO"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RegistroCuentaCommand(CLAVE, "h", EMAIL, PASSWORD_VALIDA,
				"Ana", "Gomez", null, "centro", "Sede", "BASICO"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// =================================================================================
	// DP-45 — aceptacion de terminos
	// =================================================================================

	private static RegistroCuentaCommand comandoConTerminos(Boolean acepta, String version) {
		return new RegistroCuentaCommand(CLAVE, "hash-del-request", EMAIL, PASSWORD_VALIDA,
				"Ana", "Gomez", "Centro Kine Norte", "centro-kine-norte", "Sede Centro",
				"BASICO", acepta, version);
	}

	@Test
	@DisplayName("DP-45: con version vigente, el alta registra la aceptacion con la cuenta")
	void con_version_vigente_registra_la_aceptacion() {
		terminos = new TerminosSettings("2026-10", null);
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL)).willReturn(Optional.empty());
		given(cuentaRepository.saveAndFlush(any())).willReturn(cuentaPendiente());
		given(organizationProvisioning.provision(any()))
				.willReturn(new ProvisioningResult(ORG_ID, 20L, 60L, true));
		given(tokenGenerator.nuevoToken()).willReturn(TOKEN_PLANO);
		given(tokenRepository.save(any()))
				.willReturn(token(TipoTokenVerificacion.ACTIVACION, TOKEN_PLANO));

		service().registrar(comandoConTerminos(true, " 2026-10 "));

		ArgumentCaptor<AceptacionTerminos> captor = ArgumentCaptor.forClass(AceptacionTerminos.class);
		verify(aceptacionRepository).save(captor.capture());
		assertThat(captor.getValue().getCuentaId()).isEqualTo(CUENTA_ID);
		assertThat(captor.getValue().getVersion()).isEqualTo("2026-10");
		assertThat(captor.getValue().getOrigen()).isEqualTo(AceptacionTerminos.Origen.REGISTRO);
		assertThat(captor.getValue().getAceptadoEn()).isNotNull();
	}

	@Test
	@DisplayName("DP-45: sin aceptar, 400 por aceptaTerminos ANTES de mirar el email")
	void sin_aceptar_rechaza_antes_de_mirar_el_email() {
		terminos = new TerminosSettings("2026-10", null);

		assertThatThrownBy(() -> service().registrar(comandoConTerminos(null, "2026-10")))
				.isInstanceOf(TerminosNoAceptadosException.class)
				.extracting(e -> ((TerminosNoAceptadosException) e).getCampo())
				.isEqualTo("aceptaTerminos");

		// Nada del pedido llego a la base: el rechazo es identico exista o no la cuenta.
		verifyNoInteractions(cuentaRepository, onboardingRepository, aceptacionRepository);
	}

	@Test
	@DisplayName("DP-45: una version que no es la vigente se rechaza por versionTerminos")
	void version_vieja_se_rechaza() {
		terminos = new TerminosSettings("2026-10", null);

		assertThatThrownBy(() -> service().registrar(comandoConTerminos(true, "2026-01")))
				.isInstanceOf(TerminosNoAceptadosException.class)
				.extracting(e -> ((TerminosNoAceptadosException) e).getCampo())
				.isEqualTo("versionTerminos");
		verifyNoInteractions(cuentaRepository);
	}

	@Test
	@DisplayName("DP-45: con email ya registrado no se registra ninguna aceptacion")
	void email_tomado_no_registra_aceptacion() {
		terminos = new TerminosSettings("2026-10", null);
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL))
				.willReturn(Optional.of(cuentaPendiente()));

		service().registrar(comandoConTerminos(true, "2026-10"));

		verifyNoInteractions(aceptacionRepository);
	}

	@Test
	@DisplayName("DP-45: sin version vigente, los campos se ignoran y no se registra nada")
	void sin_version_vigente_no_exige_ni_registra() {
		sinReintentoPrevio();
		given(passwordHasher.hash(PASSWORD_VALIDA)).willReturn("{h}x");
		given(cuentaRepository.findByEmailNormalizado(EMAIL))
				.willReturn(Optional.of(cuentaPendiente()));

		service().registrar(comandoConTerminos(false, "cualquiera"));

		verifyNoInteractions(aceptacionRepository);
	}
}
