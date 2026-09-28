package com.akine.clinical.application;

import org.slf4j.MDC;

/**
 * Los tipos de evento que este modulo escribe en la auditoria.
 *
 * <p><b>La lectura tambien se audita, y ese es el punto.</b> En el resto del sistema solo se
 * auditan las mutaciones; aca no alcanza. DP-03 exige que <i>todo acceso clinico sensible</i>
 * quede registrado, y la mayor parte del riesgo de una historia clinica no esta en que alguien la
 * modifique sino en que alguien la lea sin motivo.
 */
final class AuditEvents {

	static final String HISTORIA_CLINICA_OPENED = "HISTORIA_CLINICA_OPENED";
	static final String HISTORIA_CLINICA_ACCESSED = "HISTORIA_CLINICA_ACCESSED";
	static final String HISTORIA_CLINICA_RESUMEN_UPDATED = "HISTORIA_CLINICA_RESUMEN_UPDATED";
	static final String ANTECEDENTE_REGISTERED = "ANTECEDENTE_CLINICO_REGISTERED";
	static final String ANTECEDENTE_DEACTIVATED = "ANTECEDENTE_CLINICO_DEACTIVATED";

	static final String ENTRADA_CLINICA_REGISTERED = "ENTRADA_CLINICA_REGISTERED";
	static final String ENTRADA_CLINICA_AMENDED = "ENTRADA_CLINICA_AMENDED";
	static final String ENTRADA_CLINICA_DEACTIVATED = "ENTRADA_CLINICA_DEACTIVATED";

	static final String ADJUNTO_CLINICO_UPLOADED = "ADJUNTO_CLINICO_UPLOADED";

	/**
	 * La descarga de un adjunto clinico.
	 *
	 * <p><b>Es el evento que justifica la etapa entera desde el lado de seguridad.</b> Un estudio
	 * descargado y reenviado es la fuga mas barata que tiene un sistema clinico, y sin este evento
	 * no hay forma de revisarla despues. No es opcional ni configurable.
	 */
	static final String ADJUNTO_CLINICO_DOWNLOADED = "ADJUNTO_CLINICO_DOWNLOADED";

	static final String ADJUNTO_CLINICO_RECLASSIFIED = "ADJUNTO_CLINICO_RECLASSIFIED";
	static final String ADJUNTO_CLINICO_DEACTIVATED = "ADJUNTO_CLINICO_DEACTIVATED";

	/**
	 * La lectura del timeline de una historia.
	 *
	 * <p>Se audita por lo mismo que la lectura de la historia: el timeline le muestra a quien lo
	 * abre <b>cuantos</b> hechos clinicos tiene un paciente y de que clase, que ya es informacion
	 * sensible aunque no traiga contenido. DP-03 no distingue.
	 */
	static final String TIMELINE_ACCESSED = "TIMELINE_ACCESSED";

	static final String CASO_CLINICO_OPENED = "CASO_CLINICO_OPENED";
	static final String CASO_CLINICO_UPDATED = "CASO_CLINICO_UPDATED";
	static final String CASO_CLINICO_CLOSED = "CASO_CLINICO_CLOSED";
	static final String CASO_CLINICO_REOPENED = "CASO_CLINICO_REOPENED";
	static final String CASO_EQUIPO_CHANGED = "CASO_EQUIPO_CHANGED";

	/**
	 * La lectura de un caso, de la lista de casos o de su historial.
	 *
	 * <p>Se audita por lo mismo que la lectura del timeline: la lista de casos de un paciente dice
	 * <b>que problemas tiene</b> —el diagnostico presuntivo es contenido clinico y viaja en la
	 * ficha—, y DP-03 no distingue entre leer y escribir en una historia clinica. El alcance
	 * concreto viaja en los detalles.
	 */
	static final String CASO_CLINICO_ACCESSED = "CASO_CLINICO_ACCESSED";

	static final String PLAN_TRATAMIENTO_CREATED = "PLAN_TRATAMIENTO_CREATED";
	static final String PLAN_TRATAMIENTO_UPDATED = "PLAN_TRATAMIENTO_UPDATED";
	static final String PLAN_TRATAMIENTO_ACTIVATED = "PLAN_TRATAMIENTO_ACTIVATED";
	static final String PLAN_TRATAMIENTO_AMENDED = "PLAN_TRATAMIENTO_AMENDED";
	static final String PLAN_TRATAMIENTO_SUSPENDED = "PLAN_TRATAMIENTO_SUSPENDED";
	static final String PLAN_TRATAMIENTO_RESUMED = "PLAN_TRATAMIENTO_RESUMED";
	static final String PLAN_TRATAMIENTO_FINALIZED = "PLAN_TRATAMIENTO_FINALIZED";

	/**
	 * Un item del plan quedo atado a una Autorizacion real de M17 (RF-M11-007, AKINE-04.05).
	 *
	 * <p>Tipo propio y no un {@code PLAN_TRATAMIENTO_UPDATED}: la pregunta "quien ato este plan a
	 * esa autorizacion" se responde filtrando por tipo de evento.
	 *
	 * <p><b>Este hecho NO deja {@code plan_evento}, y por eso este evento importa mas de lo
	 * habitual.</b> {@code plan_evento} registra estados del plan y su vocabulario esta cerrado por
	 * el CHECK de {@code V49}; atar una autorizacion no es una transicion de estado y meterlo
	 * dentro de {@code EDICION} haria que el historial afirme que alguien edito el contenido
	 * clinico, que es falso. La trazabilidad vive aca, en el {@code autorizacion_id} del item, y en
	 * el ledger de M17.
	 */
	static final String PLAN_ITEM_AUTORIZACION_LINKED = "PLAN_ITEM_AUTORIZACION_LINKED";

	/**
	 * La lectura de un plan, de la lista de planes, de su historico de versiones o de su avance.
	 *
	 * <p>Se audita por lo mismo que la lectura de un caso: el plan dice <b>que se le esta haciendo
	 * al paciente y por cuanto tiempo</b>, y sus objetivos son contenido clinico. DP-03 no distingue
	 * entre leer y escribir en una historia clinica. El alcance concreto —PLAN, PLANES, VERSIONES o
	 * AVANCE— viaja en los detalles, para que quien audite despues pueda separar quien abrio la
	 * ficha de quien recorrio el historico.
	 */
	static final String PLAN_TRATAMIENTO_ACCESSED = "PLAN_TRATAMIENTO_ACCESSED";

	/** AKINE-08.04. Una participacion grupal quedo vinculada a un Caso Clinico. */
	static final String DERIVACION_CLINICA_CREATED = "DERIVACION_CLINICA_CREATED";

	/** AKINE-08.04. El vinculo se deshizo, con motivo. La fila queda. */
	static final String DERIVACION_CLINICA_REVERTED = "DERIVACION_CLINICA_REVERTED";

	/**
	 * AKINE-08.04. Alguien consulto a que Casos fue derivada una participacion.
	 *
	 * <p>Es una LECTURA y se audita igual: 04.01 fijo que toda lectura clinica deja rastro, y esta
	 * dice quien tiene historia abierta — dato del que despues hay que poder responder.
	 */
	static final String DERIVACION_CLINICA_ACCESSED = "DERIVACION_CLINICA_ACCESSED";

	static final String ENTITY_HISTORIA_CLINICA = "HistoriaClinica";
	static final String ENTITY_PLAN_TRATAMIENTO = "PlanTratamiento";
	static final String ENTITY_CASO_CLINICO = "CasoClinico";
	static final String ENTITY_ANTECEDENTE = "AntecedenteClinico";
	static final String ENTITY_ENTRADA_CLINICA = "EntradaClinica";
	static final String ENTITY_ADJUNTO_CLINICO = "AdjuntoClinico";
	static final String ENTITY_DERIVACION_CLINICA = "DerivacionClinica";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
	}

	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
