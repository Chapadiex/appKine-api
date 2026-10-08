package com.akine.contracting.application;

import com.akine.contracting.application.ImportacionAranceles.ResultadoFila;

import java.util.List;

/**
 * La confirmacion de una importacion de aranceles encontro al menos una fila que no entra
 * (B-7, RF-M16-007, CA-M16-007-04). No se escribio ninguna: el lote es todo o nada.
 *
 * <p>Lleva el resultado de TODAS las filas, no solo de las rechazadas, para que la pantalla
 * pueda mostrar el lote entero tal como lo vio el servidor bajo el lock.
 */
public class ImportacionArancelesRechazadaException extends RuntimeException {

	private final transient List<ResultadoFila> filas;

	public ImportacionArancelesRechazadaException(List<ResultadoFila> filas) {
		super("Importacion de aranceles rechazada: "
				+ filas.stream().filter(ResultadoFila::rechazada).count() + " de " + filas.size()
				+ " filas no entran");
		this.filas = List.copyOf(filas);
	}

	public List<ResultadoFila> getFilas() {
		return filas;
	}
}
