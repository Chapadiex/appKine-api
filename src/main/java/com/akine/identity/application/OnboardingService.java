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
import com.akine.organization.spi.OnboardingKeyTakenException;
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
	 *         contrasena no cumple la politica. No revela nada: habla de lo que la persona
	 *         acaba de tipear
	 * @throws com.akine.organization.application.PlanNotFoundException si el plan pedido no es
	 *         contratable. <b>Se evalua antes de mirar el email</b>, asi que la respuesta es la
	 *         misma exista o no la cuenta: ver el paso 2 de {@code ejecutarAlta}
	 * @throws com.akine.organization.domain.exception.OrganizationSlugTakenException si el slug
	 *         explicito ya esta tomado. Tambien antes de mirar el email, y por el mismo motivo
	 */
	public ResultadoRegistro registrar(RegistroCuentaCommand command) {
		passwordPolicy.validar(command.password());
		String passwordHash = passwordHasher.hash(command.password());

		try {
			return transactionTemplate.execute(status -> ejecutarAlta(command, passwordHash));
		} catch (EmailTomadoPorOtroHilo colision) {
			// La transaccion de arriba ya murio: su flush fallo y con el se rompio la sesion.
			// El camino del duplicado se rehace ACA, en una transaccion nueva y por lo tanto
			// con un EntityManager limpio. Ver el JavaDoc de EmailTomadoPorOtroHilo.
			return recuperarDeColisionDeEmail(command, colision);
		} catch (ClaveTomadaPorOtroHilo colision) {
			// El otro hilo ya registro el mismo pedido: lo nuestro se revirtio entero y no hay
			// nada mas que hacer. El desenlace es indistinguible del suyo.
			return ResultadoRegistro.sinAlta();
		} catch (OnboardingKeyTakenException colision) {
			// Lo mismo, pero detectado del lado de organization: su uk_onboarding_key llega
			// ANTES que el nuestro, porque el alta del tenant corre en el medio de esta
			// transaccion. organization no puede recuperarse ahi —su flush ya rompio la sesion
			// y ademas la transaccion es NUESTRA, no suya— asi que nos manda la senal por el
			// spi y el que decide revertir es el dueno del limite transaccional, que es este
			// metodo. Sin esto la excepcion llegaba a HTTP como 500, alcanzable con la misma
			// Idempotency-Key y dos emails distintos, rompiendo la uniformidad del 202.
			log.info("Alta self-service concurrente: organization registro antes la misma clave");
			return ResultadoRegistro.sinAlta();
		}
	}

	/**
	 * Rehace el trabajo pendiente despues de una colision de email, en una transaccion nueva.
	 *
	 * <p>Primero se vuelve a mirar la clave de idempotencia: si el hilo ganador ya dejo su
	 * desenlace, ese es el desenlace de este pedido tambien —es el mismo pedido— y no hace
	 * falta encolar nada. Si no lo dejo, se toma el camino del duplicado normal.
	 */
	private ResultadoRegistro recuperarDeColisionDeEmail(
			RegistroCuentaCommand command, EmailTomadoPorOtroHilo colision) {

		try {
			return transactionTemplate.execute(status -> {
				Optional<OnboardingRegistro> previo =
						onboardingRepository.findByClaveIdempotencia(command.claveIdempotencia());
				if (previo.isPresent()) {
					return replayDe(previo.get());
				}
				return registrarDuplicado(
						command, colision.emailNormalizado(), colision.ahora());
			});
		} catch (ClaveTomadaPorOtroHilo yaEstaba) {
			return ResultadoRegistro.sinAlta();
		}
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

		// 2. TODO lo que se pueda rechazar del pedido se rechaza ACA, ANTES de mirar el email.
		//    No es una optimizacion: es lo que hace que la respuesta sea la misma exista o no
		//    la cuenta. Lo que valida organization —plan contratable, slug libre— lo validaba
		//    solo el camino de creacion, porque el camino del duplicado ni siquiera llama al
		//    spi. Con eso, un planCode inexistente respondia 404 si el email estaba libre y 202
		//    si ya existia: un solo request publico, sin autenticacion y sin depender de
		//    tiempos, convertido en verificador de direcciones de correo. Justo lo que ADR-0018
		//    cierra.
		//
		//    Validar despues de mirar el email —solo cuando hace falta— es lo que produce la
		//    fuga. Validando antes, las dos ramas terminan en el mismo estado y el mismo cuerpo:
		//    plan invalido da el mismo 404 con email libre y con email tomado, y plan valido da
		//    el mismo 202 en los dos casos.
		organizationProvisioning.validateTenantRequest(
				command.planCode(), command.organizacionSlug());

		//    Y lo mismo con los datos de render del correo. Era el MISMO agujero que planCode,
		//    en otra pieza: el outbox rechaza valores que contengan "password", "secret", "jwt",
		//    "token=", "://" o "bearer " —y esa validacion vivia dentro de emitirActivacion, que
		//    solo corre cuando el email esta libre, porque el camino del duplicado encola un
		//    payload vacio—. Con eso, un firstName de "Password" respondia 400 con el email
		//    libre y 202 con el email tomado: un request por direccion, sin autenticacion y sin
		//    depender de tiempos, y el padron entero de cuentas.
		//
		//    Se valida el mapa que se va a encolar, no una copia de la regla: la lista de
		//    fragmentos prohibidos sigue viviendo en un solo lugar (SanitizedPayload), detras
		//    del puerto. Y se valida UNA vez, no en las dos ramas: dos copias divergen a la
		//    primera modificacion, que es lo que el javadoc del spi ya advertia.
		Map<String, String> datosDeRender = datosDeRenderDe(command);
		notificationOutbox.validarDatosPlantilla(datosDeRender);

		Instant ahora = Instant.now();
		String emailNormalizado = EmailNormalizado.of(command.email());

		// 3. El email ya tiene cuenta: no se crea nada y se avisa por correo. La respuesta al
		//    cliente sera identica a la del alta exitosa.
		if (cuentaRepository.findByEmailNormalizado(emailNormalizado).isPresent()) {
			return registrarDuplicado(command, emailNormalizado, ahora);
		}

		return crearCuentaYTenant(command, emailNormalizado, passwordHash, ahora, datosDeRender);
	}

	/**
	 * Los datos que la plantilla del correo va a renderizar.
	 *
	 * <p><b>Unico lugar donde se arma el mapa.</b> Se construye a partir del pedido —no de la
	 * cuenta ya creada— justamente para poder validarlo antes de mirar el email: si dependiera
	 * de la entidad, solo se podria validar en la rama que la crea, que es el agujero que se
	 * esta cerrando.
	 */
	private static Map<String, String> datosDeRenderDe(RegistroCuentaCommand command) {
		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", command.nombre());
		return datos;
	}

	private ResultadoRegistro crearCuentaYTenant(
			RegistroCuentaCommand command,
			String emailNormalizado,
			String passwordHash,
			Instant ahora,
			Map<String, String> datosDeRender) {

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
			//
			// PERO NO DESDE ACA. El flush ya fallo, y despues de un flush fallido la sesion de
			// JPA queda en estado indefinido: seguir escribiendo en ella termina en
			// AssertionFailure ("Entry for instance ... has a null identifier") y el 500 que
			// eso produce rompe la uniformidad del 202 de ADR-0018. Se sale de la transaccion
			// con esta senal y el duplicado se rehace en una sesion nueva.
			log.info("Alta self-service concurrente sobre el mismo email: se trata como duplicado");
			throw new EmailTomadoPorOtroHilo(emailNormalizado, ahora);
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

		emitirActivacion(cuenta, tenant.organizationId(), ahora, datosDeRender);

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
	 *
	 * <p>Los datos de render <b>llegan ya validados desde el borde</b> y no se rearman aca: si
	 * este metodo los construyera, su validacion volveria a ocurrir solo en la rama que crea la
	 * cuenta y el oraculo de existencia reaparece.
	 */
	private void emitirActivacion(
			Cuenta cuenta, long organizationId, Instant ahora, Map<String, String> datos) {

		String tokenPlano = tokenGenerator.nuevoToken();
		TokenVerificacion token = tokenRepository.save(new TokenVerificacion(
				cuenta.getId(), TipoTokenVerificacion.ACTIVACION,
				TokenDigest.of(tokenPlano), ahora));

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

		// Va ultimo y con flush: es el que decide si este pedido produce efecto. Si otro hilo
		// ya registro la misma clave, esta transaccion se revierte entera —el aviso encolado
		// arriba incluido, que es lo correcto: el ganador ya mando el suyo—.
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
	 * <p>Si otro hilo con la misma clave llego primero, el unique lo rechaza. Ese rechazo
	 * <b>no se puede tragar</b>: rompe la sesion de JPA igual que cualquier otro flush fallido,
	 * y el commit posterior explotaria con {@code AssertionFailure} —o con "Transaction marked
	 * as rollbackOnly"— dandole un 500 a quien hizo todo bien. Se convierte en una senal que
	 * revierte esta transaccion y se atiende arriba, fuera de la sesion rota: el ganador ya
	 * produjo el efecto correcto y el desenlace que se devuelve es indistinguible del suyo. Lo
	 * unico que hay que garantizar es no duplicar.
	 */
	private void registrarIdempotencia(OnboardingRegistro registro) {
		try {
			onboardingRepository.saveAndFlush(registro);
		} catch (DataIntegrityViolationException colision) {
			log.info("Alta self-service concurrente con la misma clave: gano el otro hilo");
			throw new ClaveTomadaPorOtroHilo();
		}
	}

	private ResultadoRegistro replayDe(OnboardingRegistro registro) {
		return switch (registro.getEstado()) {
			case COMPLETADO -> new ResultadoRegistro(false, registro.getCuentaId(),
					registro.getOrganizationId(), registro.getConsultorioId());
			case DUPLICADO -> ResultadoRegistro.sinAlta();
		};
	}

	/**
	 * Senal interna: el unique del email se activo dentro del flush de la cuenta.
	 *
	 * <p>Existe para <b>salir de la transaccion</b>, no para informar de un error. Una
	 * {@code PersistenceException} deja el {@code EntityManager} en estado indefinido y la
	 * especificacion de JPA prohibe seguir usandolo: el unico movimiento legal es revertir y
	 * empezar de nuevo. Llevarse el email normalizado y el instante evita recalcularlos.
	 *
	 * <p>Nunca sale de esta clase: {@link #registrar(RegistroCuentaCommand)} la atrapa y la
	 * traduce al mismo desenlace que cualquier otro duplicado. Si escapara, la capa HTTP
	 * respondería 500 y con eso volveria a distinguirse "ese email ya estaba" de "se creo",
	 * que es exactamente el oraculo que ADR-0018 cierra.
	 */
	private static final class EmailTomadoPorOtroHilo extends RuntimeException {

		private final transient String emailNormalizado;
		private final transient Instant ahora;

		private EmailTomadoPorOtroHilo(String emailNormalizado, Instant ahora) {
			// Sin causa, sin stack trace: no es una condicion excepcional, es una carrera
			// esperada, y llenar el log con su traza no ayuda a nadie.
			super(null, null, false, false);
			this.emailNormalizado = emailNormalizado;
			this.ahora = ahora;
		}

		private String emailNormalizado() {
			return emailNormalizado;
		}

		private Instant ahora() {
			return ahora;
		}
	}

	/** Senal interna: otro hilo registro antes la misma clave de idempotencia. */
	private static final class ClaveTomadaPorOtroHilo extends RuntimeException {

		private ClaveTomadaPorOtroHilo() {
			super(null, null, false, false);
		}
	}
}
