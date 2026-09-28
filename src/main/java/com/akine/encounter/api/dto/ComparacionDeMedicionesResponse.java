package com.akine.encounter.api.dto;

import com.akine.encounter.application.ComparacionDeMedicionesView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Esta sesion contra la cerrada anterior (RF-M14-004, la mitad "cambio" del requisito).
 *
 * <p><b>Se calcula al leer y no se guarda nada.</b> No hay delta persistido ni FK a "la medicion
 * anterior": guardarlos seria una segunda copia de la verdad que miente el dia que alguien
 * enmiende la sesion anterior. Es lo mismo que el timeline de 04.02 y el avance del plan de 04.04.
 *
 * <p><b>El baseline se acota al mismo Caso</b> cuando la sesion tiene caso, y cae a "la anterior
 * del paciente" solo cuando no lo tiene: 04.03 admite varios casos abiertos a la vez —una rodilla
 * y un hombro— y comparar el ROM de rodilla contra la sesion del hombro es comparar contra nada.
 *
 * <p><b>Sin baseline NO es un error</b>: {@code sesionAnteriorId} viene ausente y cada fila trae
 * {@code anterior} ausente. Una primera evaluacion, o una re-evaluacion cuya sesion previa quedo
 * sin cerrar, son situaciones normales.
 */
@Schema(name = "ComparacionDeMediciones",
		description = "Las medidas de esta sesion contra las de la sesion cerrada anterior")
public record ComparacionDeMedicionesResponse(

		@Schema(description = "La sesion que hace de baseline. **Ausente si no hay ninguna**, y "
				+ "eso no es un error", example = "488")
		Long sesionAnteriorId,

		@Schema(description = "true si la comparacion se acoto al Caso de la sesion. La pantalla "
				+ "lo necesita para no dejar creer que se comparo contra toda la historia del "
				+ "paciente", example = "true")
		boolean acotadaAlCaso,

		@Schema(description = "Una fila por medida y lado, con la **union** de lo de hoy y lo de "
				+ "la sesion anterior. Las que se tomaron la vez pasada y todavia no hoy son "
				+ "precisamente las que el profesional va a volver a tomar")
		List<MedicionComparadaResponse> medidas) {

	public static ComparacionDeMedicionesResponse de(ComparacionDeMedicionesView vista) {
		return new ComparacionDeMedicionesResponse(
				vista.sesionAnteriorId(),
				vista.acotadaAlCaso(),
				vista.medidas().stream().map(MedicionComparadaResponse::de).toList());
	}
}
