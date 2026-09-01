package com.akine.offering.spi;

import java.util.List;
import java.util.Optional;

/**
 * Lectura de ofertas y de sus habilitaciones para modulos que no son duenos de M27.
 *
 * <p><b>No autoriza nada.</b> Es una costura entre modulos, no una API: quien la llama ya resolvio
 * pertenencia y permiso con su propio criterio. El motor de agenda no puede reusar el permiso de
 * {@code OfertaService} —{@code consultorio:manage} es de quien configura la oferta, no de quien
 * busca un turno contra ella— asi que una llamada aca nunca sustituye a un control de acceso.
 *
 * <p>Todas las firmas llevan {@code organizationId} y {@code consultorioId} en el {@code WHERE}.
 * Es la misma leccion que M05 aprendio con las memberships de alcance organizacion: sin el
 * predicado por sede, datos de dos sedes se mezclan bajo un mismo id y el resultado no falla,
 * inventa.
 */
public interface OfertaDirectory {

	/** La oferta de esa sede, activa o no. {@code empty} si no existe o es de otro tenant/sede. */
	Optional<OfertaSnapshot> find(long organizationId, long consultorioId, long ofertaId);

	/**
	 * Lo que cuesta la oferta. Ver {@link PrecioDeOferta}: viaja aparte del snapshot general.
	 */
	Optional<PrecioDeOferta> precioDe(long organizationId, long consultorioId, long ofertaId);

	/**
	 * Memberships de profesionales habilitados para la oferta, con su vigencia.
	 *
	 * <p>Devuelve la habilitacion aunque su vigencia no toque la ventana: filtrar por fecha es
	 * responsabilidad del llamador, que la evalua dia por dia. Filtrar aca obligaria a pasar la
	 * ventana y devolveria un resultado que no se puede explicar —"no hay profesional" sin poder
	 * decir si es que no hay ninguno o que el que hay no estaba vigente—.
	 */
	List<HabilitacionSnapshot> profesionalesHabilitados(
			long organizationId, long consultorioId, long ofertaId);

	/** Espacios habilitados para la oferta, con su vigencia. Ver {@link #profesionalesHabilitados}. */
	List<HabilitacionSnapshot> espaciosHabilitados(
			long organizationId, long consultorioId, long ofertaId);
}
