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
 *
 * <h2>Con oferta o sin oferta (B-3)</h2>
 *
 * <p>Las firmas con {@code ofertaId} resuelven el arancel de la practica prestada DENTRO de esa
 * oferta: el arancel especifico de la oferta (RF-M16-008) manda sobre el general de la practica.
 * Quien conoce la oferta —el devengo, la recepcion— tiene que usarlas; las firmas sin oferta solo
 * ven aranceles generales.
 */
public interface CoberturasAplicablesDirectory {

	/**
	 * Coberturas FINANCIADAS vigentes en {@code fecha} con arancel resuelto por convenio. La
	 * principal va primero; el resto por id ascendente. Nunca {@code null}; vacia = no aplica
	 * ninguna (Particular).
	 */
	default List<CoberturaAplicable> aplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			LocalDate fecha) {
		return aplicables(organizationId, consultorioId, personaId, practicaId, null, fecha);
	}

	/** {@link #aplicables(long, long, long, long, LocalDate)} para una practica dentro de una oferta. */
	List<CoberturaAplicable> aplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			Long ofertaId, LocalDate fecha);

	/**
	 * Coberturas FINANCIADAS vigentes en {@code fecha} que NO aplican por convenio, con motivo.
	 * Mismo orden que {@link #aplicables}.
	 */
	default List<CoberturaNoAplicable> noAplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			LocalDate fecha) {
		return noAplicables(organizationId, consultorioId, personaId, practicaId, null, fecha);
	}

	/** {@link #noAplicables(long, long, long, long, LocalDate)} para una practica dentro de una oferta. */
	List<CoberturaNoAplicable> noAplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			Long ofertaId, LocalDate fecha);

	/**
	 * {@code true} si {@code coberturaId} es una cobertura FINANCIADA, activa y vigente en
	 * {@code fecha} de ESA persona en ESE tenant. No mira convenio ni arancel: lo usa la reserva de
	 * un turno para aceptar la cobertura elegida (AKINE-05.02); si aplica a la practica lo decide la
	 * recepcion el dia de la prestacion. Una cobertura de otra persona o de otro tenant da
	 * {@code false}, igual que una inexistente.
	 */
	boolean vigenteDeLaPersona(long organizationId, long personaId, long coberturaId, LocalDate fecha);
}
