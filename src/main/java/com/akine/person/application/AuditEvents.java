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

	/**
	 * Baja logica de una Persona (RF-M07-005).
	 *
	 * <p>Tipo propio y no un {@code PERSONA_UPDATED}: la pregunta "quien dio de baja esta ficha y
	 * por que" tiene que responderse filtrando por tipo, no leyendo los detalles de todas las
	 * ediciones. Es ademas el unico evento de este modulo cuyo <b>motivo es obligatorio</b>.
	 */
	static final String PERSONA_DEACTIVATED = "PERSONA_DEACTIVATED";

	/** Baja logica del perfil clinico. La persona sigue existiendo y deja de ser paciente. */
	static final String PERFIL_PACIENTE_DEACTIVATED = "PERFIL_PACIENTE_DEACTIVATED";

	/** Carga de un documento administrativo (RF-M25-001). */
	static final String ADJUNTO_UPLOADED = "ADJUNTO_UPLOADED";

	/**
	 * Descarga del contenido de un adjunto (RF-M25-002).
	 *
	 * <p><b>Es el unico evento de LECTURA que emite este modulo</b>, y la asimetria es deliberada:
	 * el listado no entrega ningun contenido y auditarlo llenaria la tabla de ruido, mientras que
	 * una descarga es el instante en que un documento personal sale del sistema. La pregunta "quien
	 * se llevo el DNI de este paciente" no tiene otra forma de responderse.
	 */
	static final String ADJUNTO_DOWNLOADED = "ADJUNTO_DOWNLOADED";

	/** Reclasificacion de un adjunto: categoria o titulo (RF-M25-003). */
	static final String ADJUNTO_RECLASSIFIED = "ADJUNTO_RECLASSIFIED";

	/** Baja logica de un adjunto (RF-M25-004). El binario NO se borra. */
	static final String ADJUNTO_DEACTIVATED = "ADJUNTO_DEACTIVATED";

	static final String ENTITY_PERSONA = "Persona";

	static final String ENTITY_ADJUNTO = "AdjuntoAdministrativo";
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


	static final String ENTITY_COBERTURA = "CoberturaPaciente";

	// --- Ordenes, autorizaciones y documentacion administrativa (M17, AKINE-03.06) ---

	static final String ORDEN_CREATED = "ORDEN_MEDICA_CREATED";
	static final String ORDEN_UPDATED = "ORDEN_MEDICA_UPDATED";
	static final String ORDEN_DOCUMENTO_LINKED = "ORDEN_MEDICA_DOCUMENTO_LINKED";
	static final String ORDEN_DEACTIVATED = "ORDEN_MEDICA_DEACTIVATED";
	static final String ENTITY_ORDEN = "OrdenMedica";

	static final String AUTORIZACION_CREATED = "AUTORIZACION_CREATED";
	static final String AUTORIZACION_UPDATED = "AUTORIZACION_UPDATED";
	static final String AUTORIZACION_RESUELTA = "AUTORIZACION_RESUELTA";
	static final String AUTORIZACION_DOCUMENTO_LINKED = "AUTORIZACION_DOCUMENTO_LINKED";
	static final String AUTORIZACION_DEACTIVATED = "AUTORIZACION_DEACTIVATED";

	/**
	 * Se descontaron unidades al cerrar una sesion (RF-M17-004, AKINE-04.05).
	 *
	 * <p>Tipo propio y no un {@code AUTORIZACION_UPDATED}: la pregunta "que gasto las unidades de
	 * esta autorizacion" tiene que responderse filtrando por tipo de evento, no leyendo los
	 * detalles de todas las ediciones. Y es el unico evento de este modulo cuyo actor <b>puede</b>
	 * venir nulo, porque lo produce un hecho clinico y no un formulario.
	 *
	 * <p><b>Un consumo IDEMPOTENTE no lo emite</b>: si el movimiento ya existia, no ocurrio ningun
	 * hecho nuevo. Mismo criterio que la activacion de perfil de paciente.
	 */
	static final String AUTORIZACION_CONSUMIDA = "AUTORIZACION_CONSUMIDA";

	/**
	 * Se compenso un consumo con una reversion (RF-M17-005).
	 *
	 * <p>Es el evento con <b>motivo obligatorio</b> de esta etapa. Devolver saldo cambia lo que el
	 * centro le va a presentar al financiador, y sin motivo quien audita no puede distinguir un
	 * error de carga de un fraude.
	 */
	static final String AUTORIZACION_CONSUMO_REVERTIDO = "AUTORIZACION_CONSUMO_REVERTIDO";

	/**
	 * Se anulo la obligacion de una sesion que consumio esta autorizacion, y el consumo quedo
	 * marcado para revisar (DP-13, RN-M17-003, AKINE C-4). <b>No movio el saldo.</b> Va en la
	 * transaccion de la anulacion.
	 */
	static final String AUTORIZACION_CONSUMO_A_REVISAR = "AUTORIZACION_CONSUMO_A_REVISAR";

	static final String ENTITY_AUTORIZACION = "Autorizacion";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
		// Catalogo de constantes.
	}

	/** Id de correlacion del request en curso, o {@code null} fuera de uno (jobs, tests). */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
