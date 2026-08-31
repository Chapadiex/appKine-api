package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.EvaluacionIncoherenteException;

/**
 * La evaluacion base de una sesion (RF-M14-003).
 *
 * <h2>Todo es opcional, y es una regla de negocio</h2>
 *
 * <p>"Seguimiento no exige examen completo". Una sesion de seguimiento carga dolor y evolucion y
 * nada mas, y esa es la mayoria de las sesiones de un tratamiento. Exigir cualquiera de estos
 * campos obligaria al profesional a <b>inventar datos clinicos</b> para poder guardar, que es
 * exactamente lo que un registro clinico no puede pedir.
 *
 * <p>Por eso {@link #exigirCoherente()} valida lo que seria FALSO, no lo que falta. Son dos cosas y
 * ninguna es un formalismo:
 *
 * <ul>
 *   <li><b>El dolor fuera de 0..10.</b> No es un error de tipeo que alguien corrige mirando: es un
 *       dato que despues se promedia y se grafica como evolucion del paciente.</li>
 *   <li><b>La lateralidad sin zona.</b> "Derecha" de que. No significa nada, y guardado ocupa el
 *       lugar de un dato real.</li>
 * </ul>
 *
 * <p>La implicacion va en un solo sentido: <b>zona sin lateralidad si es legitimo</b>, porque una
 * zona central —lumbar, cervical— no tiene lado. Para decir "no corresponde" existe
 * {@link Lateralidad#NO_APLICA}, que es distinto de dejarlo vacio: sin ese valor la pantalla no
 * puede saber si volver a preguntar.
 *
 * <h2>El modo no condiciona nada</h2>
 *
 * <p>{@link ModoSesion} es una decision de la PANTALLA sobre cuanto mostrar, no una validacion del
 * servidor. Si el modo exigiera campos, un profesional que empieza en rapida y necesita anotar una
 * cosa mas tendria que cambiar de modo y recargar el formulario en medio de una atencion.
 *
 * @param motivoClinico lo que trae al paciente, en palabras del profesional. <b>No es un
 *                      diagnostico</b>: el diagnostico es un acto medico que este sistema no
 *                      registra, y por eso es texto libre y no una referencia al nomenclador
 */
public record EvaluacionBase(
		ModoSesion modo,
		String motivoClinico,
		Integer dolorEva,
		String dolorZona,
		Lateralidad dolorLateralidad,
		Evolucion evolucion,
		String objetivoSesion,
		String limitacionFuncional) {

	public static final int DOLOR_MINIMO = 0;
	public static final int DOLOR_MAXIMO = 10;

	public EvaluacionBase {
		motivoClinico = vacioEsNulo(motivoClinico);
		dolorZona = vacioEsNulo(dolorZona);
		objetivoSesion = vacioEsNulo(objetivoSesion);
		limitacionFuncional = vacioEsNulo(limitacionFuncional);
	}

	/** Ver la cabecera: valida lo que seria falso, no lo que falta. */
	public void exigirCoherente() {
		if (dolorEva != null && (dolorEva < DOLOR_MINIMO || dolorEva > DOLOR_MAXIMO)) {
			throw new EvaluacionIncoherenteException(
					"El dolor se mide en una escala de " + DOLOR_MINIMO + " a " + DOLOR_MAXIMO
							+ " y llego " + dolorEva);
		}
		if (dolorLateralidad != null && dolorZona == null) {
			throw new EvaluacionIncoherenteException(
					"La lateralidad sin zona no dice nada: falta indicar de que zona");
		}
	}

	/**
	 * Un texto en blanco es lo mismo que no haberlo cargado.
	 *
	 * <p>Sin esto, un campo que el usuario toco y despues borro queda como cadena vacia, y la
	 * pantalla no puede distinguirlo de un dato real. Peor: una consulta de "sesiones con objetivo
	 * cargado" lo contaria.
	 */
	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.trim();
	}
}
