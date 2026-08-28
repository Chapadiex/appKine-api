package com.akine.person.domain.exception;

import java.util.List;

/**
 * El alta coincide con personas que ya existen y nadie confirmo que sea otra distinta (409).
 *
 * <h2>Esto es RN-M07-001 implementado en el backend, no en la pantalla</h2>
 *
 * <p>RN-M07-001 dice "debe existir busqueda previa a la creacion". El reflejo es implementarlo en
 * la pantalla: un buscador arriba del formulario. Eso es necesario y no es suficiente — el
 * backend es la autoridad (regla maestra 12) y un alta por API, por import o por una pantalla
 * futura que se olvide del buscador entraria sin ninguna comprobacion. Con una pantalla como
 * unica defensa, la regla dura exactamente lo que dura el primer cliente nuevo.
 *
 * <p>Por eso el alta busca coincidencias y, si encuentra, <b>rechaza con 409 y devuelve los
 * candidatos</b>. El operador ve la lista y decide: abre una ficha existente, o reenvia el alta
 * declarando explicitamente que es otra persona. Esa segunda vuelta es el equivalente por API de
 * haber mirado la busqueda previa, y queda registrada como decision suya.
 *
 * <h2>Que cuenta como coincidencia, y por que tan poco</h2>
 *
 * <p>Solo coincidencias EXACTAS sobre las claves normalizadas: mismo apellido y mismo nombre, o
 * mismo telefono. Nada de fonetica ni de distancia de edicion — ver {@code ClaveDeBusqueda}. Un
 * detector generoso produce una advertencia en casi toda alta, el operador aprende a confirmar
 * sin leer, y la regla se vuelve un click de mas que no protege nada. Un detector estricto avisa
 * poco y cuando avisa, acierta.
 *
 * <p>El documento repetido <b>no</b> llega por aca: ese es el invariante duro y es
 * {@link PersonaDocumentoTakenException}. No se confirma ni se saltea.
 */
public class PersonaPosibleDuplicadoException extends RuntimeException {

	private final List<Long> candidatos;

	public PersonaPosibleDuplicadoException(List<Long> candidatos) {
		super("El alta coincide con personas ya registradas en la organizacion");
		this.candidatos = List.copyOf(candidatos);
	}

	/** Ids de las personas que coinciden. Nunca vacia: sin coincidencias no se lanza. */
	public List<Long> getCandidatos() {
		return candidatos;
	}
}
