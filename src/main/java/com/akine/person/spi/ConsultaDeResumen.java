package com.akine.person.spi;

/**
 * Lo que {@code person} le pasa a cada contribuyente del Paciente 360.
 *
 * <p><b>No lleva al actor ni su permiso.</b> {@code person} ya evaluo el permiso que el
 * contribuyente declaro y no lo llama si falta: pasarle el actor invitaria a que cada modulo
 * volviera a decidir, y la autorizacion quedaria duplicada en cuatro lugares que se
 * desincronizan. Es el mismo criterio con el que {@code PacienteDirectory} declara que no
 * autoriza nada.
 *
 * <p>El aislamiento de tenant SI viaja, y es obligatorio: ningun contribuyente resuelve por id
 * pelado.
 *
 * @param consultorioId sede del contexto de trabajo, o {@code null}. La Persona es de la
 *                      organizacion, pero un turno y una obligacion pertenecen a una sede: cada
 *                      contribuyente decide si consolida la organizacion entera o solo la sede en
 *                      la que el operador esta parado, y lo dice en su javadoc
 * @param limiteHitos   tope de hechos datados que se esperan. Cada contribuyente lo respeta por
 *                      su cuenta: el 360 no vuelve a recortar
 */
public record ConsultaDeResumen(
		long organizationId,
		Long consultorioId,
		long personaId,
		int limiteHitos) {
}
