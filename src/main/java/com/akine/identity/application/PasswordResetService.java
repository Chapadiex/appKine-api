package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
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
 * Restablecimiento de contrasena (RF-M02-003: enlace temporal, credencial nueva, invalidar el
 * token utilizado).
 *
 * <p>El pedido responde siempre lo mismo, exista o no la cuenta. Es el mismo criterio que el
 * registro y el login: un endpoint publico donde alcanza con escribir una direccion para saber
 * si esta registrada es un enumerador de usuarios, y aca esa lista dice quien es paciente o
 * profesional de un centro de salud.
 *
 * <p>Tres decisiones que hacen la diferencia entre un reset y un camino de toma de cuenta:
 * <ol>
 *   <li><b>Un solo token vigente por cuenta.</b> Pedir el reset tres veces no deja tres llaves
 *       vivas: cada emision invalida las anteriores. Sin esto, la superficie crece con cada
 *       click impaciente y basta comprometer el correo mas viejo.</li>
 *   <li><b>Solo las cuentas ACTIVA generan token.</b> Una cuenta bloqueada no puede recuperar
 *       el acceso por si sola: el bloqueo lo levanta un administrador, no un correo.</li>
 *   <li><b>Confirmar revoca TODAS las sesiones.</b> Quien resetea puede estar expulsando a
 *       alguien que ya entro; dejarle el refresh vivo haria que el reset no sirviera para
 *       nada, que es exactamente el caso en el que mas se lo necesita.</li>
 * </ol>
 */
@Service
public class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	private final CuentaRepositoryPort cuentaRepository;
	private final TokenVerificacionRepositoryPort tokenRepository;
	private final RefreshTokenRepositoryPort refreshTokenRepository;
	private final NotificationOutboxPort notificationOutbox;
	private final VerificationLinkBuilder linkBuilder;
	private final PasswordPolicy passwordPolicy;
	private final PasswordHasher passwordHasher;
	private final TokenGenerator tokenGenerator;
	private final AuditTrail auditTrail;

	public PasswordResetService(
			CuentaRepositoryPort cuentaRepository,
			TokenVerificacionRepositoryPort tokenRepository,
			RefreshTokenRepositoryPort refreshTokenRepository,
			NotificationOutboxPort notificationOutbox,
			VerificationLinkBuilder linkBuilder,
			PasswordPolicy passwordPolicy,
			PasswordHasher passwordHasher,
			TokenGenerator tokenGenerator,
			AuditTrail auditTrail) {
		this.cuentaRepository = cuentaRepository;
		this.tokenRepository = tokenRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.notificationOutbox = notificationOutbox;
		this.linkBuilder = linkBuilder;
		this.passwordPolicy = passwordPolicy;
		this.passwordHasher = passwordHasher;
		this.tokenGenerator = tokenGenerator;
		this.auditTrail = auditTrail;
	}

	/**
	 * Pide un restablecimiento.
	 *
	 * <p><b>No devuelve nada y no lanza nunca por "no existe".</b> La firma es asi a proposito:
	 * un metodo que devolviera un booleano invitaria a que la capa HTTP lo usara para armar la
	 * respuesta, y ahi se pierde la uniformidad. El endpoint responde {@code 202} siempre.
	 */
	@Transactional
	public void solicitar(String email) {
		Optional<Cuenta> encontrada = buscar(email);
		if (encontrada.isEmpty()) {
			log.info("Reset solicitado para un email sin cuenta: no se emite nada");
			return;
		}

		Cuenta cuenta = encontrada.get();
		if (!cuenta.puedeAutenticarse()) {
			// Bloqueada, desactivada o sin credencial: el correo de reset no es el camino para
			// recuperar ninguno de esos casos. Se responde igual, sin emitir token.
			log.info("Reset solicitado sobre una cuenta que no habilita acceso: cuentaId={}",
					cuenta.getId());
			return;
		}

		Instant ahora = Instant.now();
		invalidarTokensReset(cuenta, ahora);

		String tokenPlano = tokenGenerator.nuevoToken();
		TokenVerificacion token = tokenRepository.save(new TokenVerificacion(
				cuenta.getId(), TipoTokenVerificacion.RESET, TokenDigest.of(tokenPlano), ahora));

		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", cuenta.getNombre());

		// El enlace va en el campo de transporte del outbox, jamas en el payload consultable
		// (T-11): esa tabla se lee para diagnosticar entregas, se reintenta desde una pantalla
		// administrativa y termina en los backups. Un token de reset ahi es una credencial
		// persistida, que es lo que RN-M02-003 prohibe.
		notificationOutbox.encolar(new NotificationOutboxPort.Notificacion(
				null,
				NotificationOutboxPort.TipoNotificacion.RESET_PASSWORD,
				cuenta.getEmail(),
				datos,
				linkBuilder.enlaceDe(TipoTokenVerificacion.RESET, tokenPlano),
				"reset:" + token.getId()));

		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.RESET_SOLICITADO,
				null, cuenta.getId(), cuenta.getId(), null, null,
				// El id de la fila, nunca el token: un id no sirve para autenticarse.
				Map.of("tokenVerificacionId", String.valueOf(token.getId())), null, ahora);

		log.info("Reset solicitado: cuentaId={}", cuenta.getId());
	}

	/**
	 * Fija la contrasena nueva y expulsa a todas las sesiones vivas.
	 *
	 * @throws InvalidVerificationTokenException si el token no sirve, sin decir por que
	 * @throws com.akine.identity.domain.exception.PasswordPolicyViolationException si la
	 *         contrasena no cumple la politica
	 */
	@Transactional
	public Cuenta confirmar(String tokenPlano, String passwordNueva) {
		Instant ahora = Instant.now();
		TokenVerificacion token = tokenUtilizable(tokenPlano, ahora);

		Cuenta cuenta = cuentaRepository.findById(token.getCuentaId())
				.orElseThrow(InvalidVerificationTokenException::new);
		if (!cuenta.puedeAutenticarse()) {
			// La cuenta se bloqueo entre el pedido y la confirmacion. El token no puede
			// devolverle el acceso a algo que un administrador acaba de cerrar.
			throw new InvalidVerificationTokenException();
		}

		passwordPolicy.validar(passwordNueva);
		cuenta.fijarPasswordHash(passwordHasher.hash(passwordNueva));
		token.consumir(ahora);

		int sesiones = revocarSesiones(cuenta, ahora);

		cuentaRepository.save(cuenta);
		tokenRepository.save(token);

		Map<String, String> details = new LinkedHashMap<>();
		details.put("tokenVerificacionId", String.valueOf(token.getId()));
		details.put("sesionesRevocadas", String.valueOf(sesiones));
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.RESET_COMPLETADO,
				null, cuenta.getId(), cuenta.getId(), null, null, details, null, ahora);

		log.info("Reset completado: cuentaId={} sesionesRevocadas={}", cuenta.getId(), sesiones);
		return cuenta;
	}

	private Optional<Cuenta> buscar(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		return cuentaRepository.findByEmailNormalizado(EmailNormalizado.of(email));
	}

	private TokenVerificacion tokenUtilizable(String tokenPlano, Instant ahora) {
		if (tokenPlano == null || tokenPlano.isBlank()) {
			throw new InvalidVerificationTokenException();
		}
		TokenVerificacion token = tokenRepository.findByTokenHash(TokenDigest.of(tokenPlano))
				.orElseThrow(InvalidVerificationTokenException::new);
		if (token.getTipo() != TipoTokenVerificacion.RESET || !token.esUtilizableEn(ahora)) {
			throw new InvalidVerificationTokenException();
		}
		return token;
	}

	private void invalidarTokensReset(Cuenta cuenta, Instant ahora) {
		List<TokenVerificacion> vigentes = tokenRepository
				.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
						cuenta.getId(), TipoTokenVerificacion.RESET);
		if (vigentes.isEmpty()) {
			return;
		}
		vigentes.forEach(token -> token.invalidar(ahora));
		tokenRepository.saveAll(vigentes);
	}

	private int revocarSesiones(Cuenta cuenta, Instant ahora) {
		List<RefreshToken> vivos =
				refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(cuenta.getId());
		if (vivos.isEmpty()) {
			return 0;
		}
		vivos.forEach(sesion -> sesion.revocar(MotivoRevocacion.RESET_PASSWORD, ahora));
		refreshTokenRepository.saveAll(vivos);
		return vivos.size();
	}
}
