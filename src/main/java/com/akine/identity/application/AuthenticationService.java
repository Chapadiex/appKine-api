package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
import com.akine.identity.domain.exception.InvalidCredentialsException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Validacion de credenciales y de estado de cuenta (RF-M02-002).
 *
 * <p>Este servicio decide UNA cosa: si esta persona puede iniciar sesion. La emision del
 * access token y del refresh es de {@code TokenService}, y la separacion no es cosmetica: la
 * regla de "quien puede entrar" tiene que poder recorrerse sin instanciar nada que firme
 * tokens.
 *
 * <h2>Las cuatro causas de rechazo son una sola respuesta</h2>
 *
 * <p>Email inexistente, contrasena equivocada, cuenta bloqueada y cuenta desactivada terminan
 * todas en {@link InvalidCredentialsException}, que la capa HTTP traduce a un {@code 401}
 * identico. El caso que genera discusion es el tercero: contrasena CORRECTA sobre una cuenta
 * bloqueada. Responder ahi {@code 403 account-disabled} seria mas amable, y convertiria el
 * login en un <b>oraculo de credenciales validas</b>: quien tenga una lista filtrada de otro
 * sitio la prueba aca y descubre cuales sirven, sin necesidad de poder entrar. Esa lista se
 * revende. El usuario legitimo bloqueado se entera por el canal administrativo, que es donde
 * corresponde que se entere.
 *
 * <h2>Y el tiempo tambien tiene que ser el mismo</h2>
 *
 * <p>Un cuerpo de respuesta identico no sirve de nada si la rama "no existe" responde en 2 ms
 * y la rama "existe" en 80. Por eso, cuando no hay cuenta, se invoca
 * {@link PasswordHasher#dummyVerify()}, que quema exactamente el mismo trabajo que una
 * verificacion real. Es la parte de la defensa que mas facil se pierde en un refactor: si
 * alguien "optimiza" el {@code return} temprano, la anti-enumeracion desaparece sin que
 * ningun test de contrato se entere. Por eso hay un test dedicado a esa rama.
 */
@Service
public class AuthenticationService {

	private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

	/**
	 * Fallos consecutivos a partir de los cuales el hecho deja de ser ruido.
	 *
	 * <p>No bloquea la cuenta: bloquear por intentos habilita un DoS dirigido —cualquiera deja
	 * afuera al administrador de un centro escribiendo mal su contrasena— y ademas no hay RF
	 * que lo pida. Lo que hace es emitir un evento para que un administrador decida bloquear a
	 * mano (RF-M02-005). Contra la fuerza bruta protege el rate limit.
	 */
	static final int UMBRAL_ACTIVIDAD_SOSPECHOSA = 10;

	private final CuentaRepositoryPort cuentaRepository;
	private final PasswordHasher passwordHasher;
	private final AuditTrail auditTrail;

	public AuthenticationService(
			CuentaRepositoryPort cuentaRepository,
			PasswordHasher passwordHasher,
			AuditTrail auditTrail) {
		this.cuentaRepository = cuentaRepository;
		this.passwordHasher = passwordHasher;
		this.auditTrail = auditTrail;
	}

	/**
	 * Valida las credenciales y devuelve la cuenta autenticada.
	 *
	 * <p>Transaccional porque escribe: el contador de fallos, la marca de ultimo login y el
	 * evento de auditoria van todos en la misma transaccion (T-2). Que el camino de fallo
	 * tambien escriba es deliberado — un login fallido sin rastro es exactamente lo que no se
	 * puede investigar despues.
	 *
	 * @param email    tal como lo tipeo la persona
	 * @param password contrasena en claro. No se loguea, ni siquiera su longitud
	 * @return la cuenta, ya con el login registrado
	 * @throws InvalidCredentialsException en las cuatro causas de rechazo, sin distinguirlas
	 */
	@Transactional
	public Cuenta autenticar(String email, String password) {
		Optional<Cuenta> encontrada = buscarPorEmail(email);

		if (encontrada.isEmpty()) {
			// Sin esto, la rama "el email no existe" responderia en microsegundos y el tiempo
			// de respuesta diria lo que el cuerpo se cuida de no decir.
			passwordHasher.dummyVerify();
			IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.LOGIN_FALLIDO,
					null, null, null, null, null,
					Map.of("motivo", "CUENTA_INEXISTENTE"), null, Instant.now());
			log.info("Login rechazado: no hay cuenta para el email presentado");
			throw new InvalidCredentialsException();
		}

		Cuenta cuenta = encontrada.get();
		Instant ahora = Instant.now();

		if (!passwordHasher.matches(password, cuenta.getPasswordHash())) {
			return rechazarPorCredencial(cuenta, ahora);
		}

		// Contrasena correcta pero la cuenta no habilita el acceso. Misma excepcion, distinto
		// evento: al cliente no se le dice nada, pero en auditoria queda la diferencia entre
		// "se equivoco" y "esta bloqueado y sigue intentando entrar", que es una senial.
		if (!cuenta.puedeAutenticarse()) {
			Map<String, String> details = new LinkedHashMap<>();
			details.put("estado", cuenta.getEstado().name());
			IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.LOGIN_RECHAZADO_ESTADO,
					null, cuenta.getId(), cuenta.getId(), cuenta.getEstado().name(), null,
					details, null, ahora);
			log.info("Login rechazado por estado de cuenta: cuentaId={} estado={}",
					cuenta.getId(), cuenta.getEstado());
			throw new InvalidCredentialsException();
		}

		cuenta.registrarLoginExitoso(ahora);
		cuentaRepository.save(cuenta);
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.LOGIN_EXITOSO,
				null, cuenta.getId(), cuenta.getId(), null, null, Map.of(), null, ahora);
		log.info("Login exitoso: cuentaId={}", cuenta.getId());
		return cuenta;
	}

	private Cuenta rechazarPorCredencial(Cuenta cuenta, Instant ahora) {
		int fallos = cuenta.registrarLoginFallido();
		cuentaRepository.save(cuenta);

		Map<String, String> details = new LinkedHashMap<>();
		details.put("motivo", "CREDENCIALES");
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.LOGIN_FALLIDO,
				null, cuenta.getId(), cuenta.getId(), null, null, details, null, ahora);

		if (fallos >= UMBRAL_ACTIVIDAD_SOSPECHOSA) {
			IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.ACTIVIDAD_SOSPECHOSA,
					null, cuenta.getId(), null, null, null,
					Map.of("intentosFallidos", String.valueOf(fallos)), null, ahora);
			log.warn("Actividad sospechosa: cuentaId={} acumula {} fallos consecutivos",
					cuenta.getId(), fallos);
		}

		log.info("Login rechazado por credencial invalida: cuentaId={}", cuenta.getId());
		throw new InvalidCredentialsException();
	}

	/**
	 * Busca la cuenta tolerando un email con formato invalido.
	 *
	 * <p>Un email vacio o en blanco no es un error del servidor sino un intento de login que
	 * no puede prosperar, y tiene que salir por el MISMO camino que un email inexistente: si
	 * lanzara una excepcion distinta, la respuesta cambiaria y volveria a haber dos
	 * comportamientos observables donde tiene que haber uno.
	 */
	private Optional<Cuenta> buscarPorEmail(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		return cuentaRepository.findByEmailNormalizado(EmailNormalizado.of(email));
	}
}
