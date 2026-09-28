package com.akine.person.spi;

import java.util.Collection;
import java.util.Map;

/**
 * Como un modulo productor de notificaciones obtiene el correo de una persona (AKINE-08.02).
 *
 * <h2>Por que es un puerto aparte y no un campo mas de {@link PacienteSnapshot}</h2>
 *
 * <p>Porque aquel snapshot lo consumen los modulos <b>clinicos</b>, y su javadoc dice por que no
 * lleva contacto: "correo y telefono no estan porque ningun modulo clinico los necesita para
 * decidir nada, y RN-M09-003 le prohibe ademas copiarlos en su propio esquema". Agregarlos ahi
 * para que M28 pueda mandar un mail se los regalaria de paso a {@code clinical} y a
 * {@code encounter}, que no deben tenerlos.
 *
 * <p>Un puerto propio mantiene esa separacion visible: quien necesita el correo lo pide
 * explicitamente, y quien no lo pide sigue sin poder obtenerlo por accidente.
 *
 * <p><b>Este puerto no autoriza nada</b>, igual que {@link PacienteDirectory}: devuelve datos del
 * tenant que se le pide y confia en que el llamador ya evaluo el permiso. El aislamiento de tenant
 * si se aplica — la firma exige {@code organizationId} y no resuelve por id pelado.
 */
public interface ContactoDirectory {

	/**
	 * Los contactos de esas personas, indexados por id. Los que no existan en el tenant no
	 * aparecen.
	 *
	 * <p><b>Solo en batch, sin variante de a uno.</b> El caso de uso es "avisarle a los inscriptos
	 * de una clase que se movio", y resolverlos de a uno convertiria una reprogramacion en tantas
	 * consultas como participantes tenga — que crece justo con lo llena que este la clase. Quien
	 * necesita uno pide una lista de uno.
	 */
	Map<Long, ContactoDePersona> findAll(long organizationId, Collection<Long> personaIds);
}
