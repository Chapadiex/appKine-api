package com.akine.encounter.api.dto;

import com.akine.encounter.application.MedicionComparadaView;
import com.akine.encounter.domain.LateralidadMedicion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Una medida con su valor de esta sesion y el de la sesion cerrada anterior.
 *
 * <h2>Los tres desenlaces, y ninguno es un error</h2>
 *
 * <pre>
 *   actual != null, anterior == null   se tomo hoy por primera vez, o no hay baseline
 *   actual == null, anterior != null   se tomo la vez pasada y todavia no hoy
 *   los dos != null                    hay con que comparar
 * </pre>
 *
 * <p><b>El segundo caso es el que hace innecesario un endpoint de "copiar".</b> La pantalla
 * muestra el valor anterior al lado del campo vacio y el profesional lo ajusta; escribirlo es un
 * registro normal, con alguien detras. "Copiar nunca guarda sin revision" es una regla de la
 * pantalla, y el backend la sostiene <b>no teniendo como romperla</b>.
 *
 * <h2>{@code delta} viene ausente mas seguido de lo que parece, y a proposito</h2>
 *
 * <p>Solo se calcula cuando los dos valores existen, los dos son numericos y <b>las dos unidades
 * coinciden</b>. Si el centro cambio la unidad del test entre las dos sesiones, los 90 de marzo y
 * los 90 de septiembre no son el mismo numero: restarlos daria cero y diria que no hubo cambio. En
 * ese caso {@code unidadesDifieren} viene en {@code true} y la pantalla tiene que mostrar las dos
 * unidades sin restar nada.
 */
@Schema(name = "MedicionComparada",
		description = "Una medida de hoy contra la de la sesion cerrada anterior")
public record MedicionComparadaResponse(

		@Schema(example = "12")
		long definicionId,

		@Schema(example = "ROM_RODILLA_FLEX")
		String codigo,

		@Schema(example = "ROM de rodilla en flexion")
		String nombre,

		@Schema(example = "IZQUIERDA")
		LateralidadMedicion lateralidad,

		@Schema(description = "Lo medido en esta sesion. Ausente si todavia no se cargo")
		MedicionResponse actual,

		@Schema(description = "Lo medido en la sesion cerrada anterior. Ausente si no hay "
				+ "baseline")
		MedicionResponse anterior,

		@Schema(description = "actual - anterior. **Ausente cuando no corresponde restar**: "
				+ "falta un lado, el valor no es numerico, o las unidades difieren",
				example = "15.0")
		BigDecimal delta,

		@Schema(description = "true cuando los dos valores existen y sus unidades NO coinciden. "
				+ "**No restes**: mostra las dos", example = "false")
		boolean unidadesDifieren) {

	public static MedicionComparadaResponse de(MedicionComparadaView vista) {
		return new MedicionComparadaResponse(
				vista.definicionId(),
				vista.codigo(),
				vista.nombre(),
				vista.lateralidad(),
				vista.actual() == null ? null : MedicionResponse.de(vista.actual()),
				vista.anterior() == null ? null : MedicionResponse.de(vista.anterior()),
				vista.delta(),
				vista.unidadesDifieren());
	}
}
