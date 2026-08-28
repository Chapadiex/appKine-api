package com.akine.person.application;

import com.akine.person.domain.TipoDocumento;

import java.time.LocalDate;

/**
 * Edicion parcial de una Persona (RF-M07-003).
 *
 * <p>Semantica de PATCH: cada campo {@code null} deja el valor como estaba. La consecuencia —que
 * no se puede VACIAR un telefono ya cargado— esta discutida en {@code Persona.updateDatos}.
 *
 * <p><b>El documento SI se puede corregir</b>, a diferencia del codigo de un servicio, que es
 * inmutable. El motivo es que un documento mal tipeado en el mostrador es el error mas frecuente
 * del alta y no hay ninguna referencia externa que dependa de ese valor: la identidad estable de
 * una persona en este sistema es su id, no su documento. Corregirlo puede chocar contra el unique
 * y produce el mismo 409 que el alta.
 *
 * @param expectedVersion version que el cliente leyo. Si no coincide, 409 y no se pisa el cambio
 *                        ajeno: dos recepcionistas editando la misma ficha es el caso normal, no
 *                        el raro
 */
public record PersonaEdicionCommand(
		TipoDocumento tipoDocumento,
		String numeroDocumento,
		String apellido,
		String nombre,
		LocalDate fechaNacimiento,
		String email,
		String telefono,
		String notas,
		long expectedVersion) {
}
