package com.akine.offering.application;

import org.slf4j.MDC;

/**
 * Catalogo de los tipos de evento de auditoria que emite {@code offering} (M27).
 *
 * <p>Es un catalogo por MODULO y no uno global: {@code organization} y {@code resource} tienen el
 * suyo, con las mismas constantes {@code static final String}, y unificarlos exigiria una clase
 * compartida que ningun modulo posee. La convencion —{@code ENTIDAD_VERBO_EN_PASADO}, mayusculas,
 * sin acentos— si es comun, y {@code audit_event.event_type} es un {@code VARCHAR(64)} sin lista
 * cerrada justamente para que cada modulo agregue los suyos sin migracion.
 *
 * <p><b>Toda operacion sensible se audita DENTRO de la transaccion del negocio</b>, nunca en un
 * listener post-commit: uno que falla deja la mutacion sin rastro, y {@code AuditTrail.record} es
 * {@code Propagation.MANDATORY} precisamente para que no exista la forma de equivocarse. El
 * corolario incomodo, que ya costo caro en 01.03: <b>una excepcion de negocio hace rollback de
 * todo lo escrito antes de lanzarla, incluida la auditoria</b>. Por eso ningun rechazo se audita
 * desde este modulo: el permiso denegado lo escribe el evaluador de {@code organization} en su
 * propia transaccion.
 */
final class AuditEvents {

	// --- Catalogo global de Servicios (M27/M06, AKINE-02.06) ----------------------------

	/** Alta de un Servicio en el catalogo global (RF-M27-001). */
	static final String SERVICIO_CREATED = "SERVICIO_CREATED";

	/** Edicion de nombre, descripcion, naturaleza o defaults (RF-M27-001). El codigo no. */
	static final String SERVICIO_UPDATED = "SERVICIO_UPDATED";

	/**
	 * Baja logica de un Servicio (RF-M27-002). Motivo obligatorio.
	 *
	 * <p>{@code previousState}/{@code newState} llevan el estado DERIVADO —{@code ACTIVO} /
	 * {@code INACTIVO}—, que no existe como columna: ver {@code Servicio}.
	 */
	static final String SERVICIO_DEACTIVATED = "SERVICIO_DEACTIVATED";

	static final String ENTITY_SERVICIO = "Servicio";

	// --- Ofertas de servicio por consultorio (M27/M03, AKINE-02.06) ---------------------

	/**
	 * Alta de una Oferta en una sede (RF-M03-006, RF-M27-003).
	 *
	 * <p><b>Estas tres filas SI llevan {@code organizationId} y {@code consultorioId}</b>, al
	 * reves que las tres de arriba. No es una inconsistencia: un Servicio es global y atribuirle
	 * a un centro un cambio del catalogo comun seria una linea de auditoria falsa, mientras que
	 * una Oferta es configuracion comercial de una sede concreta y omitir su tenant dejaria un
	 * evento que nadie puede atribuir ni recuperar en la consulta de auditoria del centro, que
	 * filtra por organizacion.
	 */
	static final String OFERTA_CREATED = "OFERTA_CREATED";

	/** Edicion de la configuracion de una Oferta. Ni el servicio ni la sede: son inmutables. */
	static final String OFERTA_UPDATED = "OFERTA_UPDATED";

	/** Baja logica de una Oferta (RN-M27-007). Motivo obligatorio. */
	static final String OFERTA_DEACTIVATED = "OFERTA_DEACTIVATED";

	/** Cambio de la politica de prepago de una Oferta (AKINE E-6, DP-06). */
	static final String OFERTA_POLITICA_PREPAGO_CHANGED = "OFERTA_POLITICA_PREPAGO_CHANGED";

	static final String ENTITY_OFERTA = "OfertaServicioConsultorio";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
		// Catalogo de constantes.
	}

	/** Id de correlacion del request en curso, o {@code null} fuera de uno (jobs, tests). */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}

	// --- Habilitaciones de Oferta (M27/M04/M05, AKINE-02.07) ----------------------------

	static final String HABILITACION_PROFESIONAL_GRANTED = "HABILITACION_PROFESIONAL_GRANTED";

	static final String HABILITACION_PROFESIONAL_REVOKED = "HABILITACION_PROFESIONAL_REVOKED";

	static final String HABILITACION_ESPACIO_GRANTED = "HABILITACION_ESPACIO_GRANTED";

	static final String HABILITACION_ESPACIO_REVOKED = "HABILITACION_ESPACIO_REVOKED";

	// --- Practicas de Oferta (A-9, DP-11) -------------------------------------------------
	// Misma entidad auditada que las habilitaciones: la OFERTA. Detalle con el practicaId.

	static final String OFERTA_PRACTICA_ADDED = "OFERTA_PRACTICA_ADDED";

	static final String OFERTA_PRACTICA_REMOVED = "OFERTA_PRACTICA_REMOVED";

	/** Cambio de la practica principal; el detalle lleva {@code anterior} y {@code nueva}. */
	static final String OFERTA_PRACTICA_PRINCIPAL_CHANGED = "OFERTA_PRACTICA_PRINCIPAL_CHANGED";

	// --- Precio particular por vigencia (B-3, RF-M16-009) --------------------------------
	// Entidad auditada: la OFERTA. Detalle con el precioId, el importe y el periodo.

	static final String OFERTA_PRECIO_PARTICULAR_CREATED = "OFERTA_PRECIO_PARTICULAR_CREATED";

	static final String OFERTA_PRECIO_PARTICULAR_UPDATED = "OFERTA_PRECIO_PARTICULAR_UPDATED";

	static final String OFERTA_PRECIO_PARTICULAR_DEACTIVATED =
			"OFERTA_PRECIO_PARTICULAR_DEACTIVATED";

	/**
	 * La entidad auditada es la OFERTA, no la fila de habilitacion.
	 *
	 * <p>A quien le importa el rastro es a quien pregunta "quien podia prestar esta oferta en
	 * marzo": esa pregunta se hace sobre la oferta. Auditar contra el id de una fila que ademas
	 * puede haberse dado de baja obligaria a reconstruir la cadena para responderla.
	 */
	static final String ENTITY_HABILITACION = "Oferta";
}
