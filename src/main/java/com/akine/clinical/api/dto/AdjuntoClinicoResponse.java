package com.akine.clinical.api.dto;

import com.akine.clinical.application.AdjuntoClinicoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un adjunto clinico tal como sale por la API.
 *
 * <p><b>No lleva ninguna ruta ni la clave de almacenamiento.</b> RN-M25-002: el
 * {@code storage_key} y el path en disco no salen del backend por ningun campo. Para descargar se
 * usa el {@code id} contra el endpoint de contenido, que autoriza y <b>audita</b> cada llamada —
 * que es lo que una URL firmada no podria hacer.
 *
 * <p>Es un record propio y no el de {@code person}: RN-M25-005 prohibe que una clase no clinica
 * se convierta en contenedor clinico, y las diferencias no son cosmeticas —el ancla es la
 * historia y no la persona, y las categorias son clinicas—.
 */
@Schema(description = "Documento clinico anclado a una Historia Clinica (M09/M25)")
public record AdjuntoClinicoResponse(

		@Schema(description = "Identificador del adjunto", example = "17")
		long id,

		@Schema(description = "Historia clinica de la que cuelga", example = "88")
		long historiaClinicaId,

		@Schema(description = "Entrada clinica que el documento respalda, o null si cuelga "
				+ "directamente de la historia", example = "312")
		Long entradaClinicaId,

		@Schema(description = "Sede desde la que se cargo. Dato de auditoria, no de propiedad: la "
				+ "historia es de la ORGANIZACION (DP-03) y el adjunto se lee desde cualquier "
				+ "sede con permiso clinico", example = "3")
		Long consultorioId,

		@Schema(description = "Clasificacion clinica del documento", example = "ESTUDIO",
				allowableValues = {"ESTUDIO", "INFORME", "IMAGEN", "CONSENTIMIENTO_CLINICO",
						"EVOLUCION_ESCANEADA", "OTRO"})
		String categoria,

		@Schema(description = "Titulo con el que se describio el documento, o null")
		String titulo,

		@Schema(description = "Nombre original del archivo", example = "rmn-rodilla.pdf")
		String nombreArchivo,

		@Schema(description = "Tipo DETECTADO por los bytes, no el declarado al subir",
				example = "application/pdf")
		String contentType,

		@Schema(description = "Tamano en bytes", example = "184320")
		long tamanoBytes,

		@Schema(description = "SHA-256 del contenido, en hexadecimal. Subir el mismo archivo dos "
				+ "veces a la misma historia devuelve ESTE mismo adjunto con 200")
		String checksumSha256,

		@Schema(description = "Si el contenido se puede descargar hoy. NO_DISPONIBLE significa "
				+ "que la fila esta y el binario se perdio: la descarga responde 409, no 404",
				example = "DISPONIBLE", allowableValues = {"DISPONIBLE", "NO_DISPONIBLE"})
		String estado,

		@Schema(description = "Cuenta que subio el archivo", example = "8")
		long subidoPor,

		@Schema(description = "Instante UTC de la carga")
		Instant subidoEn,

		@Schema(description = "Ciclo de vida del adjunto. Un adjunto INACTIVO se SIGUE "
				+ "descargando: la baja es logica y el binario no se borra del disco",
				example = "ACTIVO", allowableValues = {"ACTIVO", "INACTIVO"})
		String estadoCicloDeVida,

		@Schema(description = "Instante UTC de la baja logica, o null")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja, o null")
		String deactivationReason,

		@Schema(description = "Version para el bloqueo optimista", example = "0")
		long version) {

	public static AdjuntoClinicoResponse from(AdjuntoClinicoView view) {
		return new AdjuntoClinicoResponse(
				view.id(),
				view.historiaClinicaId(),
				view.entradaClinicaId(),
				view.consultorioId(),
				view.categoria(),
				view.titulo(),
				view.nombreArchivo(),
				view.contentType(),
				view.tamanoBytes(),
				view.checksumSha256(),
				view.estado(),
				view.subidoPor(),
				view.subidoEn(),
				view.estadoCicloDeVida(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
