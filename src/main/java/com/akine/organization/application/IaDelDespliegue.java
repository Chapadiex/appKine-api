package com.akine.organization.application;

import com.akine.organization.spi.FuncionIa;

/**
 * Que funciones de IA ofrece este despliegue, segun sus propiedades ({@code akine.asistente.enabled},
 * {@code akine.evolucion.*}, {@code akine.biblioteca.enabled}). El centro solo puede usar lo que esta
 * prendido aca. Las sugerencias exigen ademas evolucion y biblioteca, igual que en {@code encounter}.
 */
public record IaDelDespliegue(boolean asistente, boolean evolucion, boolean sugerencias, boolean biblioteca) {

	public IaDelDespliegue {
		sugerencias = sugerencias && evolucion && biblioteca;
	}

	public boolean ofrece(FuncionIa funcion) {
		return switch (funcion) {
			case ASISTENTE -> asistente;
			case EVOLUCION -> evolucion;
			case SUGERENCIAS -> sugerencias;
			case BIBLIOTECA -> biblioteca;
		};
	}
}
