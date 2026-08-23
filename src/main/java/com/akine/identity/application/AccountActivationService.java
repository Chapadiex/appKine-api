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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
	private final PasswordPolicy passwordPolicy;
	private final PasswordHasher passwordHasher;
	private final AuditTrail auditTrail;

	public AccountActivationService(
			CuentaRepositoryPort cuentaRepository,
			TokenVerificacionRepositoryPort tokenRepository,
			PasswordPolicy passwordPolicy,
			PasswordHasher passwordHasher,
			AuditTrail auditTrail) {
		this.cuentaRepository = cuentaRepository;
		this.tokenRepository = tokenRepository;
		this.passwordPolicy = passwordPolicy;
		this.passwordHasher = passwordHasher;
		this.auditTrail = auditTrail;
	}

	/**
	 * Consume el token y habilita la cuenta.
	 *
	 * @param tokenPlano valor recibido en el enlace
	 * @param password   contrasena nueva, obligatoria solo si la cuenta no tiene credencial
	 * @return la cuenta ya ACTIVA
	 * @throws InvalidVerificationTokenException si el token no existe, ya se uso, fue
	 *         invalidado por uno mas nuevo o vencio — sin distinguir cual de los cuatro
	 */
	@Transactional
	public Cuenta activar(String tokenPlano, String password) {
		Instant ahora = Instant.now();
		TokenVerificacion token = tokenUtilizable(tokenPlano, ahora);

		Cuenta cuenta = cuentaRepository.findById(token.getCuentaId())
				.orElseThrow(InvalidVerificationTokenException::new);

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
