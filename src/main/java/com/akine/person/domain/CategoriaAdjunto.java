package com.akine.person.domain;

/**
 * Clasificacion ADMINISTRATIVA de un adjunto de Persona (RF-M25-003).
 *
 * <h2>Por que no hay ninguna categoria clinica, y no es un olvido</h2>
 *
 * <p>RN-M25-005 lo dice literalmente: los adjuntos clinicos siguen asociados a Caso o Atencion y
 * "una clase no clinica no debe transformarse en contenedor clinico". Agregar aca un
 * {@code ESTUDIO} o un {@code INFORME} convertiria esta lista en una historia clinica paralela
 * <b>sin ninguno de los controles de M09</b>: sin justificacion declarada, sin auditoria de
 * lectura clinica y sin relacion asistencial. El dia que hagan falta, la tabla es otra.
 *
 * <p>{@link #OTRO} existe para que una carga legitima no se trabe por una categoria que este
 * catalogo no previo — mismo criterio que {@code TipoDocumento.OTRO}. Lo que NO existe es una
 * categoria que signifique "clinico".
 *
 * <p>La lista esta duplicada en el {@code CHECK} de {@code V40}. Es deliberado y es el mismo
 * patron que {@code espacio.tipo} y {@code servicio.naturaleza}: la base tiene que rechazar por
 * si sola lo que la aplicacion no deberia mandar nunca.
 */
public enum CategoriaAdjunto {

	/** Documento de identidad: DNI, pasaporte, partida. */
	DOCUMENTO_IDENTIDAD,

	/** Credencial o carnet del financiador. La cobertura en si es M15/M08, no esto. */
	CREDENCIAL_COBERTURA,

	/** Consentimiento informado firmado, en su faz administrativa. */
	CONSENTIMIENTO,

	/** Autorizacion administrativa: de un tercero, de un tutor, del financiador. */
	AUTORIZACION,

	/** Comprobante economico o administrativo: pago, derivacion, constancia. */
	COMPROBANTE,

	/** Cualquier documento administrativo que no encaje en los anteriores. */
	OTRO
}
