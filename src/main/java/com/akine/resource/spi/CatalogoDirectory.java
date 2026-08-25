package com.akine.resource.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lo unico que otros modulos pueden preguntarle al catalogo clinico (M06).
 *
 * <p>{@code resource} es el propietario de {@code especialidad}, {@code practica},
 * {@code nomenclador} y {@code nomenclador_item}: ningun otro modulo las lee ni las escribe
 * (AGENT.md seccion 4, regla 1, verificada por ArchUnit). Lo que M14 y M16 necesitan sale por
 * aca.
 *
 * <h2>Toda consulta lleva el tenant, y {@code null} NO significa "cualquiera"</h2>
 *
 * <p>{@code organizationId} es el tenant del llamador y acota que puede ver: sus conceptos
 * propios <b>mas</b> los globales. Un concepto de otro tenant nunca resuelve, y el resultado es
 * un {@code Optional} vacio, jamas una excepcion — el llamador decide si eso es un 404 o una
 * validacion.
 *
 * <h2>La resolucion historica no filtra por estado, y es a proposito</h2>
 *
 * <p>{@link #resolverCodigo} y {@link #findPractica} devuelven tambien conceptos dados de baja.
 * RN-M06-001 y RN-M06-002 exigen que un hecho de 2024 siga diciendo que practica fue y cuanto
 * valia; un metodo que escondiera los inactivos convertiria cada historico viejo en un dato
 * ilegible. Quien arma un <b>selector</b> filtra por {@code vigente()}; quien lee un
 * <b>historico</b> no filtra.
 */
public interface CatalogoDirectory {

	/** Una especialidad visible para ese tenant, activa o no. */
	Optional<CatalogoSnapshot> findEspecialidad(
			Long organizationId, long especialidadId, Instant at);

	/** Una practica visible para ese tenant, activa o no. */
	Optional<CatalogoSnapshot> findPractica(Long organizationId, long practicaId, Instant at);

	/**
	 * Las practicas que ese tenant puede ELEGIR en {@code at}: vigentes, globales y propias.
	 *
	 * <p>Es la consulta de un selector, no la de un historico. Ordenadas por nombre.
	 */
	List<CatalogoSnapshot> practicasVigentes(Long organizationId, Instant at);

	/**
	 * Que decia un codigo de un nomenclador el dia {@code at} (RN-M06-003).
	 *
	 * <p>Es la consulta que hace que un convenio de 2024 siga resolviendo el valor de 2024
	 * despues de que el de 2026 lo reemplace. Devuelve <b>una sola</b> fila: las vigencias del
	 * mismo codigo no se pueden solapar, y eso lo sostiene {@code CatalogoService} con un lock
	 * sobre el nomenclador padre.
	 */
	Optional<CatalogoSnapshot> resolverCodigo(
			Long organizationId, long nomencladorId, String codigo, Instant at);
}
