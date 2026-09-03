package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Vincula un adjunto ya subido como documento de una orden o de una autorizacion (RF-M17-002,
 * RF-M25-006).
 *
 * <p><b>Aca no se sube nada.</b> El archivo lo sube {@code POST /personas/{id}/adjuntos} desde
 * 03.02, con su validacion de tipo y tamano, su clave de almacenamiento opaca —que nunca sale del
 * backend, RN-M25-002— y su descarga autorizada llamada a llamada. Este endpoint solo guarda el
 * vinculo. Un segundo mecanismo de carga significaria una segunda validacion, una segunda ruta de
 * descarga y una segunda superficie de path traversal.
 *
 * <p>El adjunto tiene que ser <b>de la misma persona</b>: vincular el de otro paciente lo volveria
 * descargable desde esta ruta y evadiria el control de acceso que el adjunto hereda de su persona
 * (RN-M25-003).
 */
@Schema(description = "Adjunto a vincular como documento del registro")
public record VincularDocumentoRequest(

		@Schema(
				description = "Adjunto administrativo de ESTA persona, ya subido y no dado de "
						+ "baja. Null desvincula, que es una operacion legitima: el operador subio "
						+ "el escaneo equivocado y lo saca sin dar de baja el registro entero",
				example = "907",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long adjuntoId) {
}
