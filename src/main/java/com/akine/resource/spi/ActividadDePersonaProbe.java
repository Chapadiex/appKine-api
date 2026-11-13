package com.akine.resource.spi;

import com.akine.platform.spi.problem.ConflictoDeAgenda;

import java.time.Instant;
import java.util.List;

/**
 * Lo que una persona tiene reservado —turnos y clases—, para que {@code resource} valide un cambio de
 * disponibilidad contra la logica central de ocupacion (disponibilidad central, contrato 0.158.0).
 *
 * <h2>Por que vive aca</h2>
 *
 * <p>{@code scheduling} ya depende de {@code resource.spi} y no al reves. Mismo patron que
 * {@link DisponibilidadImpactProbe}: {@code resource} declara la pregunta y
 * {@code scheduling.infrastructure} la contesta delegando en {@code scheduling.spi.OcupacionDePersona},
 * que es la misma lectura que usan los turnos y las clases. No hay una segunda copia de la regla.
 *
 * <p>Sin datos del paciente: tipo, id, sede y franja.
 */
public interface ActividadDePersonaProbe {

	/**
	 * Turnos y clases (principal o adicional) de la MISMA PERSONA que {@code membershipId}, en cualquier
	 * sede, que se cruzan con {@code [inicio, fin)}. {@code consultorioId} decide {@code mismaSede}.
	 * Bajo el lock de la persona es una garantia.
	 */
	List<ConflictoDeAgenda> actividadReservada(
			long organizationId, long consultorioId, long membershipId, Instant inicio, Instant fin);

	/**
	 * Las clases vivas de esa sede que se cruzan con {@code [inicio, fin)}: las que tienen a
	 * {@code membershipId} como principal o adicional, o todas si es {@code null} (cierre de sede).
	 */
	List<ClaseAfectada> clasesDeLaSede(
			long organizationId, long consultorioId, Long membershipId, Instant inicio, Instant fin);

	/**
	 * Una clase que un cierre deja sin instructor disponible.
	 *
	 * @param instructorMembershipId el instructor al que alcanza el cierre; {@code null} en un cierre de
	 *                               sede, que alcanza a la clase entera
	 */
	record ClaseAfectada(long claseId, Long instructorMembershipId, Instant inicio, Instant fin) {
	}
}
