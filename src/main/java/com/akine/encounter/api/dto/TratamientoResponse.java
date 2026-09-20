package com.akine.encounter.api.dto;

import com.akine.encounter.application.TratamientoView;
import com.akine.encounter.domain.TipoDatoParametro;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Una intervencion realizada, como la devuelve la API.
 *
 * <p>El codigo y el nombre de la practica y el nombre del espacio salen del <b>snapshot</b> de la
 * fila, no de resolverlos contra el catalogo. Es el punto de tenerlos congelados: una sesion de
 * marzo leida en septiembre dice que practica fue <b>en marzo</b>.
 */
@Schema(
		name = "Tratamiento",
		description = "Intervencion realmente aplicada en la atencion, con su espacio y sus "
				+ "parametros tipados.")
public record TratamientoResponse(

		@Schema(example = "501") long id,

		@Schema(example = "77") long sesionId,

		@Schema(
				description = "Secuencia **cronologica** dentro de la visita. La asigna el "
						+ "servidor y **no se reutiliza**: dar de baja una intervencion no libera "
						+ "su numero. No hay operacion de reordenar.",
				example = "2")
		int orden,

		@Schema(example = "41") long practicaId,

		@Schema(description = "Codigo de la practica **al momento de registrar**", example = "KIN-ELE")
		String practicaCodigo,

		@Schema(description = "Nombre de la practica **al momento de registrar**", example = "Electroterapia")
		String practicaNombre,

		@Schema(example = "TENS convencional") String tecnica,

		@Schema(example = "Lumbar") String zona,

		@Schema(allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"})
		String lateralidad,

		@Schema(example = "20") Integer duracionMinutos,

		@Schema(description = "Quien aplico esta intervencion (co-atencion)", example = "88")
		long profesionalMembershipId,

		@Schema(description = "Espacio realmente utilizado", example = "12") Long espacioId,

		@Schema(description = "Nombre del espacio **al momento de registrar**", example = "Box 3")
		String espacioNombre,

		@Schema(example = "Tolero bien") String observacion,

		Instant registradoEn,

		@Schema(description = "`false` tras la baja logica") boolean vigente,

		List<ParametroResponse> parametros,

		@Schema(
				description = "Version de la **SESION** despues de esta escritura. Viaja para que "
						+ "la pantalla no tenga que repedir la sesion: escribir un tratamiento "
						+ "hace avanzar esa version, y un cliente que siguiera mandando la vieja "
						+ "comeria un 409 en su proxima operacion.",
				example = "4")
		long sesionVersion) {

	/** Un parametro tipado, con el valor ya desempaquetado de la columna que le toca. */
	@Schema(name = "ParametroDeTratamientoLeido")
	public record ParametroResponse(
			@Schema(example = "intensidad") String clave,
			@Schema(allowableValues = {"NUMERICO", "TEXTO", "BOOLEANO"}) TipoDatoParametro tipoDato,
			@Schema(example = "25.5") BigDecimal valorNumerico,
			String valorTexto,
			Boolean valorBooleano,
			@Schema(example = "mA") String unidad,
			int orden) {

		static ParametroResponse de(TratamientoView.ParametroView parametro) {
			return new ParametroResponse(
					parametro.clave(),
					parametro.tipoDato(),
					parametro.valorNumerico(),
					parametro.valorTexto(),
					parametro.valorBooleano(),
					parametro.unidad(),
					parametro.orden());
		}
	}

	public static TratamientoResponse de(TratamientoView vista) {
		return new TratamientoResponse(
				vista.id(),
				vista.sesionId(),
				vista.orden(),
				vista.practicaId(),
				vista.practicaCodigo(),
				vista.practicaNombre(),
				vista.tecnica(),
				vista.zona(),
				vista.lateralidad(),
				vista.duracionMinutos(),
				vista.profesionalMembershipId(),
				vista.espacioId(),
				vista.espacioNombre(),
				vista.observacion(),
				vista.registradoEn(),
				vista.vigente(),
				vista.parametros().stream().map(ParametroResponse::de).toList(),
				vista.sesionVersion());
	}
}
