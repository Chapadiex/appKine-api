package com.akine.person.application;

/**
 * Filtro por historia clinica en la busqueda del padron (13/11/2026).
 *
 * <p>Separa a quien ya tiene historia clinica abierta en la organizacion de quien no. Es
 * independiente del perfil: una persona puede tener perfil de paciente sin historia todavia (se
 * abre con la primera sesion), y una historia sobrevive a la baja del perfil.
 *
 * <p>El defecto es {@link #TODOS}.
 */
public enum HistoriaFiltro {

	/** Solo las que tienen historia clinica vigente. */
	CON_HISTORIA,

	/** Solo las que no. */
	SIN_HISTORIA,

	/** Indistinto. Es el defecto. */
	TODOS
}
