package com.akine.resource.application;

import com.akine.resource.domain.SolicitudEstado;

/**
 * Decision de la plataforma sobre una solicitud de alta de catalogo (RF-M06-005).
 *
 * <p>{@code nota} es obligatoria en los dos desenlaces. Aprobar sin decir con que criterio deja
 * al centro sin saber si su pedido se entendio; rechazar sin decir por que lo deja sin nada
 * accionable, y la unica salida es volver a pedir lo mismo.
 *
 * <p>{@code conceptoId} lo llena el servicio si la aprobacion crea el concepto global en el
 * acto; el comando no lo trae porque la plataforma aprueba una solicitud, no la implementa desde
 * este endpoint.
 */
public record SolicitudResolucionCommand(

		/** APROBADA o RECHAZADA. PENDIENTE no es una resolucion y se rechaza con 400. */
		SolicitudEstado estado,

		String nota,

		long expectedVersion) {
}
