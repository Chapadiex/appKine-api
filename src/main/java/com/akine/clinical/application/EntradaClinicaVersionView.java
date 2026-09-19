package com.akine.clinical.application;

import com.akine.clinical.domain.EntradaClinicaVersion;

import java.time.Instant;

/**
 * Una version de entrada clinica tal como sale del servicio (RF-M09-006).
 *
 * <p>Trae el cuerpo completo de <b>esa</b> version, no un diff. Un diff seria mas compacto y menos
 * util: lo que el profesional necesita leer es que decia la historia en ese momento, no que
 * caracteres cambiaron.
 *
 * <p>No lleva {@code id}: la version se identifica por su numero dentro de la entrada, que es lo
 * unico que significa algo para quien la lee. Exponer la clave primaria invitaria a una API que
 * las direccione sueltas, y una version fuera de su entrada no tiene interpretacion clinica.
 */
public record EntradaClinicaVersionView(
		int numeroVersion,
		String cuerpo,
		String motivoEnmienda,
		Instant registradaEn,
		long registradaPor) {

	public static EntradaClinicaVersionView de(EntradaClinicaVersion version) {
		return new EntradaClinicaVersionView(
				version.getNumeroVersion(),
				version.getCuerpo(),
				version.getMotivoEnmienda(),
				version.getRegistradaEn(),
				version.getRegistradaPor());
	}
}
