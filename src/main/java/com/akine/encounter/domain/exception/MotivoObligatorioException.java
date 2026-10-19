package com.akine.encounter.domain.exception;

/**
 * Falta el motivo que la operacion exige. <b>400 {@code validation-error}</b> con el campo en
 * {@code errors}, igual que una violacion de Bean Validation.
 *
 * <p>DP-43 exige motivo en la atencion sin turno y en el reemplazo del profesional. El DTO ya lo
 * valida; esto es la red para quien llame al servicio sin pasar por HTTP, que no puede dejar una
 * atencion sin turno o un reemplazo sin explicar.
 */
public class MotivoObligatorioException extends RuntimeException {

	/** Largo maximo del motivo: el de las columnas de V115. */
	public static final int MAXIMO = 500;

	private final String campo;

	public MotivoObligatorioException(String campo, String detalle) {
		super(detalle);
		this.campo = campo;
	}

	/** El motivo recortado, o la excepcion si falta o excede {@link #MAXIMO}. */
	public static String exigir(String campo, String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new MotivoObligatorioException(campo, "El motivo es obligatorio");
		}
		String recortado = motivo.strip();
		if (recortado.length() > MAXIMO) {
			throw new MotivoObligatorioException(campo,
					"El motivo admite como maximo " + MAXIMO + " caracteres");
		}
		return recortado;
	}

	public String getCampo() {
		return campo;
	}
}
