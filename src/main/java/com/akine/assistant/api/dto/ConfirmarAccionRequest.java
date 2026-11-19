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
		if (bloques.length != 1 || fueraDeLimite()) {
			throw new AccionInvalidaException();
		}
		return bloques[0];
	}

	/**
	 * La tarjeta no pasa por los DTO de cada pantalla, asi que sus topes van aca: los de la nota de
	 * cierre y lo que una pantalla puede cargar de una vez. Sin esto, una nota mas larga que la columna
	 * fallaria recien al cerrar, con los tratamientos ya cargados.
	 */
	private boolean fueraDeLimite() {
		if (atencion != null) {
			return (atencion.nota() != null && atencion.nota().length() > MAX_NOTA)
					|| atencion.tratamientos().size() > MAX_TRATAMIENTOS;
		}
		if (pago != null) {
			return pago.imputaciones().size() > MAX_IMPUTACIONES;
		}
		if (asistencia != null) {
			return asistencia.clases().size() > MAX_CLASES
					|| asistencia.clases().stream().anyMatch(c -> c.items().size() > MAX_ITEMS);
		}
		return false;
	}

	static final int MAX_NOTA = 2000;
	static final int MAX_TRATAMIENTOS = 30;
	static final int MAX_IMPUTACIONES = 50;
	static final int MAX_CLASES = 5;
	static final int MAX_ITEMS = 200;
}
