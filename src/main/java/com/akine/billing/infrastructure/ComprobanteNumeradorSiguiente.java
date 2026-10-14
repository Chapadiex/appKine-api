package com.akine.billing.infrastructure;

/**
 * Fragmento de {@link ComprobanteNumeradorRepository}: el incremento que devuelve el numero.
 *
 * <p>Aparte porque Spring Data no expone el {@code last_insert_id} de un {@code UPDATE}. Ver
 * {@link ComprobanteNumeradorSiguienteImpl}.
 */
public interface ComprobanteNumeradorSiguiente {

	int siguiente(long organizationId, long consultorioId);
}
