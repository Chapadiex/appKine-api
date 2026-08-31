package com.akine.encounter.api.dto;

import com.akine.encounter.domain.EvaluacionBase;
import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ModoSesion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * La evaluacion base de una sesion.
 *
 * <p><b>Ningun campo clinico es obligatorio, y es una regla de negocio.</b> "Seguimiento no exige
 * examen completo": una sesion de seguimiento carga dolor y evolucion y nada mas, y esa es la
 * mayoria de las sesiones de un tratamiento. Exigir cualquiera de ellos obligaria al profesional a
 * inventar datos clinicos para poder guardar.
 *
 * <p>Lo unico obligatorio es la {@code version}, que no es un dato clinico sino el control que
 * impide que dos pestanas se pisen.
 */
@Schema(
		name = "GuardarEvaluacion",
		description = "Evaluacion base de la atencion. Todos los campos clinicos son opcionales: "
				+ "una sesion de seguimiento carga dolor y evolucion y nada mas.")
public record GuardarEvaluacionRequest(

		@Schema(
				description = "Como se carga. **No condiciona que campos hacen falta**: es una "
						+ "decision de la pantalla sobre cuanto mostrar. Si condicionara campos, "
						+ "un profesional que empieza en rapida y necesita anotar una cosa mas "
						+ "tendria que cambiar de modo en medio de una atencion.",
				allowableValues = {"RAPIDA", "COMPLETA"},
				example = "RAPIDA")
		ModoSesion modo,

		@Schema(
				description = "Lo que trae al paciente, en palabras del profesional. **No es un "
						+ "diagnostico**: el diagnostico es un acto medico que este sistema no "
						+ "registra.",
				example = "Dolor lumbar al agacharse, desde hace dos semanas")
		@Size(max = 500) String motivoClinico,

		@Schema(description = "Escala visual analogica, 0 a 10", example = "6")
		@Min(0) @Max(10) Integer dolorEva,

		@Schema(description = "Zona corporal referida", example = "Lumbar")
		@Size(max = 120) String dolorZona,

		@Schema(
				description = "De que lado. `NO_APLICA` no es lo mismo que dejarlo vacio: una zona "
						+ "central no tiene lado, y decirlo distingue \"no corresponde\" de \"no lo "
						+ "cargue\". **Exige zona**: \"derecha\" de que.",
				allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"},
				example = "NO_APLICA")
		Lateralidad dolorLateralidad,

		@Schema(
				description = "Como viene respecto de la sesion anterior. `SIN_REFERENCIA` es el de "
						+ "la primera sesion: decir \"igual\" cuando no hay contra que comparar "
						+ "seria inventar un dato clinico.",
				allowableValues = {"MEJOR", "IGUAL", "PEOR", "SIN_REFERENCIA"},
				example = "MEJOR")
		Evolucion evolucion,

		@Schema(
				description = "Que se busca en ESTA sesion. Distinto del objetivo del Plan de "
						+ "Tratamiento (M11), que es del tratamiento completo.",
				example = "Reducir dolor en flexion")
		@Size(max = 500) String objetivoSesion,

		@Schema(description = "Que no puede hacer el paciente hoy", example = "No puede atarse los cordones")
		@Size(max = 500) String limitacionFuncional,

		@Schema(
				description = "Version que el cliente leyo. Un 409 `concurrent-modification` "
						+ "significa que alguien guardo antes y hay que recargar.",
				example = "3")
		@NotNull @PositiveOrZero Long version) {

	public EvaluacionBase aDominio() {
		return new EvaluacionBase(
				modo, motivoClinico, dolorEva, dolorZona, dolorLateralidad,
				evolucion, objetivoSesion, limitacionFuncional);
	}
}
