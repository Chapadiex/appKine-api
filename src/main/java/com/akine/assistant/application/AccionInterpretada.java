package com.akine.assistant.application;

import com.akine.assistant.spi.DatosDePersona;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el interpretador de acciones entendio de una frase. Recien despues de
 * {@link ControlDeAccion} se puede usar: antes, cualquier campo puede ser un invento del modelo.
 *
 * @param accion         una de {@link #ACCIONES}, o {@link #NINGUNA_ACCION}
 * @param persona        de quien se habla (pago, atencion, edicion)
 * @param modoAsistencia {@code TODOS_MENOS}, {@code SOLO} o {@code null}
 * @param nombres        los nombrados en la asistencia
 */
record AccionInterpretada(
		String accion,
		String persona,
		String clase,
		String modoAsistencia,
		List<String> nombres,
		BigDecimal monto,
		String medio,
		List<String> practicas,
		DatosDePersona datos) {

	static final String NINGUNA_ACCION = "NINGUNA";
	static final String ASISTENCIA_CLASE = "ASISTENCIA_CLASE";
	static final String PAGO = "PAGO";
	static final String ATENCION = "ATENCION";
	static final String PACIENTE_ALTA = "PACIENTE_ALTA";
	static final String PACIENTE_EDICION = "PACIENTE_EDICION";

	/** Las que el modelo puede elegir. {@code LLEGADA} no: la decide el permiso de quien habla. */
	static final List<String> ACCIONES = List.of(ASISTENCIA_CLASE, PAGO, ATENCION, PACIENTE_ALTA, PACIENTE_EDICION);

	static final AccionInterpretada NINGUNA = new AccionInterpretada(NINGUNA_ACCION, null, null, null, List.of(),
			null, null, List.of(), DatosDePersona.VACIO);

	AccionInterpretada {
		nombres = nombres == null ? List.of() : List.copyOf(nombres);
		practicas = practicas == null ? List.of() : List.copyOf(practicas);
		datos = datos == null ? DatosDePersona.VACIO : datos;
	}

	boolean ninguna() {
		return NINGUNA_ACCION.equals(accion);
	}
}
