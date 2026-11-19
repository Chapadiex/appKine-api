package com.akine.assistant.domain.exception;

/** Las acciones del asistente estan apagadas ({@code akine.asistente.acciones.enabled}) o el centro apago el asistente: 409. */
public class AccionesDeshabilitadasException extends RuntimeException {

	public AccionesDeshabilitadasException() {
		super("Las acciones del asistente estan apagadas en este servidor o en este centro.");
	}
}
