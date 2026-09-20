package com.akine.clinical.spi;

import java.time.LocalDate;

/**
 * Lo que el modulo duenio de la participacion afirma sobre ella al pedir una derivacion.
 *
 * <h2>Esto es una AFIRMACION, no una consulta, y por eso el reparto de validaciones importa</h2>
 *
 * <p>{@code clinical} no puede leer una clase ni una asistencia: la arista
 * {@code clinical -> activity} cerraria el ciclo con {@code activity -> clinical.spi} y ArchUnit la
 * rechaza —medido con sonda y control negativo, challenge pregunta 2—. Asi que el llamador valida
 * lo grupal y lo afirma aca; {@code clinical} valida lo clinico y no revalida lo ajeno.
 *
 * <p>Lo que el llamador ya comprobo cuando construye esto: que la clase y la participacion son de
 * su tenant, que la asistencia existe y que la persona <b>estuvo</b> —no se deriva a quien no
 * vino—, y que la oferta genera registro clinico. Lo que {@code clinical} comprueba despues: el
 * perfil de paciente, la historia, el Caso, el Plan y la autorizacion.
 *
 * @param ofertaId             la oferta clinica que justifica la derivacion. Se congela en la fila
 * @param requiereCasoClinico  copia de {@code oferta.requiere_caso_clinico} (RF-M10-007). No decide
 *                             si se puede derivar —derivar ES elegir el Caso— sino si 08.05 va a
 *                             exigir Caso al atender. Viaja congelada para que 08.05 no vuelva a la
 *                             oferta, que para entonces puede haber cambiado
 * @param fechaLocal           dia LOCAL de la sede contra el que se evalua la autorizacion. Se
 *                             calcula afuera, con la zona IANA del consultorio: el instante UTC y
 *                             el dia en que una autorizacion vence no estan en la misma escala, y
 *                             resolverlo en UTC corre el dia para media Argentina despues de las 21
 */
public record ParticipacionDerivable(
		OrigenDeParticipacion origen,
		long participacionId,
		long personaId,
		long consultorioId,
		long ofertaId,
		boolean requiereCasoClinico,
		LocalDate fechaLocal) {
}
