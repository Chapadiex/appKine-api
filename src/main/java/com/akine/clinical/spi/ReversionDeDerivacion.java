package com.akine.clinical.spi;

/**
 * El pedido de deshacer un vinculo clinico.
 *
 * @param motivo <b>obligatorio</b>. Deshacer un vinculo clinico sin decir por que no es auditable,
 *               y lo que alguien va a querer leer despues no es "esta fila ya no cuenta" sino
 *               "este paciente NO pertenece a este Caso, y aca esta quien lo decidio"
 */
public record ReversionDeDerivacion(
		ActorDeDerivacion actor, long derivacionId, String motivo) {
}
