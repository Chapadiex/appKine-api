package com.akine.scheduling.spi;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * Pregunta si la recepcion de un turno ya tomo el prepago (AKINE E-6, DP-06 / ADR-0013).
 *
 * <h2>Por que la interfaz vive aca y no en {@code billing}</h2>
 *
 * <p>El prepago es un anticipo de M19, y M19 es de {@code billing}. Pero {@code billing} ya
 * depende de {@code scheduling} de forma transitiva ({@code billing -> encounter -> scheduling}):
 * si {@code scheduling} importara {@code billing} para hacer esta pregunta se cerraria un ciclo y
 * ArchUnit rechazaria el build. La salida es la de siempre en este proyecto: el que pregunta
 * declara el contrato y el que sabe lo implementa. Mismo patron que {@link AtencionProbe}.
 *
 * <h2>Que garantiza y que no</h2>
 *
 * <p><b>No autoriza nada</b> y es una <b>foto</b>: el anticipo pudo anularse un instante despues.
 * Alcanza para lo unico que la recepcion hace con la respuesta, que es mostrar si el prepago esta
 * pendiente. Ninguna transicion de la recepcion depende de esto (DP-06: el prepago alerta, nunca
 * bloquea).
 */
public interface PrepagoDeTurnoProbe {

	/**
	 * Los prepagos VIGENTES (no anulados) de esos turnos, por id de turno. Un turno sin prepago
	 * no aparece en el mapa. Una sola consulta para todo el lote: la agenda del dia la usa.
	 */
	Map<Long, PrepagoDeTurno> prepagosDe(long organizationId, Collection<Long> turnoIds);

	/**
	 * Un anticipo tomado en la recepcion de un turno.
	 *
	 * @param importe     lo que se cobro
	 * @param saldoAFavor lo que todavia no se imputo ni se reintegro
	 */
	record PrepagoDeTurno(
			long turnoId, long cobroId, BigDecimal importe, BigDecimal saldoAFavor, String moneda) {
	}
}
