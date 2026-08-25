package com.akine.resource.api.dto;

import com.akine.resource.application.EspacioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Espacio fisico de una sede.
 *
 * <p><b>{@code estado} y {@code enServicio} son dos cosas distintas y la pantalla necesita las
 * dos.</b> {@code estado} es el ciclo de vida administrativo —ACTIVO o INACTIVO—;
 * {@code enServicio} dice si el espacio se ofrece para una reserva AHORA, que ademas exige
 * estar dentro de la ventana operativa. Un box ACTIVO que entra en servicio el mes que viene
 * tiene {@code estado = ACTIVO} y {@code enServicio = false}, y la UI tiene que poder explicar
 * por que no aparece en el selector.
 *
 * <p>Los dos son valores DERIVADOS y no columnas. Se publican igual porque calcularlos en el
 * cliente seria repetir en TypeScript una regla de negocio que el backend ya decidio, y las dos
 * copias divergirian.
 */
@Schema(description = "Espacio fisico de una sede: box, gimnasio, gabinete o sala grupal")
public record EspacioResponse(

		@Schema(description = "Identificador del espacio", example = "1")
		long id,

		@Schema(description = "Organizacion a la que pertenece", example = "1")
		long organizationId,

		@Schema(description = "Sede a la que pertenece (RN-M04-001)", example = "1")
		long consultorioId,

		@Schema(description = "Nombre operativo. Unico entre los espacios vigentes de la sede",
				example = "Box 1")
		String name,

		@Schema(description = "Clasificacion fisica del recurso",
				example = "BOX",
				allowableValues = {"BOX", "GIMNASIO", "GABINETE", "SALA_GRUPAL", "PILETA", "OTRO"})
		String tipo,

		@Schema(description = "Personas simultaneas que admite", example = "1")
		int capacidad,

		@Schema(description = "Observacion operativa, o null", example = "Camilla electrica")
		String notes,

		@Schema(description = "Instante UTC desde el que esta en servicio",
				example = "2026-09-01T00:00:00Z")
		Instant validFrom,

		@Schema(description = "Instante UTC hasta el que esta en servicio, exclusivo. "
				+ "null = sin fin previsto")
		Instant validUntil,

		@Schema(description = "Ciclo de vida administrativo, derivado. ACTIVO o INACTIVO",
				example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Si se ofrece para una reserva AHORA. Exige estado ACTIVO Y estar "
				+ "dentro de la ventana operativa (RN-M04-002)", example = "true")
		boolean enServicio,

		@Schema(description = "Instante UTC de la baja logica, o null si esta activo")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja, o null",
				example = "Refaccion definitiva del ala oeste")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version) {

	public static EspacioResponse from(EspacioView view) {
		return new EspacioResponse(
				view.id(),
				view.organizationId(),
				view.consultorioId(),
				view.name(),
				view.tipo(),
				view.capacidad(),
				view.notes(),
				view.validFrom(),
				view.validUntil(),
				view.estado(),
				view.enServicio(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
