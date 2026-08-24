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

	/**
	 * Se volvio a emitir el enlace de activacion.
	 *
	 * <p>Solo se registra cuando realmente se emitio uno. El pedido sobre un email sin cuenta,
	 * o sobre una que no esta pendiente, no deja rastro a proposito: auditar el intento
	 * construiria en la tabla de auditoria el mismo indice de direcciones que el 202 uniforme
	 * existe para no entregar.
	 */
	static final String ACTIVACION_REENVIADA = "ACTIVACION_REENVIADA";

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

	/** Se emitio el par access + refresh de una sesion nueva. */
	static final String SESION_ABIERTA = "SESION_ABIERTA";

	/** Un refresh vigente se canjeo por un par nuevo. La rotacion quedo enlazada en la tabla. */
	static final String SESION_REFRESCADA = "SESION_REFRESCADA";

	/**
	 * Se presento un refresh ya rotado o ya revocado: hay una copia de la cadena dando vueltas.
	 *
	 * <p>Este evento es <b>lo unico</b> que distingue un robo de cookie de un token cualquiera
	 * que no sirve: al cliente se le responde el mismo 401 en los dos casos, para no confirmarle
	 * al atacante que su copia fue detectada. Sin este evento, la deteccion no quedaria en
	 * ningun lado y nadie podria investigar por que a un usuario se le cayo la sesion.
	 */
	static final String REFRESH_REUSO_DETECTADO = "REFRESH_REUSO_DETECTADO";

	/** La persona cerro la sesion en curso: se revoco la familia. */
	static final String SESION_CERRADA = "SESION_CERRADA";

	/** Se revocaron todas las sesiones vivas de la cuenta. */
	static final String SESIONES_CERRADAS = "SESIONES_CERRADAS";

	/**
	 * La persona eligio Organizacion mas Consultorio y se le emitio un access acotado.
	 *
	 * <p>AGENT.md sec. 6 lo exige explicitamente: el cambio de contexto no pide login nuevo,
	 * pero <b>queda auditado</b>. Es el registro de quien miro datos de que organizacion.
	 */
	static final String CONTEXTO_SELECCIONADO = "CONTEXTO_SELECCIONADO";

	/**
	 * Se pidio un contexto que la cuenta no tiene.
	 *
	 * <p>Al cliente se le responde 404, indistinguible de "no existe" (ADR-0019). Aca queda la
	 * diferencia: alguien que prueba ids de consultorio ajenos genera una racha de estos.
	 */
	static final String CONTEXTO_RECHAZADO = "CONTEXTO_RECHAZADO";

	/** Se pidio un restablecimiento de contrasena. */
	static final String RESET_SOLICITADO = "RESET_SOLICITADO";

	/** Se fijo una contrasena nueva por restablecimiento. */
	static final String RESET_COMPLETADO = "RESET_COMPLETADO";

	static final String CUENTA_BLOQUEADA = "CUENTA_BLOQUEADA";
	static final String CUENTA_DESBLOQUEADA = "CUENTA_DESBLOQUEADA";
	static final String CUENTA_DESACTIVADA = "CUENTA_DESACTIVADA";

	/**
	 * Se intento dar de alta un colaborador con un email que no tiene cuenta.
	 *
	 * <p><b>Es una de las dos mitigaciones obligatorias del 404 de ese endpoint</b>, no
	 * telemetria. El alta directa recibe un email y responde distinto segun exista la cuenta:
	 * eso lo convierte en un oraculo de enumeracion para quien tenga
	 * {@code colaborador:manage}. Sin este evento, un barrido de la base del SaaS no dejaria
	 * ningun rastro; con el, deja una racha del mismo actor en la auditoria DEL TENANT, que ya
	 * es consultable por actor (RF-M24-003).
	 *
	 * <p>Contrasta a proposito con {@link #ACTIVACION_REENVIADA}, que NO registra el intento
	 * sobre un email sin cuenta: aquel es un endpoint publico y anonimo, donde auditar el
	 * intento construiria el padron de direcciones que el 202 uniforme existe para no entregar.
	 * Este es autenticado y con permiso, asi que el actor ya esta identificado y lo unico que se
	 * registra son direcciones que <b>no</b> tienen cuenta.
	 */
	static final String MEMBERSHIP_ALTA_RECHAZADA = "MEMBERSHIP_ALTA_RECHAZADA";

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
	/**
	 * Registra el intento de alta directa sobre un email sin cuenta.
	 *
	 * <p>Tiene metodo propio y no reusa {@link #registrar} por dos diferencias que importan:
	 * lleva {@code consultorioId} —el alcance del vinculo que se pidio, que
	 * {@link #registrar} fija en {@code null} porque los eventos de cuenta no tienen sede— y
	 * apunta a {@code entityId = null}, porque el hecho es justamente que no hay fila a la que
	 * apuntar. La consulta que lo encuentra es la de actor (RF-M24-003), no la de entidad.
	 *
	 * @param details contexto del intento: email tipeado, rol y sede pedidos. <b>Nunca</b>
	 *                secretos: aca no viaja ninguno, el endpoint no recibe contrasenas
	 */
	static void registrarIntentoDeAlta(
			AuditTrail auditTrail,
			Long organizationId,
			Long consultorioId,
			Long actorAccountId,
			Map<String, String> details,
			String motivo,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actorAccountId,
				MEMBERSHIP_ALTA_RECHAZADA,
				ENTITY_CUENTA,
				null,
				null,
				null,
				details,
				motivo,
				correlationId(),
				ahora));
	}

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
