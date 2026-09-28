package com.akine.encounter.application;

import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.SesionMedicion;
import com.akine.resource.spi.MedicionTipo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una medicion tomada, tal como sale del servicio.
 *
 * <p><b>Todo lo que describe la medida sale del snapshot de la fila</b>, no del catalogo: el
 * nombre, la unidad, el tipo y la version son los del instante del registro. Es lo que hace que
 * un examen de hace seis meses siga diciendo lo que decia aunque el test se haya renombrado, se
 * haya cambiado de unidad o se haya dado de baja.
 *
 * <p>No lleva rango. El rango vive en el catalogo y se evalua <b>al registrar</b>: publicarlo en
 * la lectura invitaria a una pantalla a marcar en rojo una medicion vieja perfectamente valida
 * porque el catalogo cambio despues.
 */
public record MedicionView(

		long id,

		long definicionId,

		String codigo,

		String nombre,

		MedicionTipo tipo,

		/** {@code null} solo cuando el tipo es TEXTO o BOOLEANO. */
		String unidad,

		/** Version de la definicion contra la que se valido esta medicion. */
		long definicionVersion,

		LateralidadMedicion lateralidad,

		BigDecimal valorNumerico,

		String valorTexto,

		Boolean valorBooleano,

		String nota,

		Instant registradaEn,

		long version) {

	public static MedicionView de(SesionMedicion medicion) {
		return new MedicionView(
				medicion.getId(),
				medicion.getDefinicionId(),
				medicion.getDefinicionCodigo(),
				medicion.getDefinicionNombre(),
				medicion.getDefinicionTipo(),
				medicion.getDefinicionUnidad(),
				medicion.getDefinicionVersion(),
				medicion.getLateralidad(),
				medicion.getValorNumerico(),
				medicion.getValorTexto(),
				medicion.getValorBooleano(),
				medicion.getNota(),
				medicion.getRegistradaEn(),
				medicion.getVersion());
	}
}
