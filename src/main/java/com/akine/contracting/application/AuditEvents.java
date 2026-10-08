package com.akine.contracting.application;

import org.slf4j.MDC;

/**
 * Catalogo de los tipos de evento de auditoria que emite {@code contracting} (M15).
 *
 * <p>Es un catalogo por MODULO y no uno global: cada modulo tiene el suyo, con las mismas
 * constantes {@code static final String}, y unificarlos exigiria una clase compartida que ningun
 * modulo posee. La convencion —{@code ENTIDAD_VERBO_EN_PASADO}, mayusculas, sin acentos— si es
 * comun, y {@code audit_event.event_type} es un {@code VARCHAR(64)} sin lista cerrada justamente
 * para que cada modulo agregue los suyos sin migracion.
 *
 * <p><b>Toda operacion sensible se audita DENTRO de la transaccion del negocio</b>, nunca en un
 * listener post-commit: uno que falla deja la mutacion sin rastro, y {@code AuditTrail.record} es
 * {@code Propagation.MANDATORY} para que no exista la forma de equivocarse. El corolario
 * incomodo: <b>una excepcion de negocio hace rollback de todo lo escrito antes de lanzarla,
 * incluida la auditoria</b>. Por eso ningun rechazo se audita desde este modulo — el permiso
 * denegado lo escribe el evaluador de {@code organization} en su propia transaccion.
 */
final class AuditEvents {

	/** Alta de un financiador (RF-M15-001). */
	static final String FINANCIADOR_CREATED = "FINANCIADOR_CREATED";

	/** Edicion de nombre, tipo, CUIT o contacto (RF-M15-002). El codigo no: es inmutable. */
	static final String FINANCIADOR_UPDATED = "FINANCIADOR_UPDATED";

	/**
	 * Baja logica de un financiador (RF-M15-003). Motivo obligatorio.
	 *
	 * <p>El detalle lleva {@code planesActivos}: la baja no cascadea ni se bloquea por tener
	 * planes, pero el numero queda registrado para que seis meses despues se sepa cuanto
	 * arrastraba esa baja. Es el caso borde "baja con convenios" de la etapa.
	 */
	static final String FINANCIADOR_DEACTIVATED = "FINANCIADOR_DEACTIVATED";

	static final String ENTITY_FINANCIADOR = "Financiador";

	/** Alta de un plan bajo un financiador (RF-M15-004). */
	static final String PLAN_COBERTURA_CREATED = "PLAN_COBERTURA_CREATED";

	/** Edicion de un plan, incluido el cierre de vigencia (RF-M15-005). */
	static final String PLAN_COBERTURA_UPDATED = "PLAN_COBERTURA_UPDATED";

	/** Baja logica de un plan (RF-M15-005). Motivo obligatorio. */
	static final String PLAN_COBERTURA_DEACTIVATED = "PLAN_COBERTURA_DEACTIVATED";

	static final String ENTITY_PLAN = "PlanCobertura";

	// =================================================================================
	// M16 — convenios y aranceles (AKINE-03.05)
	// =================================================================================

	/** Alta de un convenio de una sede (RF-M16-001). */
	static final String CONVENIO_CREATED = "CONVENIO_CREATED";

	/**
	 * Edicion de un convenio, incluido el cierre de vigencia (RF-M16-002, RF-M16-003).
	 *
	 * <p>El detalle lleva el cambio de vigencia cuando lo hay, y eso es lo que hace auditable
	 * RF-M16-003: sin esa linea nadie podria responder desde cuando un convenio dejo de aplicarse,
	 * que es la pregunta que aparece cuando una presentacion vuelve rechazada.
	 */
	static final String CONVENIO_UPDATED = "CONVENIO_UPDATED";

	/**
	 * Baja logica de un convenio (RF-M16-003). Motivo obligatorio.
	 *
	 * <p>El detalle lleva {@code arancelesActivos}: la baja no cascadea ni se bloquea por tenerlos,
	 * pero el numero queda registrado para que seis meses despues se sepa cuanto arrastraba. Mismo
	 * criterio que la baja de un financiador en 03.03.
	 */
	static final String CONVENIO_DEACTIVATED = "CONVENIO_DEACTIVATED";

	static final String ENTITY_CONVENIO = "Convenio";

	/**
	 * Alta de un arancel bajo un convenio (RF-M16-004).
	 *
	 * <p>Los importes van en el detalle. §37 pide auditoria reforzada de importes y vigencias, y
	 * esta es la unica forma de reconstruir despues por que una prestacion se cobro lo que se
	 * cobro cuando el arancel de hoy dice otra cosa.
	 */
	static final String ARANCEL_CREATED = "ARANCEL_CREATED";

	/** Edicion de un arancel: importes o vigencia. El detalle lleva el antes y el despues. */
	static final String ARANCEL_UPDATED = "ARANCEL_UPDATED";

	/** Baja logica de un arancel. Motivo obligatorio. */
	static final String ARANCEL_DEACTIVATED = "ARANCEL_DEACTIVATED";

	static final String ENTITY_ARANCEL = "ConvenioArancel";

	/**
	 * Importacion masiva de aranceles confirmada (B-7, RF-M16-007). Un evento por lote, sobre el
	 * convenio, con la cantidad y el rango; cada fila deja ademas su {@link #ARANCEL_CREATED}.
	 * Una importacion rechazada no audita nada: no escribio nada.
	 */
	static final String ARANCELES_IMPORTADOS = "ARANCELES_IMPORTADOS";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
		// Catalogo de constantes.
	}

	/** Id de correlacion del request en curso, o {@code null} fuera de uno (jobs, tests). */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
