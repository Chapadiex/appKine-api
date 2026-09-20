package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.ParametroInvalidoException;

import java.util.List;

/**
 * Una intervencion tal como la declara quien la registra, antes de resolverse contra los catalogos.
 *
 * <p>Es el comando de la etapa. Existe separado de {@link TratamientoRealizado} por la regla de
 * capas —los DTO de {@code api} no llegan a {@code application}— y es el mismo reparto que
 * {@code EvaluacionBase} tiene con las columnas de evaluacion de la sesion.
 *
 * <p>Lo que <b>no</b> trae y el servicio resuelve: el codigo y el nombre de la practica, y el
 * nombre del espacio. Son <b>snapshots</b> y salen de {@code resource.spi}, no del cliente:
 * dejarlos entrar desde afuera permitiria que la historia clinica afirme que se aplico una
 * practica con un nombre que esa practica nunca tuvo.
 *
 * @param profesionalMembershipId co-atencion ({@code plan_sesiones} 10.5). {@code null} significa
 *                                "el profesional de la sesion", que es el caso normal
 * @param espacioId               espacio realmente utilizado (RF-M04-005). {@code null} legitimo:
 *                                no toda oferta requiere espacio
 */
public record TratamientoAplicado(
		long practicaId,
		String tecnica,
		String zona,
		Lateralidad lateralidad,
		Integer duracionMinutos,
		Long profesionalMembershipId,
		Long espacioId,
		String observacion,
		List<ParametroAplicado> parametros) {

	public TratamientoAplicado {
		parametros = parametros == null ? List.of() : List.copyOf(parametros);
	}

	/**
	 * Valida lo que seria <b>falso</b>, no lo que falta.
	 *
	 * <p>Es la regla que 06.02 dejo fijada para todo lo clinico: ningun campo descriptivo es
	 * obligatorio —una intervencion puede no tener tecnica declarable ni cronometrarse— y exigirlos
	 * obligaria a inventar datos clinicos para poder guardar.
	 *
	 * <p>Lo unico que se rechaza es lo que no significa nada:
	 *
	 * <ul>
	 *   <li><b>Lateralidad sin zona.</b> "Derecha" de que. Es la misma validacion que 06.02 aplica
	 *       al dolor.</li>
	 *   <li><b>Duracion fuera de escala.</b> Cero no es "menos duracion", es un dato a medio
	 *       cargar; y una intervencion de mas de un dia es un tipeo.</li>
	 *   <li><b>Parametros duplicados por clave.</b> Dos "intensidad" en la misma intervencion no
	 *       son dos datos: son uno mal cargado dos veces, y cual gana seria indefinido. El unique
	 *       de V55 es el respaldo; aca se responde 400 nombrando la clave en vez de dejar que el
	 *       motor conteste con un error de constraint.</li>
	 *   <li><b>Cada parametro, por su cuenta.</b> Ver {@link ParametroAplicado}.</li>
	 * </ul>
	 *
	 * @throws IllegalArgumentException   si la lateralidad o la duracion no significan nada (400)
	 * @throws ParametroInvalidoException si algun parametro esta mal tipado o repetido (400)
	 */
	public void exigirCoherente() {
		if (lateralidad != null && (zona == null || zona.isBlank())) {
			throw new IllegalArgumentException(
					"La lateralidad no significa nada sin zona tratada: 'derecha' de que");
		}
		if (duracionMinutos != null
				&& (duracionMinutos <= 0
						|| duracionMinutos > TratamientoRealizado.MAX_DURACION_MINUTOS)) {
			throw new IllegalArgumentException(
					"La duracion de una intervencion va entre 1 y "
							+ TratamientoRealizado.MAX_DURACION_MINUTOS + " minutos");
		}

		List<String> claves = parametros.stream()
				.map(ParametroAplicado::clave)
				.filter(clave -> clave != null && !clave.isBlank())
				.map(String::strip)
				.toList();
		if (claves.size() != claves.stream().distinct().count()) {
			throw new ParametroInvalidoException("(repetida)",
					"Hay dos parametros con la misma clave en la misma intervencion");
		}

		parametros.forEach(ParametroAplicado::exigirCoherente);
	}
}
