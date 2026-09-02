package com.akine.person.api.dto;

import com.akine.person.application.AdjuntoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un adjunto administrativo tal como sale por la API.
 *
 * <p><b>No lleva ninguna ruta.</b> RN-M25-002: la clave de almacenamiento y el path en disco no
 * salen del backend. Para descargar se usa {@code id} contra el endpoint de contenido, que
 * autoriza cada llamada.
 */
@Schema(description = "Documento administrativo vinculado a una persona. No es un dato clinico")
public record AdjuntoResponse(

		@Schema(description = "Identificador del adjunto", example = "17")
		long id,

		@Schema(description = "Persona a la que pertenece", example = "42")
		long personaId,

		@Schema(description = "Sede desde la que se cargo. Dato de auditoria, no de propiedad: el "
				+ "adjunto se ve y se descarga desde cualquier sede de la organizacion",
				example = "3")
		Long consultorioId,

		@Schema(description = "Clasificacion administrativa", example = "CREDENCIAL_COBERTURA",
				allowableValues = {"DOCUMENTO_IDENTIDAD", "CREDENCIAL_COBERTURA", "CONSENTIMIENTO",
						"AUTORIZACION", "COMPROBANTE", "OTRO"})
		String categoria,

		@Schema(description = "Titulo con el que se describio el documento, o null")
		String titulo,

		@Schema(description = "Nombre original del archivo", example = "credencial.pdf")
		String nombreArchivo,

		@Schema(description = "Tipo DETECTADO por los bytes, no el declarado al subir",
				example = "application/pdf")
		String contentType,

		@Schema(description = "Tamano en bytes", example = "184320")
		long tamanoBytes,

		@Schema(description = "SHA-256 del contenido, en hexadecimal. Subir el mismo archivo dos "
				+ "veces devuelve este mismo adjunto con 200")
		String checksumSha256,

		@Schema(description = "Si el contenido se puede descargar hoy", example = "DISPONIBLE",
				allowableValues = {"DISPONIBLE", "NO_DISPONIBLE"})
		String estado,

		@Schema(description = "Cuenta que subio el archivo", example = "8")
		long subidoPor,

		@Schema(description = "Instante UTC de la carga")
		Instant subidoEn,

		@Schema(description = "Ciclo de vida del adjunto. Un adjunto INACTIVO se sigue "
				+ "descargando: la baja es logica", example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estadoCicloDeVida,

		@Schema(description = "Instante UTC de la baja logica, o null")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja, o null")
		String deactivationReason,

		@Schema(description = "Version para el bloqueo optimista", example = "0")
		long version) {

	public static AdjuntoResponse from(AdjuntoView view) {
		return new AdjuntoResponse(
				view.id(),
				view.personaId(),
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
