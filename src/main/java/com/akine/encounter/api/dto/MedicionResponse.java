package com.akine.encounter.api.dto;

import com.akine.encounter.application.MedicionView;
import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.resource.spi.MedicionTipo;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una medicion tomada.
 *
 * <p><b>Todo lo que describe la medida sale del snapshot de la fila</b>, no del catalogo: el
 * nombre, la unidad, el tipo y la version son los del instante del registro. Es lo que hace que un
 * examen de hace seis meses siga diciendo lo que decia aunque el test se haya renombrado, se haya
 * cambiado de unidad o se haya dado de baja.
 *
 * <p><b>No lleva el rango.</b> El rango vive en el catalogo y se evalua al registrar: publicarlo
 * en la lectura invitaria a una pantalla a marcar en rojo una medicion vieja perfectamente valida
 * porque el catalogo cambio despues.
 */
@Schema(name = "Medicion", description = "Una medida tomada en una sesion, con su significado "
		+ "congelado en el instante del registro")
public record MedicionResponse(

		@Schema(example = "900")
		long id,

		@Schema(description = "Definicion del catalogo que se midio", example = "12")
		long definicionId,

		@Schema(description = "Codigo copiado del catalogo al registrar",
				example = "ROM_RODILLA_FLEX")
		String codigo,

		@Schema(description = "Nombre copiado del catalogo al registrar. **Sobrevive a un "
				+ "renombre del test**", example = "ROM de rodilla en flexion")
		String nombre,

		@Schema(description = "Tipo copiado del catalogo al registrar", example = "NUMERICO")
		MedicionTipo tipo,

		@Schema(description = "Unidad copiada del catalogo al registrar. Ausente solo en TEXTO y "
				+ "BOOLEANO. **Si el centro cambio la unidad despues, esta sigue diciendo la "
				+ "vieja**, que es el punto", example = "grados")
		String unidad,

		@Schema(description = "Version de la definicion contra la que se valido esta medicion",
				example = "2")
		long definicionVersion,

		@Schema(description = "De que lado. **BILATERAL no existe**: una medicion bilateral son "
				+ "dos mediciones", example = "IZQUIERDA")
		LateralidadMedicion lateralidad,

		@Schema(example = "92.5")
		BigDecimal valorNumerico,

		@Schema(example = "Marcha antalgica")
		String valorTexto,

		@Schema(example = "true")
		Boolean valorBooleano,

		@Schema(description = "Como se tomo la medida. No entra en ninguna comparacion")
		String nota,

		@Schema(example = "2026-09-20T13:44:10Z")
		Instant registradaEn,

		@Schema(description = "Version de la fila de la medicion", example = "1")
		long version) {

	public static MedicionResponse de(MedicionView vista) {
		return new MedicionResponse(
				vista.id(),
				vista.definicionId(),
				vista.codigo(),
				vista.nombre(),
				vista.tipo(),
				vista.unidad(),
				vista.definicionVersion(),
				vista.lateralidad(),
				vista.valorNumerico(),
				vista.valorTexto(),
				vista.valorBooleano(),
				vista.nota(),
				vista.registradaEn(),
				vista.version());
	}
}
