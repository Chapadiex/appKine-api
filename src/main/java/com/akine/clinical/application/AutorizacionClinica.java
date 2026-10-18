package com.akine.clinical.application;

import com.akine.clinical.domain.exception.AccesoClinicoNoJustificadoException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.audit.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Map;

/**
 * La politica de acceso clinico de DP-03, en un solo lugar.
 *
 * <h2>Las tres condiciones, y por que ninguna alcanza sola</h2>
 *
 * <p>DP-03 dice que la Historia Clinica puede consultarse desde distintos Consultorios de la misma
 * Organizacion <b>unicamente</b> por actores con membership vigente, permiso clinico y relacion
 * asistencial o justificacion autorizada. Las tres se evaluan aca y en este orden:
 *
 * <ol>
 *   <li><b>Contexto.</b> Sin organizacion y sede seleccionadas no hay nada que evaluar. Se rechaza
 *       con {@link AccessDeniedException} —403, nunca 401: la regla que 01.01 dejo fijada, porque
 *       el interceptor del frontend borra el token ante cualquier 401 y entra en bucle de login.
 *       La <b>sede</b> es obligatoria aunque la historia sea de la organizacion: sin ella no se
 *       puede preguntar por relacion asistencial ni registrar desde donde se accedio.</li>
 *   <li><b>Permiso y membership.</b> Lo resuelve {@code PermissionGuard}, que revalida la
 *       membership <b>contra la base</b> y no contra el token: un token acotado al contexto puede
 *       portar un rol ya revocado, y ese es el caso borde "membership vencida" de la etapa.</li>
 *   <li><b>Relacion asistencial o justificacion.</b> El permiso solo no alcanza. Si alcanzara,
 *       cualquier profesional del centro podria leer la historia de cualquier paciente sin dejar
 *       mas rastro que un evento de lectura mas — que es exactamente lo que DP-03 evita.</li>
 * </ol>
 *
 * <h2>Que significa hoy la tercera condicion</h2>
 *
 * <p>{@link RelacionAsistencialProbe} no tiene implementacion real hasta que existan turnos y
 * sesiones, asi que <b>hoy responde siempre "sin evidencia" y todo acceso clinico exige
 * justificacion declarada</b>. Es mas friccion de la que va a haber despues, y es el lado seguro
 * del error: conceder por defecto mientras no hay con que verificar dejaria el sistema abierto
 * justo en el periodo en que nadie lo mira.
 *
 * <p>La justificacion no se valida contra ningun catalogo. No hay catalogo que validar —"acceso de
 * emergencia autorizado" es un caso borde declarado de la etapa y no un codigo— y un texto libre
 * obligatorio y auditado es lo que permite revisarlo despues. Lo unico que se exige es que no
 * este vacio.
 *
 * <h2>Por que es una clase estatica y no un servicio</h2>
 *
 * <p>No tiene estado ni transaccion propia, y que sea invocable desde cada servicio con sus
 * dependencias explicitas hace imposible olvidarse de ella: un servicio clinico que no llame a
 * {@link #exigir} no compila contra {@link AccesoClinico}, que es lo que necesita para auditar.
 * Mismo patron que {@code person.application.AutorizacionDePadron}.
 */
final class AutorizacionClinica {

	private static final Logger log = LoggerFactory.getLogger(AutorizacionClinica.class);

	/** El unico motivo que hoy se audita desde aca. "Sin permiso" lo audita organization. */
	static final String MOTIVO_SIN_JUSTIFICACION = "SIN_JUSTIFICACION";

	private AutorizacionClinica() {
	}

	/**
	 * Evalua las tres condiciones y devuelve con que se concedio, para que el llamador lo audite.
	 *
	 * @param permissionCode {@code hc:read} para consultar, {@code hc:write} para escribir
	 * @param justificacion  motivo declarado por el actor. Se ignora si hay relacion asistencial;
	 *                       se exige si no la hay
	 * @param rechazos       donde se audita el rechazo por falta de motivo, aparte de la
	 *                       transaccion del pedido
	 * @throws AccessDeniedException                 sin contexto de trabajo activo
	 * @throws AccesoClinicoNoJustificadoException   con permiso, pero sin relacion asistencial ni
	 *                                               justificacion. Antes de lanzarla deja
	 *                                               {@code HISTORIA_CLINICA_ACCESS_DENIED}
	 */
	static AccesoClinico exigir(
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			ClinicalSupportAccessAuditor rechazos,
			OperatingActor actor,
			String permissionCode,
			long personaId,
			String justificacion,
			String operacion) {

		exigirContexto(actor, operacion);

		long organizationId = actor.contextOrganizationId();
		long consultorioId = actor.consultorioId();

		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				permissionCode,
				organizationId,
				consultorioId,
				null,
				Instant.now()));

		boolean conRelacion = relaciones.tieneRelacionAsistencial(
				organizationId, consultorioId, actor.accountId(), personaId);
		String motivo = vacioEsNulo(justificacion);

		if (!conRelacion && motivo == null) {
			// El rechazo SE AUDITA (pedido de appKine-web #83, DP-03: todo intento de acceso
			// clinico deja rastro). Aparte y despues de la transaccion, porque la del pedido
			// revierte con esta excepcion y se llevaria la fila puesta. Lo de "sin permiso" no se
			// registra aca: ya lo deja PERMISSION_DENIED el evaluador de organization, adentro de
			// requirePermission. Sin justificacion que guardar: es justamente lo que falto.
			log.info("{} sin relacion asistencial ni justificacion: accountId={} personaId={}",
					operacion, actor.accountId(), personaId);
			rechazos.registrarRechazo(new AuditEntry(
					organizationId,
					consultorioId,
					actor.accountId(),
					AuditEvents.HISTORIA_CLINICA_ACCESS_DENIED,
					AuditEvents.ENTITY_PERSONA,
					personaId,
					null,
					null,
					Map.of("operacion", operacion,
							"permissionCode", permissionCode,
							"motivoDelRechazo", MOTIVO_SIN_JUSTIFICACION,
							"viaSupportAccess", String.valueOf(decision.viaSupportAccess())),
					null,
					AuditEvents.correlationId(),
					Instant.now()));
			throw new AccesoClinicoNoJustificadoException(personaId);
		}

		return new AccesoClinico(decision, conRelacion, conRelacion ? null : motivo);
	}

	/**
	 * {@link #exigir} para una persona que llega <b>por id pelado</b> —de la ruta— y no de una
	 * fila ya leida dentro de la organizacion.
	 *
	 * <p><b>Pertenencia antes que permiso</b> (threat model §2.4). Evaluar el permiso primero hacia
	 * que una persona de OTRA organizacion se respondiera 403 a quien no tiene {@code hc:read} en
	 * la suya, contra la regla de 01.01: fuera de alcance es 404, porque un 403 dice que hay algo
	 * ahi. Una persona que no es del padron de la organizacion del contexto se responde igual que
	 * una historia inexistente, y no deja fila de auditoria: no se accedio a nada.
	 *
	 * <p>Cuando la persona sale de una historia, un caso o una entrada ya leidos con la
	 * organizacion en el filtro, ese 404 ya ocurrio antes: por eso esos llamadores siguen con
	 * {@link #exigir}.
	 *
	 * @throws HistoriaClinicaNotAccessibleException si la persona no es de la organizacion del
	 *                                               contexto (404)
	 */
	@SuppressWarnings("java:S107")
	static AccesoClinico exigirSobrePersona(
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			ClinicalSupportAccessAuditor rechazos,
			PacienteDirectory pacientes,
			OperatingActor actor,
			String permissionCode,
			long personaId,
			String justificacion,
			String operacion) {

		exigirContexto(actor, operacion);
		if (pacientes.find(actor.contextOrganizationId(), personaId).isEmpty()) {
			log.info("{} sobre una persona fuera de la organizacion: accountId={} personaId={}",
					operacion, actor.accountId(), personaId);
			throw new HistoriaClinicaNotAccessibleException(personaId);
		}
		// La pertenencia va ANTES que todo lo demas, tambien antes de auditar un rechazo por falta
		// de motivo: una fila HISTORIA_CLINICA_ACCESS_DENIED con el id de una persona ajena
		// confirmaria que existe.
		return exigir(permissionGuard, relaciones, rechazos, actor, permissionCode, personaId,
				justificacion, operacion);
	}

	private static void exigirContexto(OperatingActor actor, String operacion) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}
}
