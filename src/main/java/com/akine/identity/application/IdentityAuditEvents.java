package com.akine.identity.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;

/**
 * Catalogo de eventos auditables de {@code identity} y armado de la entrada.
 *
 * <p>Los tipos son constantes y no un enum porque {@code AuditEntry.eventType} es un
 * {@code String} en el contrato de {@code platform.spi.audit}: cada modulo declara su catalogo
 * sin que la plataforma tenga que conocerlos todos.
 *
 * <p><b>Regla que no se negocia aca mas que en ningun otro modulo:</b> ni el mapa
 * {@code details}, ni {@code reason}, ni ningun campo lleva contrasenas, tokens de
 * verificacion, refresh tokens ni fragmentos de ellos. La auditoria de identidad registra QUE
 * paso y SOBRE QUE cuenta; si ademas registrara el secreto, seria el mejor lugar del sistema
 * para robar credenciales. De los tokens solo se registra el ID DE LA FILA, que no sirve para
 * autenticarse.
 *
 * <p><b>Se escribe DENTRO de la transaccion de negocio</b> (T-2). No es un listener
 * post-commit y no debe convertirse en uno: si la auditoria falla, la operacion no se
 * confirma.
 */
final class IdentityAuditEvents {

	/** Alta de una cuenta. {@code details.origen} distingue REGISTRO de INVITACION. */
	static final String CUENTA_CREADA = "CUENTA_CREADA";

	/** La persona confirmo el enlace y la cuenta quedo habilitada. */
	static final String CUENTA_ACTIVADA = "CUENTA_ACTIVADA";

	/** Login aceptado. */
	static final String LOGIN_EXITOSO = "LOGIN_EXITOSO";

	/** Credenciales rechazadas. */
	static final String LOGIN_FALLIDO = "LOGIN_FALLIDO";

	/**
	 * Contrasena correcta sobre una cuenta que no puede entrar.
	 *
	 * <p>Existe porque al cliente se le responde lo mismo que a una contrasena equivocada, y
	 * sin este evento no quedaria en ningun lado la diferencia entre "se equivoco" y "esta
	 * bloqueado y sigue intentando": la primera es ruido, la segunda es una senial.
	 */
	static final String LOGIN_RECHAZADO_ESTADO = "LOGIN_RECHAZADO_ESTADO";

	/** Fallos consecutivos por encima del umbral: alimenta la alerta al administrador. */
	static final String ACTIVIDAD_SOSPECHOSA = "ACTIVIDAD_SOSPECHOSA";

	/** Se pidio un restablecimiento de contrasena. */
	static final String RESET_SOLICITADO = "RESET_SOLICITADO";

	/** Se fijo una contrasena nueva por restablecimiento. */
	static final String RESET_COMPLETADO = "RESET_COMPLETADO";

	static final String CUENTA_BLOQUEADA = "CUENTA_BLOQUEADA";
	static final String CUENTA_DESBLOQUEADA = "CUENTA_DESBLOQUEADA";
	static final String CUENTA_DESACTIVADA = "CUENTA_DESACTIVADA";

	/** Entidad sobre la que recaen todos los eventos del modulo. */
	static final String ENTITY_CUENTA = "Cuenta";

	/** Clave con la que Micrometer Tracing publica el trace id del request en el MDC. */
	private static final String MDC_TRACE_ID = "traceId";

	private IdentityAuditEvents() {
		// Catalogo de constantes.
	}

	/**
	 * Trace id del request en curso (ADR-0005).
	 *
	 * <p>Se lee del MDC y no se recibe por parametro para que ningun servicio pueda olvidarse
	 * de propagarlo. {@code null} fuera de un request —un job, un test— es informacion valida.
	 */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}

	/**
	 * Registra un hecho sobre una cuenta.
	 *
	 * <p>{@code organizationId} va en {@code null} en casi todos los eventos de identidad y
	 * eso es correcto, no un olvido: autenticarse, activarse o resetear la contrasena son
	 * hechos de la persona, que es global (ADR-0009), y todavia no ocurren dentro de ningun
	 * tenant. Los eventos administrativos —bloquear, desactivar— si lo llevan, porque los
	 * ejecuta un administrador desde su organizacion.
	 */
	static void registrar(
			AuditTrail auditTrail,
			String eventType,
			Long organizationId,
			Long cuentaId,
			Long actorAccountId,
			String estadoAnterior,
			String estadoNuevo,
			Map<String, String> details,
			String motivo,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				organizationId,
				null,
				actorAccountId,
				eventType,
				ENTITY_CUENTA,
				cuentaId,
				estadoAnterior,
				estadoNuevo,
				details,
				motivo,
				correlationId(),
				ahora));
	}
}
