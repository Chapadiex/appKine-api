package com.akine.contracting.domain;

/**
 * Como se pacto la prestacion en un convenio (RF-M16-001).
 *
 * <p><b>CLASIFICA, no habilita.</b> Ninguna decision del sistema depende de este valor: la
 * resolucion del arancel efectivo mira la vigencia y la practica, nunca la modalidad. Es la misma
 * regla que {@code financiador.tipo} (V41) y {@code espacio.tipo} (V19) siguen, y la que la regla
 * maestra 15 impone — que ningun comportamiento cuelgue de un nombre o de una etiqueta.
 *
 * <p>Existe porque RF-M16-001 la pide explicitamente y porque quien administra necesita saber
 * bajo que forma se pacto lo que esta mirando. Lista cerrada, sostenida ademas por
 * {@code ck_convenio_modalidad}: el conjunto es del producto y no del tenant, asi que no hay
 * tabla de catalogo.
 */
public enum ModalidadConvenio {

	/** Se factura cada practica realizada, al arancel de su vigencia. Es el caso habitual. */
	POR_PRESTACION,

	/** Se factura por sesion completa, independientemente de cuantas practicas incluya. */
	POR_SESION,

	/** Se factura un modulo cerrado de tratamiento. */
	MODULO,

	/** Pago fijo periodico por paciente a cargo, con independencia de lo realizado. */
	CAPITA
}
