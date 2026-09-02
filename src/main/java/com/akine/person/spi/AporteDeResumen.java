package com.akine.person.spi;

import java.util.List;

/**
 * El bloque de Paciente 360 que aporta un modulo.
 *
 * <p>Dos formas y nada mas: <b>indicadores</b>, que son los numeros que la ficha muestra arriba
 * —"2 turnos futuros", "4.500 de deuda"—, y <b>hitos</b>, que son hechos datados que arman una
 * linea de tiempo corta. Todo lo que no entre en esas dos formas no es resumen: es el detalle, y
 * el detalle se pide al modulo duenio con su propio permiso.
 *
 * @param seccion     igual al {@code seccion()} de su contribuyente. Se repite en el aporte para
 *                    que el consumidor no tenga que correlacionar dos listas por posicion
 * @param indicadores puede venir vacia
 * @param hitos       puede venir vacia, ordenada por el contribuyente
 */
public record AporteDeResumen(
		String seccion,
		List<IndicadorDeResumen> indicadores,
		List<HitoDeResumen> hitos) {

	public AporteDeResumen {
		indicadores = indicadores == null ? List.of() : List.copyOf(indicadores);
		hitos = hitos == null ? List.of() : List.copyOf(hitos);
	}

	/** Aporte sin datos. No es lo mismo que una seccion omitida por permiso. */
	public static AporteDeResumen vacio(String seccion) {
		return new AporteDeResumen(seccion, List.of(), List.of());
	}
}
