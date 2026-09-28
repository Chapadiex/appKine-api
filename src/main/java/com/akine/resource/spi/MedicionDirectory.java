package com.akine.resource.spi;

import java.util.List;
import java.util.Optional;

/**
 * Lo unico que otros modulos pueden preguntarle al catalogo de mediciones (M06, AKINE-06.03).
 *
 * <p>{@code resource} es el propietario de {@code medicion_definicion}: ningun otro modulo la lee
 * ni la escribe (AGENT.md seccion 4, regla 1, verificada por ArchUnit). Lo que M14 necesita para
 * registrar una medicion sale por aca.
 *
 * <p><b>La arista es de un solo sentido: {@code encounter -> resource.spi}.</b> {@code resource}
 * no sabe que existen mediciones tomadas y no tiene por que: un catalogo que conociera a sus
 * consumidores dejaria de ser un catalogo. La ausencia de la arista inversa se verifico con una
 * clase sonda contra {@code ModuleArchitectureTest} <b>antes</b> de escribir el servicio, no
 * razonando el grafo: {@code SlicesRuleDefinition} busca ciclos de cualquier longitud.
 *
 * <h2>Toda consulta lleva el tenant, y {@code null} NO significa "cualquiera"</h2>
 *
 * <p>{@code organizationId} es el tenant del llamador y acota que puede ver: sus definiciones
 * propias <b>mas</b> las globales de plataforma. Una definicion de otro tenant nunca resuelve, y
 * el resultado es un {@link Optional} vacio, jamas una excepcion — el llamador decide si eso es
 * un 404 o una validacion. Internamente se filtra por {@code owner_key}, nunca por
 * {@code organization_id}: varios {@code NULL} no colisionan en MySQL y filtrar por la columna
 * cruda dejaria fuera todo el catalogo global (ADR-0021, error que 02.05 ya pago).
 *
 * <h2>{@link #find} no filtra por estado, y es a proposito</h2>
 *
 * <p>Devuelve tambien las definiciones dadas de baja. RN-M06-001 y RN-M06-002 exigen que un hecho
 * historico siga siendo legible; un metodo que escondiera las inactivas convertiria cada medicion
 * vieja en un dato que no se puede explicar. Quien arma un <b>selector</b> usa
 * {@link #definicionesVigentes}; quien <b>valida un registro</b> usa {@link #find} y decide el 409
 * con {@code activa()}.
 */
public interface MedicionDirectory {

	/**
	 * Una definicion visible para ese tenant, activa o no.
	 *
	 * @param organizationId tenant del llamador; resuelve sus definiciones propias y las globales
	 */
	Optional<MedicionDefinicionSnapshot> find(Long organizationId, long definicionId);

	/**
	 * Las definiciones que ese tenant puede ELEGIR: activas, globales y propias, por nombre.
	 *
	 * <p>Es la consulta de un selector, no la de un historico: las inactivas no aparecen.
	 */
	List<MedicionDefinicionSnapshot> definicionesVigentes(Long organizationId);
}
