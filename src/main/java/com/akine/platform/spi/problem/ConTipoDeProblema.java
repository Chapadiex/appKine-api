package com.akine.platform.spi.problem;

import java.util.Map;

/**
 * Una excepcion de negocio que sabe con que {@code type} viaja (AKINE-08.05).
 *
 * <p>Existe para las operaciones <b>en lote</b> que cruzan modulos. Un item que falla no puede
 * cortar el lote —"un fallo individual no oculta los resultados de los demas"— y tiene que
 * informar el <b>mismo</b> {@code problemType} que habria viajado en un {@code ProblemDetail}
 * individual. El lote vive en un modulo y la excepcion en otro, y el primero no puede importar
 * las excepciones del segundo (AGENT.md seccion 4, regla 2). Con esta interfaz el lote pregunta el
 * tipo sin conocer la clase.
 *
 * <p><b>No reemplaza al {@code ProblemHandler} de cada modulo</b>, que sigue armando el
 * {@code ProblemDetail} completo del camino individual. El tipo que devuelve
 * {@link #tipoDeProblema()} tiene que ser el mismo que pone ese handler.
 */
public interface ConTipoDeProblema {

	/** El {@code type} con el que esta excepcion viaja en el camino individual. */
	ProblemType tipoDeProblema();

	/**
	 * Las propiedades extra que el {@code ProblemHandler} del modulo agrega al
	 * {@code ProblemDetail} individual —por ejemplo {@code derivacionIds} en
	 * {@code derivacion-ambigua}—, para que el item de un lote viaje con lo mismo. Vacio por
	 * defecto: casi ningun tipo lleva extras. Tienen que ser las <b>mismas</b> claves y valores que
	 * pone el handler.
	 */
	default Map<String, Object> propiedadesDelProblema() {
		return Map.of();
	}
}
