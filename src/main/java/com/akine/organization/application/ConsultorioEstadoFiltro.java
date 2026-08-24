package com.akine.organization.application;

/**
 * Filtro de estado del listado de sedes (RNF-M03-004).
 *
 * <p><b>El default es {@link #ACTIVO}, y eso es lo que hace que el cambio de contrato sea
 * aditivo:</b> el listado ya existia desde 01.01 devolviendo solo las sedes vigentes, asi que
 * un cliente que no manda el parametro ve exactamente lo mismo que antes.
 *
 * <p>{@link #INACTIVO} y {@link #TODOS} existen porque una sede dada de baja sigue siendo
 * consultable con su historia (RF-M03-004). Lo que no puede pasar es que aparezca por descuido
 * en el selector de contexto, y por eso hay que pedirla explicitamente.
 */
public enum ConsultorioEstadoFiltro {

	/** Solo sedes vigentes. Comportamiento historico del endpoint. */
	ACTIVO,

	/** Solo sedes dadas de baja. Vista de historico. */
	INACTIVO,

	/** Todas, para la pantalla de administracion de sedes. */
	TODOS
}
