package com.akine.encounter.domain.exception;

/**
 * La definicion de medicion no existe, o es de otro tenant (<b>404 siempre</b>).
 *
 * <p>Es la misma situacion que la excepcion homonima de {@code resource}, y son dos clases a
 * proposito: <b>quien rechaza es este modulo</b>. {@code resource.spi.MedicionDirectory} responde
 * existencia y estado, y no autoriza nada — la misma division que {@code HistoriaClinicaDirectory}
 * y {@code CasoDirectory} ya tenian. Compartir la clase obligaria a {@code encounter} a importar
 * {@code resource.domain}, que es justo lo que ArchUnit prohibe.
 *
 * <p>El {@code type} del Problem Detail SI es el mismo —{@code medicion-definicion-no-accesible}—
 * porque el catalogo de {@code platform.spi.problem} es unico para toda la API: el cliente maneja
 * una sola respuesta por situacion, la emita el modulo que la emita.
 */
public class MedicionDefinicionNoAccesibleException extends RuntimeException {

	private final long definicionId;

	public MedicionDefinicionNoAccesibleException(long definicionId) {
		super("Definicion de medicion no accesible: " + definicionId);
		this.definicionId = definicionId;
	}

	public long getDefinicionId() {
		return definicionId;
	}
}
