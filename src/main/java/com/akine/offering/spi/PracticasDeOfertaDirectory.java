package com.akine.offering.spi;

import java.util.List;
import java.util.Optional;

/**
 * Que practicas puede prestar una oferta, para quien devenga o consume (A-9, DP-11).
 *
 * <p>Consumidores previstos: {@code billing} al devengar la obligacion del financiador (F-4) y
 * {@code person} al consumir autorizaciones (C-4). La regla que tienen que aplicar, de DP-11:
 *
 * <pre>
 *   practicas = sesion.practicasRealizadas()
 *   si esta vacia: practicas = practicaPrincipal(...)            // defecto de la oferta
 *   por cada practica realizada que no este en practicasHabilitadas(...): ALERTA, nunca rechazo
 * </pre>
 *
 * <h2>Lectura viva, no copia</h2>
 *
 * <p>Devuelve la configuracion ACTUAL de la oferta (el criterio de 03.03 para {@code contracting}):
 * sirve para decidir. Quien necesita fijar que se devengo copia el id en su propio hecho; volver a
 * preguntar mas tarde puede dar otra respuesta si el centro reconfiguro la oferta.
 *
 * <h2>Alcance</h2>
 *
 * <p>La oferta se resuelve primero con organizacion <b>y</b> sede en el {@code WHERE}: una oferta de
 * otro tenant o de otra sede responde vacio, nunca una excepcion. Solo filas <b>activas</b>. <b>No
 * filtra por el estado de la oferta</b>: una sesion de una oferta dada de baja ayer todavia tiene
 * que poder devengarse con su principal.
 */
public interface PracticasDeOfertaDirectory {

	/** Las practicas activas de la oferta, en orden de alta. Vacia si la oferta no declara ninguna. */
	List<PracticaDeOferta> practicasHabilitadas(long organizationId, long consultorioId, long ofertaId);

	/** La practica principal vigente, o vacio si la oferta no declara practicas. */
	Optional<Long> practicaPrincipal(long organizationId, long consultorioId, long ofertaId);
}
