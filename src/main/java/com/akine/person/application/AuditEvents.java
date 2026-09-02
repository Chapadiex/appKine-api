package com.akine.person.application;

import org.slf4j.MDC;

/**
 * Catalogo de los tipos de evento de auditoria que emite {@code person} (M07).
 *
 * <p>Catalogo por MODULO, misma convencion que {@code organization}, {@code resource} y
 * {@code offering}: {@code ENTIDAD_VERBO_EN_PASADO}, mayusculas, sin acentos, y
 * {@code audit_event.event_type} es un {@code VARCHAR(64)} sin lista cerrada para que cada modulo
 * agregue los suyos sin migracion.
 *
 * <p><b>Toda operacion se audita DENTRO de la transaccion del negocio.</b> El corolario incomodo,
 * que ya costo caro en 01.03: una excepcion de negocio hace rollback de todo lo escrito antes de
 * lanzarla, incluida la auditoria. Por eso ningun rechazo se audita desde este modulo — el
 * permiso denegado lo escribe el evaluador de {@code organization} en su propia transaccion.
 *
 * <p><b>Ningun detalle de auditoria lleva el documento, el telefono ni el email.</b> Es PII y la
 * auditoria es una tabla que se consulta con permisos distintos a los del padron: RNF-M07-001 y
 * las reglas de §35 piden minimo privilegio, y una fila de auditoria que reproduce el DNI lo
 * entrega a quien tiene {@code auditoria:read} y no {@code paciente:manage}. Lo que se audita es
 * QUE cambio, no A QUE valor.
 */
final class AuditEvents {

	/** Alta de una Persona (RF-M07-002, RF-M07-007). */
	static final String PERSONA_CREATED = "PERSONA_CREATED";

	/** Edicion de datos administrativos o de contacto (RF-M07-003). */
	static final String PERSONA_UPDATED = "PERSONA_UPDATED";

	/**
	 * Activacion del perfil clinico sobre una Persona existente (RF-M07-008).
	 *
	 * <p>Es el evento mas importante de este modulo y por eso tiene tipo propio en vez de ser un
	 * {@code PERSONA_UPDATED} con un detalle: es el instante en que alguien pasa a ser paciente,
	 * y la pregunta "quien la convirtio en paciente y cuando" tiene que responderse filtrando por
	 * un tipo de evento, no leyendo los detalles de todas las ediciones.
	 *
	 * <p><b>Una activacion IDEMPOTENTE no emite este evento</b>: si el perfil ya existia, no
	 * ocurrio ningun hecho nuevo y registrarlo llenaria la auditoria de activaciones que nunca
	 * pasaron.
	 */
	static final String PERFIL_PACIENTE_ACTIVATED = "PERFIL_PACIENTE_ACTIVATED";

	/** Alta de una cobertura del paciente (RF-M08-001, AKINE-03.04). */
	static final String COBERTURA_CREATED = "COBERTURA_CREATED";

	/** Edicion de los datos no historicos de una cobertura (RF-M08-002). */
	static final String COBERTURA_UPDATED = "COBERTURA_UPDATED";

	/**
	 * Cierre de la vigencia de una cobertura (RF-M08-003). Tipo propio y no un UPDATED.
	 *
	 * <p>"Desde cuando el paciente dejo de tener esa obra social" es una pregunta que se responde
	 * filtrando por tipo de evento; enterrarla en los detalles de una edicion la volveria una
	 * busqueda de texto sobre el detalle de todas las ediciones del padron.
	 */
	static final String COBERTURA_VIGENCIA_FINALIZADA = "COBERTURA_VIGENCIA_FINALIZADA";

	/** Cambio de la cobertura principal del paciente (RF-M08-004). */
	static final String COBERTURA_PRINCIPAL_CHANGED = "COBERTURA_PRINCIPAL_CHANGED";

	/** Baja logica de una cobertura. No borra: la cobertura sigue siendo legible (RN-M08-003). */
	static final String COBERTURA_DEACTIVATED = "COBERTURA_DEACTIVATED";

	static final String ENTITY_PERSONA = "Persona";

	static final String ENTITY_COBERTURA = "CoberturaPaciente";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
		// Catalogo de constantes.
	}

	/** Id de correlacion del request en curso, o {@code null} fuera de uno (jobs, tests). */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
