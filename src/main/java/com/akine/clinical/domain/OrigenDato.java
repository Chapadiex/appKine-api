package com.akine.clinical.domain;

/**
 * Por que camino entro un antecedente o un adjunto a la historia (DP-47).
 *
 * <p>No dice QUIEN lo cargo —eso es la columna de autor de cada tabla—: un profesional que carga
 * desde la pestaña de datos declarados produce un {@link #DECLARADO}, porque lo que cuenta el
 * paciente sigue siendo declarado aunque lo tipee un profesional.
 */
public enum OrigenDato {

	/** Lo cargo un profesional desde la historia. No lleva revision. */
	PROFESIONAL,

	/** Lo declaro el paciente y lo cargo el mostrador o el profesional. Espera revision. */
	DECLARADO
}
