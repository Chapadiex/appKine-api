package com.akine.activity.api.dto;

import com.akine.activity.application.ResultadoDeAsistencia;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Como termino un registro de asistencia.
 *
 * <p>Los tres desenlaces viajan explicitos en vez de deducirse de comparar campos, porque la
 * pantalla los muestra distinto: se marco, se corrigio, o el pedido repetia lo ya registrado y no
 * se escribio nada.
 */
@Schema(
		name = "ResultadoDeAsistencia",
		description = "La asistencia y como quedo la inscripcion. `registrada` y `corregida` en "
				+ "false significa que el pedido era un reintento y no cambio nada.")
public record ResultadoDeAsistenciaResponse(

		AsistenciaResponse asistencia,

		@Schema(description = "La reserva, con su estado ya proyectado a ASISTIO o AUSENTE")
		InscripcionResponse inscripcion,

		@Schema(description = "true si esta llamada creo el hecho", example = "true")
		boolean registrada,

		@Schema(description = "true si esta llamada corrigio un hecho previo", example = "false")
		boolean corregida,

		@Schema(description = "Que le hizo este registro al saldo de pases de la persona "
				+ "(AKINE-08.07). Ausente si el pedido repetia lo ya registrado y no se escribio nada.")
		ConsumoDeCreditoResponse consumoDeCredito,

		@Schema(description = "Que le hizo este registro a la deuda de la persona en una oferta "
				+ "`POR_CLASE` (U-39, RF-M18-008, desde 0.124.0). Ausente si el pedido repetia lo ya "
				+ "registrado y no se escribio nada.")
		DevengoDeClaseResponse devengoDeClase) {

	public static ResultadoDeAsistenciaResponse de(ResultadoDeAsistencia resultado) {
		return new ResultadoDeAsistenciaResponse(
				AsistenciaResponse.de(resultado.asistencia()),
				InscripcionResponse.de(resultado.inscripcion()),
				resultado.registrada(),
				resultado.corregida(),
				ConsumoDeCreditoResponse.de(resultado.consumo()),
				DevengoDeClaseResponse.de(resultado.devengo()));
	}
}
