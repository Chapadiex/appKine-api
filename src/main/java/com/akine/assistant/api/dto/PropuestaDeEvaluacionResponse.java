package com.akine.assistant.api.dto;

import com.akine.assistant.application.PropuestaDeEvaluacion;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Como repartir el texto dictado entre los campos de la evaluacion. Es una propuesta: no se
 * escribio nada en la sesion. Cada campo es opcional.
 */
@Schema(name = "PropuestaDeEvaluacionDictada")
public record PropuestaDeEvaluacionResponse(
		@Schema(description = "Dolor EVA, de 0 a 10", minimum = "0", maximum = "10") Integer dolorEva,
		@Schema(description = "Zona del dolor", maxLength = 120) String dolorZona,
		@Schema(description = "Lado del dolor", allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"})
		String dolorLateralidad,
		@Schema(description = "Evolucion respecto de la sesion anterior",
				allowableValues = {"MEJOR", "IGUAL", "PEOR", "SIN_REFERENCIA"}) String evolucion,
		@Schema(description = "Objetivo de la sesion", maxLength = 500) String objetivoSesion,
		@Schema(description = "Motivo clinico", maxLength = 500) String motivoClinico,
		@Schema(description = "Limitacion funcional", maxLength = 500) String limitacionFuncional,
		@Schema(description = "Lo dicho que no entro en ningun campo; la pantalla ofrece llevarlo a las notas")
		String restante) {

	public static PropuestaDeEvaluacionResponse de(PropuestaDeEvaluacion p) {
		return new PropuestaDeEvaluacionResponse(p.dolorEva(), p.dolorZona(), p.dolorLateralidad(), p.evolucion(),
				p.objetivoSesion(), p.motivoClinico(), p.limitacionFuncional(), p.restante());
	}
}
