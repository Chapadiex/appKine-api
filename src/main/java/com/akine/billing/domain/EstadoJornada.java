package com.akine.billing.domain;

/**
 * En que punto de su vida esta un turno de caja.
 *
 * <p><b>Dos valores y no tres.</b> No existe REABIERTA ni ANULADA: RN-M20-003 dice que una caja
 * cerrada no se edita en silencio, y la regla maestra 10 que la informacion historica no se
 * elimina. Un cierre equivocado no se deshace — se compensa con movimientos en la jornada que este
 * abierta hoy, que es lo que {@link TipoMovimiento#REVERSION_DE_INGRESO} existe para hacer.
 */
public enum EstadoJornada {

	/** Admite movimientos. A lo sumo una por sede, y lo hace cumplir un unique de la base. */
	ABIERTA,

	/** Arqueada. No admite movimientos nuevos, no se edita y no se reabre. */
	CERRADA
}
