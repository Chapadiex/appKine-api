package com.akine.clinical.domain;

/**
 * Enfermedades que la ficha de ingreso pregunta con una casilla. Lista del producto, no del
 * centro: cada una se guarda como antecedente {@link TipoAntecedente#MEDICO} con este codigo, asi
 * "pacientes con marcapasos" es un {@code WHERE} y no un parseo de texto.
 */
public enum EnfermedadDeIngreso {
	DIABETES("Diabetes"),
	HIPERTENSION("Hipertensión"),
	CARDIOPATIA("Cardiopatía"),
	MARCAPASOS("Marcapasos"),
	OSTEOPOROSIS("Osteoporosis"),
	ANTICOAGULADO("Anticoagulado"),
	EMBARAZO("Embarazo"),
	CANCER("Cáncer"),
	EPILEPSIA("Epilepsia"),
	RESPIRATORIA("Asma / EPOC");

	private final String etiqueta;

	EnfermedadDeIngreso(String etiqueta) {
		this.etiqueta = etiqueta;
	}

	public String etiqueta() {
		return etiqueta;
	}
}
