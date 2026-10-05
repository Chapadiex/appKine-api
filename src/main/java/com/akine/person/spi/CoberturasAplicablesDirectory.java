package com.akine.person.spi;

import java.time.LocalDate;
import java.util.List;

/**
 * Coberturas de una persona que aplican a una practica en una fecha.
 *
 * <p><b>Solo lectura y no autoriza nada.</b> Es una costura entre modulos: quien llama ya resolvio
 * pertenencia y permiso con su propio criterio. Misma advertencia que {@link PacienteDirectory}.
 *
 * <p>Nunca devuelve el numero de afiliado.
 */
public interface CoberturasAplicablesDirectory {

	/**
	 * Coberturas FINANCIADAS vigentes en {@code fecha} con arancel resuelto por convenio. La
	 * principal va primero; el resto por id ascendente. Nunca {@code null}; vacia = no aplica
	 * ninguna (Particular).
	 */
	List<CoberturaAplicable> aplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			LocalDate fecha);

	/**
	 * Coberturas FINANCIADAS vigentes en {@code fecha} que NO aplican por convenio, con motivo.
	 * Mismo orden que {@link #aplicables}.
	 */
	List<CoberturaNoAplicable> noAplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			LocalDate fecha);
}
