package com.akine.encounter.domain.exception;

/**
 * El valor enviado no es el que admite el tipo de la medida (<b>400</b>).
 *
 * <p>Cubre las dos mitades de la misma invariante, y las dos importan: que <b>falte</b> el valor
 * que corresponde deja una medicion que no mide nada, y que <b>sobre</b> otro es el principio de
 * una medicion que despues nadie puede comparar —un numero guardado como texto no se promedia, no
 * se grafica y no se resta contra el de la sesion anterior—.
 *
 * <p>400 y no 409, por el mismo motivo que {@code MedicionFueraDeRangoException}: es el cuerpo el
 * que esta mal, y reintentarlo falla igual.
 *
 * <p>Un solo {@code type} para las dos familias, con {@code motivo} como propiedad extra: para la
 * pantalla el desenlace es el mismo —decir que valor se espera— y publicar dos codigos obligaria
 * al cliente a manejar dos respuestas para una sola correccion. Mismo criterio que
 * {@code ARCHIVO_NO_ACEPTADO} y {@code TURNO_TRANSICION_NO_PERMITIDA}.
 */
public class MedicionTipoIncompatibleException extends RuntimeException {

	private final String codigo;
	private final String tipoEsperado;
	private final String motivo;

	public MedicionTipoIncompatibleException(String codigo, String tipoEsperado, String motivo) {
		super("La medida " + codigo + " es de tipo " + tipoEsperado + ": " + motivo);
		this.codigo = codigo;
		this.tipoEsperado = tipoEsperado;
		this.motivo = motivo;
	}

	public String getCodigo() {
		return codigo;
	}

	public String getTipoEsperado() {
		return tipoEsperado;
	}

	public String getMotivo() {
		return motivo;
	}
}
