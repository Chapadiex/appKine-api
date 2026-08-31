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

	static final String ENTITY_HISTORIA_CLINICA = "HistoriaClinica";
	static final String ENTITY_ANTECEDENTE = "AntecedenteClinico";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
	}

	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
