package com.akine.scheduling.spi;

import com.akine.platform.spi.problem.ConflictoDeAgenda;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * <b>La logica central de ocupacion por persona</b> (disponibilidad central, contrato 0.158.0).
 *
 * <p>Un profesional no puede tener dos obligaciones a la vez aunque sean de sedes o tipos distintos.
 * Hasta 0.157.0 cada camino lo preguntaba a su manera: el turno cruzaba turnos y clases, la clase cruzaba
 * turnos y clases pero no los bloqueos de los adicionales, la apertura de una excepcion no cruzaba nada.
 * Esta interfaz es la unica pregunta, y la usan los turnos ({@code RevalidadorDeSlot}: reservar,
 * sobreturno, reprogramar, trasladar, series, lista de espera), las clases y sus instructores
 * ({@code activity}) y la disponibilidad ({@code resource}, por {@code resource.spi.ActividadDePersonaProbe}).
 * Un tipo de actividad nuevo se suma aca y lo ven todos.
 *
 * <p>No es una agenda paralela: no tiene tablas. Lee los turnos ({@code scheduling}), las clases por
 * {@link OcupacionExternaProbe} ({@code activity}) y los cierres por {@code resource.spi.DisponibilidadDirectory}.
 *
 * <p><b>Bajo el lock de la persona</b> ({@code agenda_profesional}) es una garantia; fuera, una foto. No
 * autoriza nada.
 */
public interface OcupacionDePersona {

	/**
	 * Lo que se pregunta.
	 *
	 * @param consultorioId    sede de la operacion: decide {@code mismaSede} y donde se buscan los cierres
	 * @param membershipId     la membership de la persona en esa sede (sus otras memberships se resuelven)
	 * @param turnoExcluidoId  el turno que se mueve (choca consigo mismo si no)
	 * @param claseExcluidaId  la clase que se mueve o a la que se suma un instructor
	 * @param ofertaDelGrupo   con {@code inicio}: los turnos de esa oferta, ese profesional y ese inicio son
	 *                         el mismo grupo (oferta grupal) y no cuentan como choque
	 */
	record Consulta(
			long organizationId,
			long consultorioId,
			long membershipId,
			Instant inicio,
			Instant fin,
			Long turnoExcluidoId,
			Long claseExcluidaId,
			Long ofertaDelGrupo) {

		public static Consulta de(long organizationId, long consultorioId, long membershipId, Instant inicio,
				Instant fin) {
			return new Consulta(organizationId, consultorioId, membershipId, inicio, fin, null, null, null);
		}

		public Consulta excluyendoClase(Long claseId) {
			return new Consulta(organizationId, consultorioId, membershipId, inicio, fin, turnoExcluidoId, claseId,
					ofertaDelGrupo);
		}
	}

	/**
	 * Turnos y clases (principal o adicional) de la MISMA PERSONA, en cualquier sede de la organizacion,
	 * que se cruzan con {@code [inicio, fin)}, ordenados por inicio.
	 */
	List<ConflictoDeAgenda> actividadReservada(Consulta consulta);

	/**
	 * Cierres de la sede de la consulta que alcanzan a esa membership en {@code [inicio, fin)}: los
	 * suyos ({@code BLOQUEO} / {@code AUSENCIA}) y los de la sede entera ({@code CIERRE_DE_SEDE}).
	 */
	List<ConflictoDeAgenda> cierres(Consulta consulta);

	/** El primer conflicto: lo reservado primero, despues los cierres. */
	default Optional<ConflictoDeAgenda> primerConflicto(Consulta consulta) {
		List<ConflictoDeAgenda> reservada = actividadReservada(consulta);
		if (!reservada.isEmpty()) {
			return Optional.of(reservada.getFirst());
		}
		return cierres(consulta).stream().findFirst();
	}
}
