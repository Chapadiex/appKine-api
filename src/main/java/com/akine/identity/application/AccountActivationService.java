package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.identity.domain.exception.InvalidVerificationTokenException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Confirmacion del enlace que habilita una cuenta (RF-M02-001, RF-M26-001).
 *
 * <p>Cubre los dos origenes con el mismo codigo:
 * <ul>
 *   <li><b>alta self-service</b>: la cuenta ya tiene credencial, fijada al registrarse. El
 *       enlace solo confirma que quien puso esa direccion la controla;</li>
 *   <li><b>invitacion</b> (01.03): la cuenta nace sin credencial y la persona la fija aca.</li>
 * </ul>
 *
 * <p>Por eso {@code password} es opcional: se exige exactamente cuando la cuenta no tiene
 * credencial. Aceptarla siempre permitiria que un enlace de activacion reenviado sirviera para
 * cambiarle la contrasena a una cuenta que ya la tenia — un camino de toma de cuenta que
 * esquiva el flujo de reset, que si revoca todas las sesiones.
 *
 * <h2>Lo que NO se implementa aca, a proposito</h2>
 *
 * <p>El diseño original proponia un {@code IdentityProvisioningSpi.provisionarCuentaInvitada}
 * para que {@code organization} creara cuentas al invitar. No existe y no va a existir: seria
 * la flecha {@code organization -> identity}, prohibida sin excepciones (T-1), y cerraria un
 * ciclo que ArchUnit rechaza. La invitacion se parte en dos: <b>invitar</b> es de
 * {@code organization} y no toca cuentas; <b>aceptar</b> es de {@code identity} —esta clase— y
 * llama a {@code organization.spi} para activar la membership. La mitad de {@code organization}
 * llega en 01.03.
 */
@Service
public class AccountActivationService {

	private static final Logger log = LoggerFactory.getLogger(AccountActivationService.class);

	private final CuentaRepositoryPort cuentaRepository;
	private final TokenVerificacionRepositoryPort tokenRepository;
	private final NotificationOutboxPort notificationOutbox;
	private final VerificationLinkBuilder linkBuilder;
	private final PasswordPolicy passwordPolicy;
	private final PasswordHasher passwordHasher;
	private final TokenGenerator tokenGenerator;
	private final AuditTrail auditTrail;

	public AccountActivationService(
			CuentaRepositoryPort cuentaRepository,
			TokenVerificacionRepositoryPort tokenRepository,
			NotificationOutboxPort notificationOutbox,
			VerificationLinkBuilder linkBuilder,
			PasswordPolicy passwordPolicy,
			PasswordHasher passwordHasher,
			TokenGenerator tokenGenerator,
			AuditTrail auditTrail) {
		this.cuentaRepository = cuentaRepository;
		this.tokenRepository = tokenRepository;
		this.notificationOutbox = notificationOutbox;
		this.linkBuilder = linkBuilder;
		this.passwordPolicy = passwordPolicy;
		this.passwordHasher = passwordHasher;
		this.tokenGenerator = tokenGenerator;
		this.auditTrail = auditTrail;
	}

	/**
	 * Vuelve a emitir el enlace de activacion (RF-M02-001, caso borde "el correo no llego").
	 *
	 * <p><b>No devuelve nada y no lanza nunca.</b> La firma copia la de
	 * {@code PasswordResetService.solicitar} por el mismo motivo: un booleano o una excepcion
	 * invitarian a que la capa HTTP los usara para armar la respuesta, y ahi se pierde la
	 * uniformidad de ADR-0018. El endpoint responde {@code 202} identico en los tres casos que
	 * salen por aca sin hacer nada —email sin cuenta, cuenta ya activa, cuenta bloqueada o
	 * desactivada— y en el que si emite.
	 *
	 * <p>Emitir invalida los enlaces de activacion anteriores. Sin eso, pedir el reenvio tres
	 * veces dejaria tres llaves vivas de la misma puerta, y el correo mas viejo —el que
	 * probablemente ya se reenvio a alguien o quedo en un buzon compartido— seguiria sirviendo.
	 */
	@Transactional
	public void reenviarActivacion(String email) {
		Optional<Cuenta> encontrada = buscarPorEmail(email);
		if (encontrada.isEmpty()) {
			log.info("Reenvio de activacion para un email sin cuenta: no se emite nada");
			return;
		}

		Cuenta cuenta = encontrada.get();
		if (cuenta.getEstado() != EstadoCuenta.PENDIENTE_ACTIVACION) {
			// Ya activa, bloqueada o desactivada. Ninguna de las tres se resuelve con un enlace
			// de activacion, y emitir uno sobre una cuenta bloqueada seria devolverle por la
			// ventana el acceso que un administrador acaba de cerrar.
			log.info("Reenvio de activacion sobre una cuenta que no esta pendiente: cuentaId={}",
					cuenta.getId());
			return;
		}

		Instant ahora = Instant.now();
		invalidarTokensDeActivacion(cuenta, ahora);

		String tokenPlano = tokenGenerator.nuevoToken();
		TokenVerificacion token = tokenRepository.save(new TokenVerificacion(
				cuenta.getId(), TipoTokenVerificacion.ACTIVACION,
				TokenDigest.of(tokenPlano), ahora));

		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", cuenta.getNombre());

		// organizationId va en null: el reenvio se pide sin sesion y sin contexto, asi que
		// identity no sabe —ni tiene por que preguntarle a organization— a que tenant pertenece
		// esta persona. El outbox lo admite: null es "evento de identidad global".
		notificationOutbox.encolar(new NotificationOutboxPort.Notificacion(
				null,
				NotificationOutboxPort.TipoNotificacion.ACTIVACION_CUENTA,
				cuenta.getEmail(),
				datos,
				// El enlace va al campo de transporte, jamas al payload consultable (T-11).
				linkBuilder.enlaceDe(TipoTokenVerificacion.ACTIVACION, tokenPlano),
				"activacion:" + token.getId()));

		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.ACTIVACION_REENVIADA,
				null, cuenta.getId(), cuenta.getId(), null, null,
				// El id de la fila, nunca el token: un id no sirve para activar nada.
				Map.of("tokenVerificacionId", String.valueOf(token.getId())), null, ahora);

		log.info("Enlace de activacion reenviado: cuentaId={}", cuenta.getId());
	}

	/** Busca tolerando un email vacio: la falta de dato sale por el mismo camino que la falta de cuenta. */
	private Optional<Cuenta> buscarPorEmail(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		return cuentaRepository.findByEmailNormalizado(EmailNormalizado.of(email));
	}

	private void invalidarTokensDeActivacion(Cuenta cuenta, Instant ahora) {
		List<TokenVerificacion> vigentes = tokenRepository
				.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
						cuenta.getId(), TipoTokenVerificacion.ACTIVACION);
		if (vigentes.isEmpty()) {
			return;
		}
		vigentes.forEach(anterior -> anterior.invalidar(ahora));
		tokenRepository.saveAll(vigentes);
	}

	/**
	 * Consume el token y habilita la cuenta.
	 *
	 * @param tokenPlano valor recibido en el enlace
	 * @param password   contrasena nueva, obligatoria solo si la cuenta no tiene credencial
	 * @return la cuenta ya ACTIVA
	 * @throws InvalidVerificationTokenException si el token no existe, ya se uso, fue
	 *         invalidado por uno mas nuevo, vencio, <b>o la cuenta no esta
	 *         PENDIENTE_ACTIVACION</b> — sin distinguir cual de los cinco (ADR-0018)
	 */
	@Transactional
	public Cuenta activar(String tokenPlano, String password) {
		Instant ahora = Instant.now();
		TokenVerificacion token = tokenUtilizable(tokenPlano, ahora);

		Cuenta cuenta = cuentaRepository.findById(token.getCuentaId())
				.orElseThrow(InvalidVerificationTokenException::new);

		// La cuenta tiene que estar PENDIENTE_ACTIVACION. No alcanza con delegar en la maquina
		// de estados, y son dos problemas distintos los que eso dejaba abiertos:
		//
		// (a) FUGA DE ESTADO. Sin esta linea, una transicion ilegal salia como
		//     InvalidAccountTransitionException -> 409 con el detalle "La cuenta no admite pasar
		//     de DESACTIVADA a ACTIVA" y con fromStatus/toStatus en el cuerpo. Camino real:
		//     alguien se registra, no activa, un administrador desactiva la cuenta
		//     (PENDIENTE_ACTIVACION -> DESACTIVADA ES legal), y quien tenga el enlace obtiene el
		//     estado exacto de esa cuenta. ADR-0018 exige 400 invalid-token uniforme para token
		//     inexistente, usado, invalidado o expirado; el estado de la cuenta no puede ser la
		//     excepcion. PasswordResetService.confirmar ya hacia exactamente esto.
		//
		// (b) AUTODESBLOQUEO. BLOQUEADA -> ACTIVA es una transicion legal —existe para el
		//     desbloqueo administrativo— y activar() la ejecutaba sin restriccion. Hoy no se
		//     puede completar el escenario, pero en cuanto 01.03 traiga invitaciones —cuentas
		//     con token de activacion vivo de 7 dias que despues pueden bloquearse— el titular
		//     del enlace se desbloquearia a si mismo presentandolo, anulando la decision del
		//     administrador. La condicion de arriba lo impide hoy, antes de que el escenario
		//     exista.
		if (cuenta.getEstado() != EstadoCuenta.PENDIENTE_ACTIVACION) {
			log.info("Activacion rechazada por estado de cuenta: cuentaId={}", cuenta.getId());
			throw new InvalidVerificationTokenException();
		}

		if (cuenta.getPasswordHash() == null) {
			passwordPolicy.validar(password);
			cuenta.fijarPasswordHash(passwordHasher.hash(password));
		}

		cuenta.transicionarA(EstadoCuenta.ACTIVA, null, ahora);
		token.consumir(ahora);

		// Los demas tokens de activacion de la cuenta dejan de servir: si se reenvio el correo
		// tres veces, quedaron tres enlaces vivos, y el que no se uso sigue siendo una llave.
		invalidarOtrosTokens(cuenta, token, ahora);

		cuentaRepository.save(cuenta);
		tokenRepository.save(token);

		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.CUENTA_ACTIVADA,
				null, cuenta.getId(), cuenta.getId(),
				EstadoCuenta.PENDIENTE_ACTIVACION.name(), EstadoCuenta.ACTIVA.name(),
				Map.of("tokenVerificacionId", String.valueOf(token.getId())), null, ahora);

		log.info("Cuenta activada: cuentaId={}", cuenta.getId());
		return cuenta;
	}

	/**
	 * Busca el token y comprueba que sirva.
	 *
	 * <p>Un token invalido y uno inexistente salen por la misma excepcion, sin mensaje que los
	 * distinga: decirle "expirado" a quien lo presenta le confirma que acerto un valor real, y
	 * eso es justo lo que necesita saber quien esta probando.
	 */
	private TokenVerificacion tokenUtilizable(String tokenPlano, Instant ahora) {
		if (tokenPlano == null || tokenPlano.isBlank()) {
			throw new InvalidVerificationTokenException();
		}
		Optional<TokenVerificacion> encontrado =
				tokenRepository.findByTokenHash(TokenDigest.of(tokenPlano));

		TokenVerificacion token = encontrado.orElseThrow(InvalidVerificationTokenException::new);
		if (token.getTipo() != TipoTokenVerificacion.ACTIVACION || !token.esUtilizableEn(ahora)) {
			// Un token de RESET presentado en el endpoint de activacion tampoco sirve: cada
			// token vale para lo que se emitio y para nada mas.
			throw new InvalidVerificationTokenException();
		}
		return token;
	}

	private void invalidarOtrosTokens(Cuenta cuenta, TokenVerificacion consumido, Instant ahora) {
		var vigentes = tokenRepository
				.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
						cuenta.getId(), TipoTokenVerificacion.ACTIVACION)
				.stream()
				.filter(otro -> !otro.getTokenHash().equals(consumido.getTokenHash()))
				.toList();

		vigentes.forEach(otro -> otro.invalidar(ahora));
		if (!vigentes.isEmpty()) {
			tokenRepository.saveAll(vigentes);
		}
	}
}
