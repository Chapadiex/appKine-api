package com.akine.resource.api.dto;

import com.akine.resource.application.CatalogoSolicitudView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Solicitud de alta de un concepto GLOBAL (RF-M06-005).
 *
 * <p>Un solo schema para las dos pantallas —la del centro que pide y la bandeja de la
 * plataforma— porque muestran lo mismo con distinto filtro. {@code organizationId} viaja
 * siempre, aunque el propio tenant ya lo sepa: la bandeja es cross-tenant y necesita decir de
 * quien es cada pedido.
 */
@Schema(description = "Pedido de un tenant para que la plataforma incorpore un concepto global")
public record CatalogoSolicitudResponse(

		@Schema(description = "Identificador de la solicitud", example = "1")
		long id,

		@Schema(description = "Organizacion que solicita", example = "1")
		long organizationId,

		@Schema(description = "Sede desde la que se pidio, o null. Es trazabilidad: la "
				+ "solicitud es de la organizacion", example = "1")
		Long consultorioId,

		@Schema(description = "Que concepto global se pide",
				example = "ESPECIALIDAD",
				allowableValues = {"ESPECIALIDAD", "PRACTICA", "NOMENCLADOR"})
		String tipo,

		@Schema(description = "Nombre propuesto por el centro", example = "Terapia ocupacional")
		String nombrePropuesto,

		@Schema(description = "Codigo sugerido, o null. El centro propone, la plataforma dispone")
		String codigoPropuesto,

		@Schema(description = "Por que hace falta", example = "Tres profesionales del centro la "
				+ "ejercen y hoy no hay forma de registrarla")
		String justificacion,

		@Schema(description = "Estado de la solicitud",
				example = "PENDIENTE",
				allowableValues = {"PENDIENTE", "APROBADA", "RECHAZADA"})
		String estado,

		@Schema(description = "Cuenta que la creo", example = "1")
		long solicitadaPorAccountId,

		@Schema(description = "Cuenta de plataforma que la resolvio, o null")
		Long resueltaPorAccountId,

		@Schema(description = "Instante UTC de la resolucion, o null")
		Instant resueltaAt,

		@Schema(description = "Motivo de la aprobacion o del rechazo, o null")
		String resolucionNota,

		@Schema(description = "Concepto global creado al aprobar, si se creo. Aprobar NO crea el "
				+ "concepto automaticamente: la plataforma lo publica por el alta normal")
		Long conceptoId,

		@Schema(description = "Instante UTC del pedido")
		Instant createdAt,

		@Schema(description = "Version a reenviar para resolver", example = "0")
		long version) {

	public static CatalogoSolicitudResponse from(CatalogoSolicitudView view) {
		return new CatalogoSolicitudResponse(
				view.id(),
				view.organizationId(),
				view.consultorioId(),
				view.tipo(),
				view.nombrePropuesto(),
				view.codigoPropuesto(),
				view.justificacion(),
				view.estado(),
				view.solicitadaPorAccountId(),
				view.resueltaPorAccountId(),
				view.resueltaAt(),
				view.resolucionNota(),
				view.conceptoId(),
				view.createdAt(),
				view.version());
	}
}
