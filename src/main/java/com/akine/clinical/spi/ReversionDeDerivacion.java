package com.akine.clinical.spi;

/**
 * El pedido de deshacer un vinculo clinico.
 *
 * @param motivo          <b>obligatorio</b>. Deshacer un vinculo clinico sin decir por que no es
 *                        auditable, y lo que alguien va a querer leer despues no es "esta fila ya
 *                        no cuenta" sino "este paciente NO pertenece a este Caso, y aca esta quien
 *                        lo decidio"
 * @param origen          si viene, la derivacion tiene que colgar de esa participacion; si no, es
 *                        404 <b>antes</b> de evaluar el acceso y de tocar nada (AKINE-09.02)
 * @param participacionId la participacion de la que tiene que colgar, junto con {@code origen}
 */
public record ReversionDeDerivacion(
		ActorDeDerivacion actor, long derivacionId, String motivo,
		OrigenDeParticipacion origen, Long participacionId) {

	/** Sin acotar a una participacion: la derivacion se busca solo por organizacion. */
	public ReversionDeDerivacion(ActorDeDerivacion actor, long derivacionId, String motivo) {
		this(actor, derivacionId, motivo, null, null);
	}

	/** Si la derivacion pedida puede ser esta: sin acote, cualquiera de la organizacion. */
	public boolean admite(OrigenDeParticipacion origenDeLaFila, Long participacionDeLaFila) {
		if (origen == null || participacionId == null) {
			return true;
		}
		return origen == origenDeLaFila && participacionId.equals(participacionDeLaFila);
	}
}
