package com.akine.clinical.domain.exception;

import java.util.List;

/**
 * El alta coincide con un caso ACTIVO de la misma historia y la misma oferta (409).
 *
 * <h2>Es una advertencia, no un invariante, y la diferencia es todo</h2>
 *
 * <p>RN-M10-002 admite <b>varios casos activos</b> del mismo paciente: una rodilla y un hombro son
 * dos casos legitimos el mismo dia. Por eso no hay ningun unique que lo impida —seria un bug
 * disfrazado de proteccion— y por eso el segundo caso de la misma oferta <b>puede ser correcto</b>:
 * el rechazo de plano estaria mal.
 *
 * <p>Lo que se hace es detener el alta, devolver <b>los candidatos</b> y dejar que el profesional
 * decida: abre el caso que ya existe, o reenvia el alta declarando que es otro. Esa segunda vuelta
 * queda registrada como decision suya. Es el mismo mecanismo del alta de Persona de 03.01
 * (RN-M07-001), que los usuarios de este sistema ya conocen.
 *
 * <h2>La ventana de carrera existe y no se pretende cerrarla</h2>
 *
 * <p>Dos administrativos abriendo el caso a la vez desde dos sedes pueden pasar los dos el
 * chequeo. El resultado correcto no es rechazar: es que los dos entren y alguien los unifique
 * despues (challenge seccion 8.2).
 *
 * <h2>Y no se confunde con el 409 de concurrencia</h2>
 *
 * <p>Son {@code problemType} distintos y tienen que serlo: este se resuelve <b>confirmando</b>, el
 * de {@code concurrent-modification} se resuelve <b>releyendo</b>. Un solo tipo obligaria al
 * cliente a adivinar cual de las dos acciones corresponde leyendo prosa en castellano.
 */
public class CasoClinicoPosibleDuplicadoException extends RuntimeException {

	private final List<Long> candidatos;

	public CasoClinicoPosibleDuplicadoException(List<Long> candidatos) {
		super("Ya hay un caso activo de esa oferta en la historia clinica");
		this.candidatos = List.copyOf(candidatos);
	}

	/** Ids de los casos activos que coinciden. Nunca vacia: sin coincidencias no se lanza. */
	public List<Long> getCandidatos() {
		return candidatos;
	}
}
