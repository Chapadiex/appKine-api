package com.akine.clinical.domain;

/**
 * Clase de antecedente clinico (RF-M09-002).
 *
 * <p>Los cuatro primeros son los que el requerimiento nombra literalmente —medicos, quirurgicos,
 * alergias y medicacion—. Los tres ultimos sostienen la anamnesis basica sin obligar a meterla en
 * texto libre dentro de {@code OTRO}, que es como un catalogo cerrado se vuelve inutil a los dos
 * meses.
 *
 * <p>Es una lista <b>del producto</b>, no del tenant: mismo criterio que {@code espacio.tipo}
 * (V19), {@code servicio.naturaleza} (V24) y {@code persona.tipo_documento} (V27). Un centro no
 * puede agregar clases de antecedente, porque la interpretacion clinica de cada una tiene que ser
 * la misma en todo el sistema para que un dia se pueda preguntar "alergias vigentes" con un
 * {@code WHERE} y no con un parseo.
 */
public enum TipoAntecedente {

	MEDICO,
	QUIRURGICO,
	ALERGIA,
	MEDICACION,
	FAMILIAR,
	HABITO,
	OTRO;
}
