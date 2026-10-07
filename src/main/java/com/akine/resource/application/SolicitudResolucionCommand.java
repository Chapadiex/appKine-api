package com.akine.resource.application;

import com.akine.resource.domain.SolicitudEstado;

/**
 * Decision de la plataforma sobre una solicitud de alta de catalogo (RF-M06-005).
 *
 * <p>{@code nota} es obligatoria en los dos desenlaces. Aprobar sin decir con que criterio deja
 * al centro sin saber si su pedido se entendio; rechazar sin decir por que lo deja sin nada
 * accionable, y la unica salida es volver a pedir lo mismo.
 *
 * <p>Los cuatro campos de concepto solo valen al aprobar (AKINE-A-7): son la normalizacion con
 * la que la plataforma publica el concepto global. {@code codigo} y {@code nombre} nulos toman
 * los propuestos por el centro.
 */
public record SolicitudResolucionCommand(

		/** APROBADA o RECHAZADA. PENDIENTE no es una resolucion y se rechaza con 400. */
		SolicitudEstado estado,

		String nota,

		long expectedVersion,

		String codigo,

		String nombre,

		String descripcion,

		/** Obligatorio al aprobar una practica: especialidad GLOBAL de la que cuelga. */
		Long especialidadId) {

	/** Un rechazo, o una aprobacion que publica con los datos propuestos. */
	public SolicitudResolucionCommand(SolicitudEstado estado, String nota, long expectedVersion) {
		this(estado, nota, expectedVersion, null, null, null, null);
	}

	boolean traeDatosDeConcepto() {
		return codigo != null || nombre != null || descripcion != null || especialidadId != null;
	}
}
