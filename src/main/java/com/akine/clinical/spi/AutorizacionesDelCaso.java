package com.akine.clinical.spi;

import java.util.Set;

/**
 * Que autorizaciones de M17 pertenecen a otro caso clinico (RF-M17-007, AKINE C-4).
 *
 * <p>Una autorizacion no tiene {@code caso_id}: queda atada a un caso cuando un item de un plan de
 * ese caso la vincula (RF-M11-007). RF-M17-007 pide "no reutilizar autorizacion de otro Caso", y
 * quien sabe que autorizacion esta atada a que caso es {@code clinical}.
 *
 * <p><b>Solo lectura y no autoriza nada</b>: quien llama ya resolvio pertenencia y permiso. No
 * devuelve nada clinico, solo ids de autorizacion.
 */
public interface AutorizacionesDelCaso {

	/**
	 * Las autorizaciones atadas a planes de OTROS casos de la misma historia clinica y a ninguno
	 * de {@code casoId}. Una atada a los dos no se devuelve: tambien es de este caso.
	 *
	 * @return vacio si el caso no existe, es de otro tenant, o no hay ninguna
	 */
	Set<Long> deOtrosCasos(long organizationId, long casoId);
}
