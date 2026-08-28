package com.akine.person.domain;

/**
 * Tipo del documento declarado por una {@link Persona} (M07).
 *
 * <p>Lista cerrada del PRODUCTO y no del tenant, mismo criterio que {@code espacio.tipo} (V19) y
 * {@code servicio.naturaleza} (V24): no hay tabla de catalogo porque ningun centro necesita
 * inventar un tipo propio, y un enum hace que un valor invalido falle al deserializar en vez de
 * llegar a la base.
 *
 * <p><b>No existe un valor que signifique "sin documento".</b> Eso ya se expresa con el tipo y el
 * numero en {@code null}, y tener las dos formas de decir lo mismo garantiza que la mitad del
 * codigo compruebe una y la otra mitad la otra. {@link #OTRO} es distinto: es un documento real
 * cuyo tipo este catalogo no previo, y existe para que un alta no se bloquee por un caso que
 * nadie anticipo.
 *
 * <p><b>Ninguna decision del sistema depende de este valor</b> mas alla de mostrarlo y de
 * participar de la unicidad. En particular no hay —ni debe agregarse— validacion de formato por
 * tipo: la longitud y la forma de un DNI, de una cedula o de un pasaporte cambian por pais y por
 * epoca, y una regla equivocada rechaza documentos legitimos en el mostrador, que es peor que
 * aceptar uno mal tipeado y corregirlo despues.
 */
public enum TipoDocumento {

	/** Documento Nacional de Identidad. */
	DNI,

	/** Libreta Civica. */
	LC,

	/** Libreta de Enrolamiento. */
	LE,

	/** Cedula de identidad, tipicamente de otro pais. */
	CI,

	/** Pasaporte. */
	PASAPORTE,

	/** Un documento real cuyo tipo este catalogo no previo. Ver la cabecera. */
	OTRO
}
