package com.akine.clinical.api.dto;

import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Reclasificacion de un adjunto clinico (RF-M25-003). Edicion parcial: lo que no viene, no se
 * toca.
 *
 * <p><b>Lo unico editable es COMO esta descripto</b> —categoria y titulo—. El contenido, su
 * nombre, su tipo y la entrada que respalda son inmutables: reemplazar el archivo de una fila
 * reescribiria un hecho clinico. La forma de reemplazar un documento es dar de baja el viejo y
 * subir el nuevo, que deja las dos decisiones consultables.
 *
 * <p>La historia clinica no viaja aca sino como parametro de la operacion: ver el javadoc de
 * {@code AdjuntoClinicoController}.
 */
@Schema(description = "Reclasificacion de un adjunto clinico. Solo cambia como esta descripto")
public record ReclasificarAdjuntoClinicoRequest(

		@Schema(description = "Clasificacion clinica nueva. Si no viene, no se toca",
				example = "INFORME")
		CategoriaAdjuntoClinico categoria,

		@Schema(description = "Titulo nuevo. Si no viene, no se toca")
		@Size(max = 200, message = "El titulo no puede superar los 200 caracteres")
		String titulo) {
}
