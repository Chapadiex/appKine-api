package com.akine.reporting.spi;

import java.util.List;

/**
 * Una fila de detalle. Las celdas ya vienen formateadas como texto por el modulo que las produjo.
 *
 * <p><b>Texto y no objetos tipados a proposito.</b> La fila existe para el CSV de RF-M23-006 y
 * para la tabla que lo espeja en pantalla, y el que sabe como se escribe un importe o una fecha de
 * este dominio es el modulo duenio. Darle estructura obligaria a un tipo de celda por columna y a
 * que {@code reporting} conociera el modelo de los cinco modulos fuente, que es exactamente lo que
 * el diseno evita.
 *
 * <p><b>Ninguna fila lleva identificadores de persona.</b> No es una omision: una fila economica
 * por paciente y por oferta permitiria deducir que prestacion recibio quien —contenido clinico
 * reconstruido desde la economia— sin haber pasado por {@code hc:read} ni haber dejado un acceso
 * auditado. Quien necesita ese detalle entra por la cuenta corriente de M18, con su permiso.
 */
public record FilaDeReporte(List<String> celdas) {

	public FilaDeReporte {
		celdas = celdas == null ? List.of() : List.copyOf(celdas);
	}

	public static FilaDeReporte de(String... celdas) {
		return new FilaDeReporte(List.of(celdas));
	}
}
