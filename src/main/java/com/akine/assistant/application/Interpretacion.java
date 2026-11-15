package com.akine.assistant.application;

import com.akine.assistant.spi.Intencion;

import java.util.Objects;

/**
 * Lo que el interpretador entendio de una pregunta, ya validado: la intencion es una del catalogo y
 * cada campo es {@code null} o un valor legible. Los campos que la intencion no usa vienen en
 * {@code null} aunque el modelo los haya llenado.
 *
 * @param persona el nombre tal como lo escribio el usuario, sin titulos; para
 *                {@link Intencion#ASISTENCIA_PERSONA} y {@link Intencion#SESION_DE_PERSONA}
 * @param clase   el nombre de la actividad en minusculas; para {@link Intencion#ASISTENTES_CLASE}
 * @param periodo un valor del vocabulario de {@link ResolutorDePeriodo}, o {@code null}
 * @param reporte {@code ECONOMICO}, {@code TURNOS} o {@code CLASES}; para {@link Intencion#REPORTE}
 */
public record Interpretacion(Intencion intencion, String persona, String clase, String periodo, String reporte) {

	public static final Interpretacion FUERA_DE_ALCANCE =
			new Interpretacion(Intencion.FUERA_DE_ALCANCE, null, null, null, null);

	public Interpretacion {
		Objects.requireNonNull(intencion, "intencion");
	}

	public boolean usaPersona() {
		return intencion.usaPersona();
	}

	public boolean usaClase() {
		return intencion.usaClase();
	}

	public boolean usaReporte() {
		return intencion.usaReporte();
	}

	public boolean fueraDeAlcance() {
		return intencion == Intencion.FUERA_DE_ALCANCE;
	}
}
