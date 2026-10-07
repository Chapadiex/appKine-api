package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import com.akine.organization.spi.PlatformAdminRoster;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bootstrap del primer administrador de plataforma (DP-14, AKINE-A-4).
 *
 * <p>{@code V15} siembra la cuenta de plataforma sin credencial, y ningun flujo publico le deja
 * fijar una: el reset descarta las cuentas que no pueden autenticarse (ADR-0018) y otorgar el rol
 * exige tenerlo. Al arrancar, si no hay ningun administrador de plataforma <b>con credencial</b> y
 * el operador definio {@code AKINE_BOOTSTRAP_ADMIN_EMAIL}, esta clase re-apunta la cuenta sembrada
 * a esa casilla y le emite un enlace de activacion por el outbox, con el mismo emisor que el
 * registro. Desde ahi la activacion es la de siempre: la persona fija su contrasena y la fila de
 * {@code platform_role}, que nunca se toca, la reconoce.
 *
 * <p><b>Nunca lanza por una condicion de negocio.</b> Cada camino que no hace nada devuelve un
 * {@link ResultadoBootstrap} que dice por que, y el runner lo loguea: un arranque no puede caerse
 * porque la plataforma ya tenga administrador. Lo que si puede propagar es un choque de
 * concurrencia o de unique al hacer flush (otra instancia gano, alguien registro ese email en el
 * medio); la transaccion se revierte entera y el runner lo registra.
 *
 * <p>Diseno y design challenge: {@code docs/diseno/AKINE-A-4-bootstrap.md}.
 */
@Service
public class PlatformAdminBootstrapService {

	/**
	 * Forma minima de una direccion: algo, arroba, dominio con punto, sin espacios. La validacion
	 * de {@code @Email} vive en la capa HTTP; aca alcanza con no aceptar basura evidente de una
	 * variable de entorno mal copiada.
	 */
	private static final Pattern FORMA_DE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	/** Mismo tope que {@code RegisterAccountRequest}. */
	private static final int LARGO_MAXIMO_EMAIL = 254;

	private final CuentaRepositoryPort cuentaRepository;
	private final TokenVerificacionRepositoryPort tokenRepository;
	private final PlatformAdminRoster platformAdminRoster;
	private final AccountActivationService activationService;
	private final AuditTrail auditTrail;
	private final IdentityClock clock;

	public PlatformAdminBootstrapService(
			CuentaRepositoryPort cuentaRepository,
			TokenVerificacionRepositoryPort tokenRepository,
			PlatformAdminRoster platformAdminRoster,
			AccountActivationService activationService,
			AuditTrail auditTrail,
			IdentityClock clock) {
		this.cuentaRepository = cuentaRepository;
		this.tokenRepository = tokenRepository;
		this.platformAdminRoster = platformAdminRoster;
		this.activationService = activationService;
		this.auditTrail = auditTrail;
		this.clock = clock;
	}

	/**
	 * Ejecuta el bootstrap si corresponde.
	 *
	 * @param emailConfigurado valor de {@code akine.bootstrap.admin-email}; {@code null} o en
	 *                         blanco significa que el operador no pidio nada
	 * @return que hizo, o por que no hizo nada
	 */
	@Transactional
	public ResultadoBootstrap ejecutar(String emailConfigurado) {
		if (emailConfigurado == null || emailConfigurado.isBlank()) {
			return ResultadoBootstrap.SIN_VARIABLE;
		}
		String email = emailConfigurado.strip();
		if (email.length() > LARGO_MAXIMO_EMAIL || !FORMA_DE_EMAIL.matcher(email).matches()) {
			return ResultadoBootstrap.EMAIL_INVALIDO;
		}

		Instant ahora = clock.now();
		List<Cuenta> cuentasDePlataforma = platformAdminRoster.cuentasConRolDePlataforma(ahora).stream()
				.map(cuentaRepository::findById)
				.flatMap(Optional::stream)
				.toList();

		// Literal de DP-14: "con credencial", no "habilitada". Una cuenta de plataforma
		// bloqueada sigue siendo un administrador; desbloquearla es de una persona, no de un
		// arranque.
		if (cuentasDePlataforma.stream().anyMatch(cuenta -> cuenta.getPasswordHash() != null)) {
			return ResultadoBootstrap.YA_HAY_ADMIN_CON_CREDENCIAL;
		}

		List<Cuenta> candidatas = cuentasDePlataforma.stream()
				.filter(Cuenta::admiteBootstrapDePlataforma)
				.toList();
		if (candidatas.isEmpty()) {
			return ResultadoBootstrap.SIN_CUENTA_SEMBRADA;
		}
		if (candidatas.size() > 1) {
			return ResultadoBootstrap.CANDIDATAS_AMBIGUAS;
		}
		Cuenta cuenta = candidatas.get(0);

		String normalizado = EmailNormalizado.of(email);
		Optional<Cuenta> duenia = cuentaRepository.findByEmailNormalizado(normalizado);
		if (duenia.isPresent() && !duenia.get().getId().equals(cuenta.getId())) {
			// No se pisa nada y NO se le otorga el rol a esa otra cuenta: no hay RF que lo diga, y
			// darle el permiso mas alto del sistema a quien se registro con esa casilla es la toma
			// de control que ADR-0020 exige hacer con autor y motivo.
			return ResultadoBootstrap.EMAIL_DE_OTRA_CUENTA;
		}

		boolean mismoEmail = normalizado.equals(cuenta.getEmailNormalizado());
		if (mismoEmail && cuenta.getEstado() == EstadoCuenta.PENDIENTE_ACTIVACION
				&& tieneEnlaceVigente(cuenta, ahora)) {
			return ResultadoBootstrap.ENLACE_VIGENTE;
		}

		String emailAnterior = cuenta.getEmail();
		String estadoAnterior = cuenta.getEstado().name();
		cuenta.prepararBootstrapDePlataforma(email);
		// Flush antes de emitir: si otra instancia gano la carrera (@Version) o alguien tomo el
		// email (unique), que falle aca y no despues de haber encolado un correo.
		cuentaRepository.saveAndFlush(cuenta);

		TokenVerificacion token = activationService.emitirEnlaceDeActivacion(cuenta, ahora);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("emailAnterior", emailAnterior);
		detalles.put("emailNuevo", cuenta.getEmail());
		detalles.put("tokenVerificacionId", String.valueOf(token.getId()));
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.PLATFORM_ADMIN_BOOTSTRAP,
				null, cuenta.getId(), null, estadoAnterior, cuenta.getEstado().name(),
				detalles, "DP-14: AKINE_BOOTSTRAP_ADMIN_EMAIL definida y sin administrador de "
						+ "plataforma con credencial", ahora);

		return ResultadoBootstrap.ENLACE_EMITIDO;
	}

	private boolean tieneEnlaceVigente(Cuenta cuenta, Instant ahora) {
		return tokenRepository
				.findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
						cuenta.getId(), TipoTokenVerificacion.ACTIVACION)
				.stream()
				.anyMatch(token -> token.esUtilizableEn(ahora));
	}

	/** Lo que hizo el bootstrap, o por que no hizo nada. */
	public enum ResultadoBootstrap {

		/** La variable no esta definida: el caso normal de todo arranque. */
		SIN_VARIABLE,

		/** La variable no tiene forma de email. */
		EMAIL_INVALIDO,

		/** Ya hay un administrador de plataforma con credencial: no hay nada que rescatar. */
		YA_HAY_ADMIN_CON_CREDENCIAL,

		/** Ninguna cuenta con rol de plataforma puede recibir el bootstrap (o no hay ninguna). */
		SIN_CUENTA_SEMBRADA,

		/** Mas de una cuenta con rol de plataforma y sin credencial: no se elige al azar. */
		CANDIDATAS_AMBIGUAS,

		/** El email ya es de otra cuenta. No se pisa nada ni se otorga el rol. */
		EMAIL_DE_OTRA_CUENTA,

		/** La cuenta ya apunta a ese email y tiene un enlace de activacion vigente. */
		ENLACE_VIGENTE,

		/** Se re-apunto la cuenta y se encolo el enlace de activacion. */
		ENLACE_EMITIDO
	}
}
