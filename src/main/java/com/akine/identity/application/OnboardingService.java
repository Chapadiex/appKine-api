package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
import com.akine.identity.domain.OnboardingRegistro;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.identity.domain.port.OnboardingRegistroRepositoryPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import com.akine.organization.spi.InitialOrganizationCommand;
import com.akine.organization.spi.InitialOrganizationProvisioning;
import com.akine.organization.spi.ProvisioningResult;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Alta self-service: Cuenta + Organizacion + Consultorio + Membership en UNA transaccion
 * (ADR-0008, RF-M02-001).
 *
 * <h2>Por que el endpoint responde lo mismo exista o no la cuenta</h2>
 *
 * <p>Lo natural seria responder "ese email ya esta registrado". Esta prohibido. El sistema ya
 * decidio que el login no revela si una cuenta existe —ni siquiera con la contrasena
 * correcta— y dejar abierta la puerta del registro haria inutil esa decision: el registro es
 * el endpoint mas facil de automatizar de todos, no exige conocer ninguna credencial, y
 * probar diez mil direcciones contra el da exactamente el mismo listado que se queria evitar.
 * Sobre un sistema que guarda historia clinica, saber que alguien es paciente de un centro ya
 * es informacion sensible.
 *
 * <p>La solucion no es dejar al usuario legitimo a ciegas: si el email ya existe se le envia
 * un correo de "ya tenes cuenta, entra o recupera tu contrasena". Llega a destino igual, por
 * un canal que solo puede leer quien controla esa casilla. Quien sondea no aprende nada.
 *
 * <p>Esa uniformidad tiene una consecuencia que no es obvia y que este servicio hace cumplir:
 * <b>el alta NO devuelve una sesion.</b> Si la devolviera, la respuesta de "creada" y la de
 * "ya existia" serian distinguibles a simple vista y todo lo anterior no serviria de nada. La
 * cuenta nace {@code PENDIENTE_ACTIVACION} y la sesion se emite al confirmar el enlace.
 *
 * <h2>Y por que se hashea la contrasena siempre, incluso cuando no se crea nada</h2>
 *
 * <p>Argon2id con los parametros de OWASP tarda decenas de milisegundos a proposito. Si el
 * camino "ya existe" se saltara el hasheo, respondria notoriamente mas rapido y la diferencia
 * de tiempo seria un oraculo de direcciones registradas, aunque el cuerpo de las dos
 * respuestas fuera identico byte a byte. Por eso el hasheo ocurre SIEMPRE y ANTES de mirar la
 * base — que ademas es donde corresponde para no retener la conexion mientras se quema CPU.
 */
// El nombre del bean es explicito porque organization tiene su propio OnboardingService y el
// nombre por defecto que deriva Spring —"onboardingService"— es el mismo para los dos: el
// escaneo aborta con ConflictingBeanDefinitionException y la aplicacion no arranca. Renombrar
// la clase seria mas limpio, pero el nombre correcto para el alta de identidad es este y el
// choque es de bean, no de concepto.
@Service("identityOnboardingService")
public class OnboardingService {

	private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

	/** Origen del alta, para el evento {@code CUENTA_CREADA}. */
	private static final String ORIGEN_REGISTRO = "REGISTRO";

	private final TransactionTemplate transactionTemplate;
	private final CuentaRepositoryPort cuentaRepository;
	private final OnboardingRegistroRepositoryPort onboardingRepository;
	private final TokenVerificacionRepositoryPort tokenRepository;
	private final InitialOrganizationProvisioning organizationProvisioning;
	private final NotificationOutboxPort notificationOutbox;
	private final VerificationLinkBuilder linkBuilder;
	private final PasswordPolicy passwordPolicy;
	private final PasswordHasher passwordHasher;
	private final TokenGenerator tokenGenerator;
	private final AuditTrail auditTrail;

	public OnboardingService(
			TransactionTemplate transactionTemplate,
			CuentaRepositoryPort cuentaRepository,
			OnboardingRegistroRepositoryPort onboardingRepository,
			TokenVerificacionRepositoryPort tokenRepository,
			InitialOrganizationProvisioning organizationProvisioning,
			NotificationOutboxPort notificationOutbox,
			VerificationLinkBuilder linkBuilder,
			PasswordPolicy passwordPolicy,
			PasswordHasher passwordHasher,
			TokenGenerator tokenGenerator,
			AuditTrail auditTrail) {
		this.transactionTemplate = transactionTemplate;
		this.cuentaRepository = cuentaRepository;
		this.onboardingRepository = onboardingRepository;
		this.tokenRepository = tokenRepository;
		this.organizationProvisioning = organizationProvisioning;
		this.notificationOutbox = notificationOutbox;
		this.linkBuilder = linkBuilder;
		this.passwordPolicy = passwordPolicy;
		this.passwordHasher = passwordHasher;
		this.tokenGenerator = tokenGenerator;
		this.auditTrail = auditTrail;
	}

	/**
	 * Ejecuta el alta.
	 *
	 * <p>Fuera de la transaccion: validar la politica y hashear. Dentro: todo lo demas. El
	 * limite esta ahi porque Argon2id ocupa CPU y memoria durante decenas de milisegundos y
	 * hacerlo con una conexion tomada multiplica las conexiones que hacen falta para el mismo
	 * trafico. Se usa {@link TransactionTemplate} y no {@code @Transactional} justamente por
	 * eso: la anotacion abarcaria el metodo entero.
	 *
	 * @throws com.akine.identity.domain.exception.PasswordPolicyViolationException si la
	 *         contrasena no cumple la politica. Es el UNICO rechazo posible de este endpoint,
	 *         y no revela nada: habla de lo que la persona acaba de tipear
	 */
	public ResultadoRegistro registrar(RegistroCuentaCommand command) {
		passwordPolicy.validar(command.password());
		String passwordHash = passwordHasher.hash(command.password());

		return transactionTemplate.execute(status -> ejecutarAlta(command, passwordHash));
	}

	private ResultadoRegistro ejecutarAlta(RegistroCuentaCommand command, String passwordHash) {
		// 1. Reintento con la misma clave: se devuelve el mismo desenlace sin repetir nada.
		//    Incluye el caso "ya existia": sin esto, cada reintento mandaria otro correo de
		//    "ya tenes cuenta" y el endpoint seria un amplificador de spam gratuito.
		Optional<OnboardingRegistro> previo =
				onboardingRepository.findByClaveIdempotencia(command.claveIdempotencia());
		if (previo.isPresent()) {
			log.info("Alta self-service reintentada con la misma clave: no se repite ningun efecto");
			return replayDe(previo.get());
		}

		Instant ahora = Instant.now();
		String emailNormalizado = EmailNormalizado.of(command.email());

		// 2. El email ya tiene cuenta: no se crea nada y se avisa por correo. La respuesta al
		//    cliente sera identica a la del alta exitosa.
		if (cuentaRepository.findByEmailNormalizado(emailNormalizado).isPresent()) {
			return registrarDuplicado(command, emailNormalizado, ahora);
		}

		return crearCuentaYTenant(command, emailNormalizado, passwordHash, ahora);
	}

	private ResultadoRegistro crearCuentaYTenant(
			RegistroCuentaCommand command,
			String emailNormalizado,
			String passwordHash,
			Instant ahora) {

		Cuenta cuenta;
		try {
			// La cuenta nace PENDIENTE_ACTIVACION: la sesion se emite al confirmar el enlace.
			// El flush hace que la violacion de uk_cuenta_email_normalizado se manifieste aca
			// y no al cerrar la transaccion, cuando ya no habria a quien avisarle.
			cuenta = cuentaRepository.saveAndFlush(new Cuenta(
					command.email(), command.nombre(), command.apellido(), passwordHash));
		} catch (DataIntegrityViolationException colision) {
			// Dos altas simultaneas del mismo email: la de al lado gano entre nuestro SELECT y
			// este INSERT. Quien decide es el unique, no un chequeo previo — un SELECT solo
			// seria la misma carrera con otro nombre. El perdedor toma el camino del duplicado,
			// que es indistinguible desde afuera.
			log.info("Alta self-service concurrente sobre el mismo email: se trata como duplicado");
			return registrarDuplicado(command, emailNormalizado, ahora);
		}

		// El alta del tenant corre DENTRO de esta transaccion (propagacion REQUIRED, fijada en
		// el contrato de organization.spi). Si abriera la suya, un fallo posterior de identity
		// revertiria la cuenta y dejaria una organizacion apuntando a un account_id que no
		// existe: un tenant huerfano que nadie detecta hasta que alguien intenta entrar.
		ProvisioningResult tenant = organizationProvisioning.provision(new InitialOrganizationCommand(
				command.claveIdempotencia(),
				command.requestHash(),
				cuenta.getId(),
				command.organizacionNombre(),
				command.organizacionSlug(),
				command.consultorioNombre(),
				command.planCode()));

		emitirActivacion(cuenta, tenant.organizationId(), ahora);

		registrarIdempotencia(OnboardingRegistro.completado(
				command.claveIdempotencia(), emailNormalizado, cuenta.getId(),
				tenant.organizationId(), tenant.consultorioId(), ahora));

		Map<String, String> details = new LinkedHashMap<>();
		details.put("origen", ORIGEN_REGISTRO);
		details.put("organizationId", String.valueOf(tenant.organizationId()));
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.CUENTA_CREADA,
				tenant.organizationId(), cuenta.getId(), cuenta.getId(),
				null, cuenta.getEstado().name(), details, null, ahora);

		log.info("Alta self-service completada: cuentaId={} organizationId={}",
				cuenta.getId(), tenant.organizationId());

		return new ResultadoRegistro(true, cuenta.getId(), tenant.organizationId(),
				tenant.consultorioId());
	}

	/**
	 * Emite el token de activacion y encola el correo.
	 *
	 * <p>El token en claro existe en una variable local y en ningun lado mas: a la base va su
	 * SHA-256 y al outbox va el enlace ya armado, en el campo de transporte y nunca en el
	 * payload consultable (T-11). No se loguea ni siquiera a nivel DEBUG.
	 */
	private void emitirActivacion(Cuenta cuenta, long organizationId, Instant ahora) {
		String tokenPlano = tokenGenerator.nuevoToken();
		TokenVerificacion token = tokenRepository.save(new TokenVerificacion(
				cuenta.getId(), TipoTokenVerificacion.ACTIVACION,
				TokenDigest.of(tokenPlano), ahora));

		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", cuenta.getNombre());

		notificationOutbox.encolar(new NotificationOutboxPort.Notificacion(
				organizationId,
				NotificationOutboxPort.TipoNotificacion.ACTIVACION_CUENTA,
				cuenta.getEmail(),
				datos,
				linkBuilder.enlaceDe(TipoTokenVerificacion.ACTIVACION, tokenPlano),
				"activacion:" + token.getId()));
	}

	/**
	 * Camino del email ya registrado.
	 *
	 * <p>No toca la cuenta existente —no la lee mas alla de saber que esta, no la modifica y
	 * no guarda su id en el registro de idempotencia—: el alta de un tercero no puede producir
	 * ningun efecto sobre la cuenta de otra persona, ni siquiera un contador.
	 */
	private ResultadoRegistro registrarDuplicado(
			RegistroCuentaCommand command, String emailNormalizado, Instant ahora) {

		notificationOutbox.encolar(new NotificationOutboxPort.Notificacion(
				null,
				NotificationOutboxPort.TipoNotificacion.CUENTA_YA_REGISTRADA,
				command.email().strip(),
				Map.of(),
				null,
				"registro-duplicado:" + command.claveIdempotencia()));

		registrarIdempotencia(OnboardingRegistro.duplicado(
				command.claveIdempotencia(), emailNormalizado, ahora));

		// Nada de auditoria de cuenta: no hubo hecho sobre ninguna cuenta. Auditar aca
		// construiria justamente el indice de direcciones registradas que el 202 uniforme
		// evita, y encima en la tabla que mas gente consulta.
		log.info("Alta self-service sobre un email ya registrado: se encolo el aviso, sin cambios");
		return ResultadoRegistro.sinAlta();
	}

	/**
	 * Inserta el registro de idempotencia con flush.
	 *
	 * <p>Si otro hilo con la misma clave llego primero, el unique lo rechaza. En ese caso no
	 * se relanza: la operacion del ganador ya produjo el efecto correcto y el desenlace que se
	 * devuelve es indistinguible. Lo unico que hay que garantizar es no duplicar.
	 */
	private void registrarIdempotencia(OnboardingRegistro registro) {
		try {
			onboardingRepository.saveAndFlush(registro);
		} catch (DataIntegrityViolationException colision) {
			log.info("Alta self-service concurrente con la misma clave: gano el otro hilo");
		}
	}

	private ResultadoRegistro replayDe(OnboardingRegistro registro) {
		return switch (registro.getEstado()) {
			case COMPLETADO -> new ResultadoRegistro(false, registro.getCuentaId(),
					registro.getOrganizationId(), registro.getConsultorioId());
			case DUPLICADO -> ResultadoRegistro.sinAlta();
		};
	}
}
