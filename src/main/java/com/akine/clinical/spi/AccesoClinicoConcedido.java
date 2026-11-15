package com.akine.clinical.spi;

/**
 * El resultado de una lectura clinica autorizada: por que via entro, para que quien lee lo audite.
 *
 * @param conRelacionAsistencial {@code true} si el actor atiende a la persona
 * @param justificacion          el motivo declarado cuando entro sin relacion; {@code null} si no
 * @param viaSupportAccess       {@code true} si el permiso salio de un acceso de soporte
 */
public record AccesoClinicoConcedido(
		boolean conRelacionAsistencial,
		String justificacion,
		boolean viaSupportAccess) {

	public String via() {
		return conRelacionAsistencial ? "RELACION_ASISTENCIAL" : "JUSTIFICACION";
	}
}
