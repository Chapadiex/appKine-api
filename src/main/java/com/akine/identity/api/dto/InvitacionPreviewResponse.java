package com.akine.identity.api.dto;

import com.akine.identity.application.InvitacionPreview;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una invitacion como la ve el invitado, antes de decidir.
 *
 * <p>Deliberadamente distinta de {@link InvitacionResponse} y no una version recortada: son dos
 * publicos con dos preguntas distintas. El administrador quiere saber quien no respondio; el
 * invitado quiere saber <b>quien lo invita, a que y que le van a pedir a continuacion</b>.
 *
 * <p><b>Sin ningun id.</b> Quien presenta el token no pertenece a la organizacion —puede no
 * tener ni cuenta— y cada id que se le entregue es una pieza mas para adivinar el resto.
 *
 * @param requiereRegistro lo que decide si la pantalla pide nombre y contrasena. Es tambien lo
 *                         unico que este endpoint dice sobre la existencia de una cuenta, y solo
 *                         se lo dice a quien ya probo que llega a ese buzon
 */
@Schema(description = "Lo que el invitado necesita saber para decidir")
public record InvitacionPreviewResponse(

		@Schema(description = "Organizacion que invita", example = "Centro Belgrano")
		String organizacionNombre,
		@Schema(description = "Sede del vinculo, o null si es de toda la organizacion")
		String consultorioNombre,
		@Schema(example = "PROFESIONAL") String roleCode,
		@Schema(description = "Direccion a la que se emitio", example = "kine@centro.test")
		String email,
		@Schema(description = "Hasta cuando sirve el enlace") Instant expiraEn,
		@Schema(description = "Si al aceptar hay que crear la cuenta") boolean requiereRegistro) {

	public static InvitacionPreviewResponse de(InvitacionPreview preview) {
		return new InvitacionPreviewResponse(
				preview.organizacionNombre(),
				preview.consultorioNombre(),
				preview.roleCode(),
				preview.email(),
				preview.expiraEn(),
				preview.requiereRegistro());
	}
}
