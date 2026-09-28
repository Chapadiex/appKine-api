package com.akine.encounter.api.dto;

import com.akine.encounter.application.MedicionesDeSesionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * El examen fisico de una sesion, con su informe de completitud.
 *
 * <p><b>{@code completo} se informa y no gatea nada.</b> El enunciado pide "completo solo cuando
 * corresponde", y la decision de la etapa es que la completitud se calcula y se muestra, y
 * <b>no bloquea el cierre de la sesion</b>. Bloquearlo por una medicion faltante obligaria a
 * inventar un dato clinico para poder cerrar, que es lo que 06.02 ya declaro inaceptable, y es lo
 * mismo que 04.04 rechazo al negarse a cerrar un Caso por contador.
 *
 * <p>Las dos cuentas viajan al lado para que la pantalla pueda decir "12 de 18" en vez de un
 * booleano que no explica nada.
 */
@Schema(name = "MedicionesDeSesion",
		description = "Mediciones cargadas en la sesion y cuanto del catalogo cubren")
public record MedicionesDeSesionResponse(

		@Schema(description = "Las mediciones de esta sesion. Lista vacia si todavia no se cargo "
				+ "ninguna")
		List<MedicionResponse> mediciones,

		@Schema(description = "Cuantas definiciones DISTINTAS tienen al menos una medicion aca",
				example = "12")
		int definicionesCubiertas,

		@Schema(description = "Cuantas definiciones activas ve este tenant hoy: el denominador "
				+ "del informe", example = "18")
		int definicionesDisponibles,

		@Schema(description = "**No gatea el cierre.** true cuando toda definicion activa y "
				+ "visible tiene al menos una medicion en esta sesion", example = "false")
		boolean completo) {

	public static MedicionesDeSesionResponse de(MedicionesDeSesionView vista) {
		return new MedicionesDeSesionResponse(
				vista.mediciones().stream().map(MedicionResponse::de).toList(),
				vista.definicionesCubiertas(),
				vista.definicionesDisponibles(),
				vista.completo());
	}
}
