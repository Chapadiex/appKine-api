package com.akine.encounter.domain;

/**
 * Lo que una enmienda puede cambiar de una sesion cerrada (RF-M14-010).
 *
 * <h2>La lista de campos ES la regla, y por eso no hay un error de "campo bloqueado"</h2>
 *
 * <p>Este record tiene exactamente los once campos enmendables y ninguno mas. Lo que falta no se
 * rechaza con una validacion: <b>no se puede expresar</b>. Un servicio disciplinado que valide
 * "este campo no se toca" funciona hasta que alguien agrega un camino de escritura; un tipo que no
 * tiene el campo funciona siempre.
 *
 * <h2>Las tres ausencias que hay que saber leer</h2>
 *
 * <ul>
 *   <li><b>{@code asistencia}.</b> Es la unica bisagra entre el relato clinico y el dinero: de
 *       ella dependen {@code ObligacionDevengador} —que devenga la deuda— y
 *       {@code ConsumoDeAutorizacionEnCierre} —que gasta una unidad autorizada—. Cambiarla es un
 *       acto economico que exige compensacion explicita en M18 y M17, no una enmienda clinica.
 *       Congelarla es lo que convierte "no hay cambios economicos implicitos" en una propiedad del
 *       modelo en vez de en una promesa del servicio. Ver {@code AKINE-06.06-challenge.md}
 *       seccion 8.</li>
 *   <li><b>{@code modo}.</b> No es contenido clinico: es una decision de la PANTALLA sobre cuanto
 *       mostrar (06.02). Versionarlo seria versionar una preferencia de interfaz.</li>
 *   <li><b>{@code numeroSesion} y {@code numeroEnCaso}.</b> Renumerar sesiones cerradas es
 *       reescribir historia clinica. Es lo que 04.03 rechazo explicitamente y lo que 06.05 dejo
 *       fijado: el numero esta impreso en informes.</li>
 * </ul>
 *
 * <h2>Reemplazo completo, no parche</h2>
 *
 * <p>Un campo nulo significa "queda vacio", no "dejalo como estaba". Un parche obligaria a
 * distinguir "no lo mande" de "lo borre" sobre campos que son legitimamente nulos —toda la
 * evaluacion base lo es— y esa distincion no se puede expresar en JSON sin inventar un centinela.
 * La pantalla manda el formulario completo, que es lo que ya hace al evaluar.
 */
public record ContenidoDeSesion(
		String motivoClinico,
		Integer dolorEva,
		String dolorZona,
		Lateralidad dolorLateralidad,
		Evolucion evolucion,
		String objetivoSesion,
		String limitacionFuncional,
		String notaDeCierre,
		String respuestaTratamiento,
		Tolerancia tolerancia,
		String indicaciones,
		ProximaConducta proximaConducta) {

	/**
	 * La evaluacion base que esta enmienda propone, con el modo que la sesion ya tenia.
	 *
	 * <p>El modo lo pone la sesion y no el pedido: ver la cabecera. Se reusa
	 * {@link EvaluacionBase} entero —y no una validacion propia— para que una enmienda no pueda
	 * guardar un EVA de 12 que la evaluacion original habria rechazado.
	 */
	public EvaluacionBase evaluacionCon(ModoSesion modo) {
		return new EvaluacionBase(
				modo, motivoClinico, dolorEva, dolorZona, dolorLateralidad,
				evolucion, objetivoSesion, limitacionFuncional);
	}

	/**
	 * El cierre que esta enmienda propone, con la asistencia que la sesion ya tenia.
	 *
	 * <p>La asistencia sale de la sesion y no del pedido, que es toda la decision de la etapa. El
	 * efecto util de reusar {@link CierreDeSesion} es que {@code exigirMinimos} sigue valiendo:
	 * una enmienda <b>no puede vaciar la nota de cierre de una sesion con el paciente presente</b>,
	 * porque eso dejaria una prestacion que ocurrio sin nada que diga que se hizo.
	 */
	public CierreDeSesion cierreCon(Asistencia asistencia) {
		return new CierreDeSesion(
				asistencia, notaDeCierre, respuestaTratamiento, tolerancia, indicaciones,
				proximaConducta);
	}
}
