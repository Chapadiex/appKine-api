package com.akine.encounter.application;

import org.slf4j.MDC;

/**
 * Catalogo de los tipos de evento de auditoria que emite {@code encounter} (M14).
 *
 * <p>Catalogo por MODULO, misma convencion que {@code person}, {@code organization},
 * {@code resource} y {@code offering}: {@code ENTIDAD_VERBO_EN_PASADO}, mayusculas, sin acentos, y
 * {@code audit_event.event_type} es un {@code VARCHAR(64)} sin lista cerrada para que cada modulo
 * agregue los suyos sin migracion.
 *
 * <h2>Por que esta clase aparece recien en 06.04, y que le agrego 06.06</h2>
 *
 * <p>{@code encounter} no auditaba nada: 06.01, 06.02 y 06.05 registran hechos clinicos cuyo
 * rastro queda en la propia fila —{@code iniciada_por_cuenta_id}, {@code cerrada_por_cuenta_id}—
 * y en el timeline. 06.04 es la primera etapa que necesita catalogo propio porque introduce una
 * <b>lectura</b> de contenido clinico que DP-03 obliga a auditar, y porque la baja de un
 * tratamiento lleva motivo y el motivo tiene que quedar en algun lado que no sea la fila borrada.
 *
 * <p>06.06 sumo la enmienda por la razon simetrica: es la <b>unica operacion del modulo que cambia
 * lo que un registro clinico ya cerrado dice</b>, y de ella no queda nada en {@code sesion} salvo
 * el contenido nuevo. El rastro tiene que ser explicito o RN-M14-006 —"no se modifica
 * silenciosamente"— queda escrito y no cumplido.
 *
 * <p><b>Toda operacion se audita DENTRO de la transaccion del negocio.</b> Es la regla que 01.01
 * dejo fijada: un listener posterior al commit que falla deja la mutacion sin rastro. El corolario
 * incomodo es que una excepcion de negocio hace rollback de la auditoria escrita antes de
 * lanzarla, asi que <b>ningun rechazo se audita desde aca</b> — el permiso denegado lo escribe el
 * evaluador de {@code organization} en su propia transaccion.
 *
 * <h2>Lo que NO va a la auditoria</h2>
 *
 * <p><b>Ningun detalle lleva contenido clinico.</b> Se auditan ids —sesion, practica, espacio— y
 * nunca la zona tratada, la tecnica ni la observacion, y tampoco el motivo de una enmienda, que es
 * prosa que el profesional escribe sobre un paciente. La auditoria se consulta con
 * {@code auditoria:read}, que <b>no es un permiso clinico</b>: una fila que reprodujera la zona
 * tratada —o el motivo— entregaria historia clinica a quien no tiene {@code hc:read}. Lo que se
 * audita es QUE cambio, no A QUE valor; el motivo vive en {@code sesion_version.motivo_enmienda},
 * detras del mismo permiso que el resto del registro. Es la misma decision que tomo
 * {@code clinical} en 04.02.
 */
public final class AuditEvents {

	/** Clave del trace id en el MDC, puesta por el filtro de correlacion de {@code platform}. */
	private static final String MDC_TRACE_ID = "traceId";

	// --- Sesion (AKINE-06.06 y 07.07) ------------------------------------------------------

	/**
	 * Tipo de entidad de los eventos de sesion de este modulo.
	 *
	 * <p><b>El nombre de la clase del agregado, en PascalCase</b>, que es la convencion que siguen
	 * los otros catalogos —{@code "Persona"}, {@code "CasoClinico"}, {@code "JornadaCaja"}—. 06.06
	 * lo habia escrito {@code "SESION"} y 07.07 {@code "Sesion"}: la integracion unifica en el
	 * segundo. Dos valores para la misma entidad parten el filtro de la auditoria en dos sin que
	 * nada falle, que es la peor forma de romperlo.
	 */
	public static final String ENTITY_SESION = "Sesion";

	/** Se inicio la atencion de un turno (RF-M14-001). */
	static final String SESION_INICIADA = "SESION_INICIADA";

	/** Alguien <b>leyo</b> una sesion: DP-03 no distingue entre leer y escribir en lo clinico. */
	static final String SESION_ACCEDIDA = "SESION_ACCEDIDA";

	/** Se cerro la atencion, con su numero de sesion (RF-M14-009). */
	static final String SESION_CERRADA = "SESION_CERRADA";

	/** Se escribio una version nueva de una sesion cerrada (RF-M14-010). */
	public static final String SESION_AMENDED = "SESION_AMENDED";

	// --- Tratamientos realizados (AKINE-06.04) ---------------------------------------------

	/** Entidad sobre la que recaen los eventos de tratamientos. */
	static final String ENTITY_TRATAMIENTO = "TRATAMIENTO_REALIZADO";

	/** Se asento una intervencion aplicada en la sesion (RF-M14-005). */
	static final String TRATAMIENTO_REGISTRADO = "TRATAMIENTO_REGISTRADO";

	/** Se reemplazo una intervencion, con sus parametros (RF-M14-005). */
	static final String TRATAMIENTO_MODIFICADO = "TRATAMIENTO_MODIFICADO";

	/**
	 * Baja logica de una intervencion, con motivo.
	 *
	 * <p>Tiene tipo propio y no es un {@code MODIFICADO} con un detalle: la pregunta "quien borro
	 * un tratamiento de esta sesion y por que" tiene que responderse filtrando por un tipo de
	 * evento, no leyendo los detalles de todas las ediciones.
	 */
	static final String TRATAMIENTO_DADO_DE_BAJA = "TRATAMIENTO_DADO_DE_BAJA";

	/**
	 * Alguien <b>leyo</b> los tratamientos de una sesion.
	 *
	 * <p>DP-03 no distingue entre leer y escribir en una historia clinica, y en lo clinico el
	 * riesgo esta mas del lado de quien lee sin motivo. Es la misma decision que 04.01 y 04.02
	 * tomaron para la HC y la entrada clinica.
	 */
	static final String TRATAMIENTO_CONSULTADO = "TRATAMIENTO_CONSULTADO";

	private AuditEvents() {
	}

	/**
	 * Correlaciona el evento con el log estructurado del request (ADR-0005).
	 *
	 * <p>Sale del MDC y puede ser {@code null} fuera de un request —un job, un test—, que es
	 * informacion legitima y no un descuido.
	 */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
