package com.akine.contracting.application;

/**
 * Filtro de ciclo de vida de los listados de M15.
 *
 * <p>El default es {@link #ACTIVO} y eso es lo que cumple RN-M15-002: <b>las selecciones nuevas
 * excluyen lo inactivo</b>. Que haya que pedir explicitamente lo dado de baja es la unica forma de
 * que no se cuele por descuido en el selector con el que se registra una cobertura.
 *
 * <p>{@link #INACTIVO} y {@link #TODOS} existen porque RN-M15-003 exige que lo dado de baja siga
 * siendo consultable: la pantalla de administracion necesita mostrarlo para que se entienda por
 * que un codigo "esta libre" o "esta tomado", y por que una cobertura vieja apunta a algo que ya
 * no se ofrece.
 *
 * <p><b>No filtra por VIGENCIA.</b> Ciclo de vida y vigencia son dos cosas distintas
 * ({@code PlanCobertura} lo desarrolla): un plan activo con vigencia vencida sigue siendo ACTIVO
 * y este filtro lo devuelve. Quien necesita "los que se pueden elegir hoy" pregunta por el
 * {@code spi}, que es donde vive esa combinacion.
 */
public enum EstadoFiltro {

	/** Solo lo vigente administrativamente. Lo que ve un selector. */
	ACTIVO,

	/** Solo lo dado de baja. Vista de historico. */
	INACTIVO,

	/** Todo, para la pantalla de administracion. */
	TODOS
}
