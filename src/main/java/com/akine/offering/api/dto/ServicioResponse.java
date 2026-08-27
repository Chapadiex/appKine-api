package com.akine.offering.api.dto;

import com.akine.offering.application.ServicioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un servicio del catalogo GLOBAL de la plataforma (M27).
 *
 * <p>No lleva {@code organizationId} y no es un olvido: un servicio no pertenece a ningun
 * tenant. Lo que cada centro decide es si lo ofrece y en que condiciones, y eso es una
 * {@link OfertaResponse}, no este recurso.
 */
@Schema(description = "Servicio del catalogo global de la plataforma")
public record ServicioResponse(

		@Schema(description = "Identificador del servicio", example = "12")
		long id,

		@Schema(
				description = "Clave estable con la que las ofertas lo referencian. No cambia "
						+ "nunca: renombrar es cambiar nombre, no codigo",
				example = "KINESIOLOGIA_SESION")
		String codigo,

		@Schema(description = "Nombre visible del servicio", example = "Sesion de kinesiologia")
		String nombre,

		@Schema(description = "Descripcion libre. Nunca contenido clinico")
		String descripcion,

		@Schema(
				description = "Que clase de prestacion es. No condiciona por nombre ninguna "
						+ "regla: lo que decide el comportamiento son los tres campos de abajo",
				example = "TERAPEUTICO",
				allowableValues = {"CLINICO", "TERAPEUTICO", "PREVENTIVO", "BIENESTAR"})
		String naturaleza,

		@Schema(
				description = "Modalidad sugerida al crear una oferta. La oferta puede apartarse",
				example = "INDIVIDUAL",
				allowableValues = {"INDIVIDUAL", "GRUPAL"})
		String modalidadDefault,

		@Schema(description = "Si por defecto la prestacion exige un caso clinico abierto")
		boolean requiereCasoClinicoDefault,

		@Schema(description = "Si por defecto la prestacion genera registro clinico")
		boolean generaRegistroClinicoDefault,

		@Schema(
				description = "Ciclo de vida. Un servicio INACTIVO se sigue leyendo con 200: los "
						+ "historicos que lo referencian tienen que seguir resolviendo",
				example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(
				description = "Version para el control optimista. Se manda de vuelta en "
						+ "expectedVersion al editar o dar de baja",
				example = "0")
		long version) {

	public static ServicioResponse de(ServicioView view) {
		return new ServicioResponse(
				view.id(),
				view.codigo(),
				view.nombre(),
				view.descripcion(),
				view.naturaleza(),
				view.modalidadDefault(),
				view.requiereCasoClinicoDefault(),
				view.generaRegistroClinicoDefault(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
