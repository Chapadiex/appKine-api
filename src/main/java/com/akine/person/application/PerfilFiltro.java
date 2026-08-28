package com.akine.person.application;

/**
 * Filtro por perfil clinico en la busqueda del padron.
 *
 * <p>Es la consulta que hace operativa la separacion de RN-M07-005: "dame las personas que son
 * pacientes" y "dame las que todavia no lo son" son dos preguntas distintas y las dos se hacen en
 * el mostrador. Sin este filtro, una pantalla clinica tendria que traer el padron entero y
 * descartar en el cliente, que ademas le mostraria al operador gente que no le corresponde ver
 * en ese flujo.
 *
 * <p>El defecto es {@link #TODOS}: la busqueda del mostrador no sabe de antemano si la persona
 * que busca es paciente.
 */
public enum PerfilFiltro {

	/** Solo las personas con perfil de paciente vigente. */
	CON_PERFIL,

	/** Solo las que no lo tienen. Incluye a quien lo tuvo y se lo dieron de baja. */
	SIN_PERFIL,

	/** Indistinto. Es el defecto. */
	TODOS
}
