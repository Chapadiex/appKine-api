package com.akine.contracting.domain;

/**
 * Clasificacion de un financiador. Lista cerrada del producto, no del tenant.
 *
 * <p><b>CLASIFICA, no habilita.</b> Ninguna regla del sistema puede decidir comportamiento a
 * partir de este valor ni del nombre del financiador: es la regla maestra 15, la misma que
 * {@code Servicio.naturaleza} declara para M27. Si un dia hace falta una regla "para los de tipo
 * X", X tiene que ser una columna nueva y tipada —{@code requiere_autorizacion} es el ejemplo de
 * como se hace bien—, nunca una comparacion contra uno de estos valores.
 *
 * <p><b>{@code PARTICULAR} no esta, y es la ausencia mas importante de este enum.</b> RN-M15-004
 * dice que "PARTICULAR es una modalidad siempre disponible aunque no sea financiador externo":
 * modelarlo como un tipo de financiador obligaria a sembrar una fila por organizacion, que
 * alguien podria renombrar o dar de baja, y a que alguna decision del sistema dependiera de ese
 * nombre. La cobertura particular se modela en M08 como la AUSENCIA de plan financiado.
 */
public enum TipoFinanciador {

	/** Obra social sindical o de direccion. */
	OBRA_SOCIAL,

	/** Empresa de medicina prepaga. */
	PREPAGA,

	/** Aseguradora de riesgos del trabajo. */
	ART,

	/** Mutual o asociacion sin fines de lucro. */
	MUTUAL,

	/** Programa u organismo publico que financia prestaciones. */
	ORGANISMO_PUBLICO,

	/** Cualquier otro pagador externo. Valida, no un cajon de sastre para lo mal clasificado. */
	OTRO
}
