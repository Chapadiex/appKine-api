package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Alta de un Caso Clinico (RF-M10-001).
 *
 * <p>El diagnostico presuntivo es obligatorio y se valida <b>dos veces</b> a proposito: aca con
 * {@code @NotBlank}, que da un 400 {@code validation-error} con el campo señalado, y otra vez en
 * el dominio. La de aca es comodidad para el formulario; la del dominio es la que vale, porque
 * ningun camino de escritura —ni uno futuro que no pase por este endpoint— puede crear un caso sin
 * decir que se esta tratando.
 */
@Schema(description = "Alta de un Caso Clinico dentro de una Historia Clinica (RF-M10-001)")
public record AbrirCasoClinicoRequest(

		@Schema(description = "Oferta que motiva el caso. Tiene que existir en la sede del "
				+ "contexto y estar vigente (RN-M10-006)",
				example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El caso exige declarar la oferta que lo motiva")
		Long ofertaId,

		@Schema(description = "Lo que se esta tratando. Es contenido clinico y es obligatorio: un "
				+ "caso sin el es una fila que en la lista de casos del paciente no se distingue "
				+ "de la de al lado",
				example = "Gonalgia derecha post-artroscopia",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El caso exige un diagnostico presuntivo")
		@Size(max = 500, message = "El diagnostico no puede superar los 500 caracteres")
		String diagnosticoPresuntivo,

		@Schema(description = "A donde se quiere llegar. Opcional: se suele definir despues de la "
				+ "primera evaluacion",
				example = "Recuperar flexion completa y marcha sin claudicacion")
		@Size(max = 1000, message = "El objetivo no puede superar los 1000 caracteres")
		String objetivoTerapeutico,

		@Schema(description = "Equipo tratante inicial. PUEDE VENIR VACIO: un caso abierto desde "
				+ "el mostrador todavia no sabe quien lo va a atender, y exigirlo obligaria a "
				+ "inventarlo")
		@Valid
		List<IntegranteDelEquipoRequest> equipo,

		@Schema(description = "El profesional ya vio los casos ACTIVOS que coinciden en oferta y "
				+ "declara que este es otro. Sin esto, un alta que coincide se rechaza con 409 "
				+ "caso-clinico-posible-duplicado y la lista de candidatos. NO saltea ningun "
				+ "invariante: RN-M10-002 admite varios casos activos, y esto registra que "
				+ "alguien miro.",
				example = "false")
		Boolean confirmaPosibleDuplicado) {
}
