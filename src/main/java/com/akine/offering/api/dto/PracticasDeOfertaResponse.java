package com.akine.offering.api.dto;

import com.akine.offering.application.PracticasDeOfertaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** Que practicas puede prestar una oferta y cual es su principal (A-9, DP-11). */
@Schema(description = "Practicas que una oferta puede prestar, con su principal")
public record PracticasDeOfertaResponse(

		@Schema(description = "Oferta configurada", example = "34")
		long ofertaId,

		@Schema(
				description = "Version de la OFERTA que hay que mandar como expectedVersion en el "
						+ "proximo reemplazo, de practicas o de habilitaciones: las dos comparten la "
						+ "misma. En la lectura es la vigente; en la respuesta de un reemplazo es la que "
						+ "la oferta queda teniendo despues de ese reemplazo",
				example = "3")
		long ofertaVersion,

		@Schema(
				description = "Practica principal vigente, o null si la oferta no declara practicas",
				example = "51")
		Long practicaPrincipalId,

		@Schema(description = "Practicas de la oferta, activas e inactivas, en orden de alta")
		List<PracticaDeOferta> practicas) {

	/** Una practica de la oferta. */
	@Schema(description = "Practica que la oferta puede prestar")
	public record PracticaDeOferta(

			@Schema(description = "Identificador de la fila", example = "12")
			long id,

			@Schema(description = "Practica del catalogo", example = "51")
			long practicaId,

			@Schema(description = "Codigo de la practica. Null si el catalogo ya no la resuelve")
			String codigo,

			@Schema(description = "Nombre de la practica. Null si el catalogo ya no la resuelve")
			String nombre,

			@Schema(description = "Si es la principal de la oferta")
			boolean principal,

			@Schema(description = "Ciclo de vida de la fila", example = "ACTIVO",
					allowableValues = {"ACTIVO", "INACTIVO"})
			String estado,

			@Schema(
					description = "Si la practica se puede elegir hoy en el catalogo. Puede ser false "
							+ "con la fila activa: la practica se dio de baja en el catalogo y la "
							+ "oferta la conserva. Se muestra, no se esconde")
			boolean vigenteEnCatalogo,

			@Schema(description = "Instante UTC de la baja. Null mientras este activa")
			Instant deletedAt,

			@Schema(description = "Motivo declarado de la baja")
			String deactivationReason,

			@Schema(description = "Version de la fila", example = "0")
			long version) {
	}

	public static PracticasDeOfertaResponse de(PracticasDeOfertaView view) {
		return new PracticasDeOfertaResponse(
				view.ofertaId(),
				view.ofertaVersion(),
				view.practicaPrincipalId(),
				view.practicas().stream()
						.map(p -> new PracticaDeOferta(
								p.id(), p.practicaId(), p.codigo(), p.nombre(), p.principal(),
								p.estado(), p.vigenteEnCatalogo(), p.deletedAt(),
								p.deactivationReason(), p.version()))
						.toList());
	}
}
