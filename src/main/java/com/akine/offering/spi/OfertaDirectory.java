package com.akine.offering.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
	 * Las ofertas de esa sede con esos ids, activas o no, en UNA consulta, indexadas por id. Las que
	 * no existen o son de otro tenant/sede no aparecen en el mapa. Para listas: pedir la oferta de
	 * cada fila con {@link #find} es una consulta por fila (docs/carga.md §6.4).
	 */
	Map<Long, OfertaSnapshot> findTodas(long organizationId, long consultorioId, Collection<Long> ofertaIds);

	/**
	 * Servicio y naturaleza de esas ofertas de la sede, activas o no, indexadas por id (AKINE-09.01b).
	 * Mismo criterio que {@link #findTodas}: las de otro tenant o sede no aparecen. Dos consultas,
	 * no una por oferta.
	 */
	Map<Long, ClasificacionDeOferta> clasificar(
			long organizationId, long consultorioId, Collection<Long> ofertaIds);

	/**
	 * Lo que cuesta la oferta. Ver {@link PrecioDeOferta}: viaja aparte del snapshot general.
	 */
	Optional<PrecioDeOferta> precioDe(long organizationId, long consultorioId, long ofertaId);

	/**
	 * {@link #precioDe} para varias ofertas de esa sede en UNA consulta, indexado por id. Las que no
	 * existen o son de otro tenant/sede no aparecen en el mapa. Para listas: la recepcion del dia
	 * pedia el precio de cada oferta distinta por separado (docs/carga.md §6.4).
	 */
	Map<Long, PrecioDeOferta> preciosDe(long organizationId, long consultorioId, Collection<Long> ofertaIds);

	/**
	 * B-3 (RF-M16-009): lo que cuesta la oferta ESE dia local. El precio particular activo que
	 * cubre la fecha manda sobre el precio de lista; sin precio particular, es {@link #precioDe}.
	 *
	 * <p>LECTURA VIVA: quien devenga copia el importe a su propia fila y no lo vuelve a pedir.
	 */
	Optional<PrecioDeOferta> precioVigenteEl(
			long organizationId, long consultorioId, long ofertaId, LocalDate fecha);

	/**
	 * {@link #precioVigenteEl} para el dia local de la SEDE en ese instante. Existe para quien solo
	 * tiene el instante del hecho —el cierre de una sesion— y no la zona de la sede: resolverlo en
	 * UTC correria el dia despues de las 21 h en Argentina.
	 */
	Optional<PrecioDeOferta> precioEn(
			long organizationId, long consultorioId, long ofertaId, Instant momento);

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

	/**
	 * Los minutos de cada profesional con duracion propia en esa oferta, por membership (TURNO - Tecnico
	 * §47). Quien no esta usa la de la oferta: {@link OfertaSnapshot#duracionCon} resuelve las dos cosas.
	 *
	 * <p>Una consulta para todos los profesionales, a proposito: el motor de agenda corta un dia entero
	 * con el mismo mapa en vez de preguntar profesional por profesional.
	 */
	Map<Long, Integer> duracionesPropias(long organizationId, long consultorioId, long ofertaId);

	/**
	 * El esquema economico declarado por la oferta (RN-M27-006). Vacio si la oferta no existe en
	 * esa sede o no declaro esquema. Lo lee {@code activity} para decidir si una asistencia consume
	 * credito de un pase (RN-M29-005).
	 */
	Optional<EsquemaDeCobro> esquemaDeCobro(long organizationId, long consultorioId, long ofertaId);
}
