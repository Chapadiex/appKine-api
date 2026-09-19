package com.akine.clinical.domain;

/**
 * Clasificacion CLINICA de un adjunto de Historia Clinica (RF-M25-003).
 *
 * <h2>Por que esta lista no tiene ninguna categoria administrativa</h2>
 *
 * <p>Es la misma regla de {@code person.domain.CategoriaAdjunto} leida en la otra direccion.
 * RN-M25-005 prohibe que una clase no clinica se convierta en contenedor clinico, y por eso alla
 * no hay {@code ESTUDIO}; aca no hay {@code DOCUMENTO_IDENTIDAD} ni {@code CREDENCIAL_COBERTURA}
 * por el motivo simetrico: un DNI cargado en esta tabla quedaria detras de {@code hc:read} con
 * justificacion declarada, o sea <b>inaccesible para el mostrador que lo necesita todos los
 * dias</b>. Una separacion que corre en una sola direccion no es una separacion.
 *
 * <p>{@link #CONSENTIMIENTO_CLINICO} se llama asi y no {@code CONSENTIMIENTO} para que nadie lo
 * confunda con el administrativo de {@code V40}: el de alla es el de tratamiento de datos, el de
 * aca es el informado de una practica.
 *
 * <p>{@link #OTRO} existe para que una carga legitima no se trabe por una categoria que este
 * catalogo no previo — mismo criterio que {@code TipoDocumento.OTRO} y que {@code V40}.
 *
 * <p>La lista esta duplicada en el {@code CHECK} de {@code V46}. Es deliberado y es el mismo
 * patron que {@code espacio.tipo} y {@code servicio.naturaleza}: la base tiene que rechazar por
 * si sola lo que la aplicacion no deberia mandar nunca.
 */
public enum CategoriaAdjuntoClinico {

	/** Estudio complementario: laboratorio, imagen con su protocolo, funcional. */
	ESTUDIO,

	/** Informe profesional: epicrisis, informe de especialista, derivacion clinica. */
	INFORME,

	/** Imagen clinica suelta: foto de una lesion, una radiografia digitalizada. */
	IMAGEN,

	/** Consentimiento informado de una practica. NO es el administrativo de {@code V40}. */
	CONSENTIMIENTO_CLINICO,

	/** Evolucion en papel, escaneada. El caso tipico de una historia que venia de antes. */
	EVOLUCION_ESCANEADA,

	/** Cualquier documento clinico que no encaje en los anteriores. */
	OTRO
}
