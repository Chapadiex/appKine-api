package com.akine.person.api.dto;

import com.akine.person.domain.CategoriaAdjunto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Reclasificacion de un adjunto (RF-M25-003).
 *
 * <p><b>Lo unico editable de un adjunto es como esta descripto.</b> El contenido, su nombre y su
 * tipo son inmutables: reemplazar el archivo de una fila reescribiria un hecho, y la forma de
 * reemplazar un documento es dar de baja el viejo y subir el nuevo, que deja las dos versiones
 * consultables.
 *
 * <p>Edicion parcial, mismo criterio que el PATCH de persona: lo que no viene, no se toca.
 */
@Schema(description = "Reclasificacion de un adjunto. El contenido no se puede cambiar")
public record ClasificarAdjuntoRequest(

		@Schema(description = "Nueva clasificacion, o null para no cambiarla",
				example = "CREDENCIAL_COBERTURA",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		CategoriaAdjunto categoria,

		@Schema(description = "Nuevo titulo, o null para no cambiarlo. Cadena vacia lo borra",
				example = "Credencial 2026",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El titulo no puede superar los 160 caracteres")
		String titulo) {
}
