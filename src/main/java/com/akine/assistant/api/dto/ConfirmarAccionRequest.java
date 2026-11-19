package com.akine.assistant.api.dto;

import com.akine.assistant.domain.exception.AccionInvalidaException;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAsistencia;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAtencion;
import com.akine.assistant.spi.Tarjeta.TarjetaDeLlegada;
import com.akine.assistant.spi.Tarjeta.TarjetaDePaciente;
import com.akine.assistant.spi.Tarjeta.TarjetaDePago;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.stream.Stream;

/** La tarjeta tal como quedo despues de que la persona la reviso: exactamente un bloque. */
@Schema(name = "ConfirmarAccion", description = "La tarjeta de la accion tal como quedo. Exactamente un "
		+ "bloque, el del tipo de la accion; los campos de solo lectura se ignoran")
public record ConfirmarAccionRequest(
		TarjetaDeAsistencia asistencia,
		TarjetaDePago pago,
		TarjetaDeAtencion atencion,
		TarjetaDeLlegada llegada,
		TarjetaDePaciente paciente) {

	/** @throws AccionInvalidaException si no viene exactamente un bloque (400) */
	public Tarjeta aTarjeta() {
		Tarjeta[] bloques = Stream.of(asistencia, pago, atencion, llegada, paciente)
				.filter(Objects::nonNull).toArray(Tarjeta[]::new);
		if (bloques.length != 1) {
			throw new AccionInvalidaException();
		}
		return bloques[0];
	}
}
