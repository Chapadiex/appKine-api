package com.akine.encounter.api.dto;

import com.akine.encounter.domain.ValorMedido;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * El valor de una medicion del examen fisico.
 *
 * <p><b>Los tres valores son opcionales aca y exactamente uno es obligatorio en el servidor.</b>
 * Cual corresponde lo decide el tipo de la definicion, que esta capa no conoce: declararlo con
 * anotaciones obligaria a decidir el tipo en {@code api}, que es donde no se toman decisiones de
 * negocio. La invariante la verifica {@link ValorMedido} en un solo lugar, y el 400 que sale de
 * ahi nombra el tipo esperado.
 *
 * <p><b>No lleva {@code version}.</b> El control optimista de 06.01 protege el borrador de la
 * sesion —un documento que dos pestanas editan entero—; aca cada fila es una medida sola y el
 * {@code PUT} es idempotente: dos escrituras de la misma medida son la misma medida. Exigir una
 * version obligaria al autosave a releer cada valor antes de guardarlo.
 *
 * <p><b>La lateralidad no esta en el cuerpo</b>: es parte de la identidad de la medicion —entra en
 * el unique junto con la sesion y la definicion— y viaja como parametro de la ruta. Un cuerpo que
 * pudiera cambiarla volveria al {@code PUT} no idempotente sobre su propia URL.
 */
@Schema(
		name = "RegistrarMedicion",
		description = "Valor medido. Exactamente uno de los tres campos de valor segun el tipo "
				+ "de la definicion")
public record RegistrarMedicionRequest(

		@Schema(
				description = "Valor de una medida NUMERICA o de ESCALA, en la unidad que la "
						+ "definicion declara. Se valida contra el rango vigente **en este "
						+ "momento**, y esa version queda copiada en la fila",
				example = "92.5")
		BigDecimal valorNumerico,

		@Schema(
				description = "Valor de una medida de TEXTO: el hallazgo descriptivo",
				example = "Marcha antalgica con claudicacion a los 50 m")
		@Size(max = 500, message = "El valor de texto no puede superar los 500 caracteres")
		String valorTexto,

		@Schema(
				description = "Valor de una medida BOOLEANA: presencia o ausencia del signo",
				example = "true")
		Boolean valorBooleano,

		@Schema(
				description = "Como se tomo la medida. **No reemplaza al valor y no entra en "
						+ "ninguna comparacion**",
				example = "Con dolor al final del rango")
		@Size(max = 280, message = "La nota no puede superar los 280 caracteres")
		String nota) {

	/** El valor tal como lo espera el dominio, que es quien verifica que corresponda al tipo. */
	public ValorMedido valor() {
		return new ValorMedido(valorNumerico, valorTexto, valorBooleano);
	}
}
