package com.akine.clinical.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lo que la agenda necesita saber de casos y planes para reservar un turno (TURNO.md §3 y §4).
 *
 * <p><b>No lleva contenido clinico</b>: ni diagnostico, ni objetivos, ni indicaciones. Quien reserva
 * un turno en el mostrador tiene {@code turno:manage} y no {@code hc:read}, asi que nada de esto se
 * audita como lectura clinica y por eso nada de esto puede decir de que se trata la atencion. Un
 * caso se nombra por su numero y por la oferta, que es lo que ya figura en el comprobante del turno.
 *
 * <p>La dependencia va de {@code scheduling} a {@code clinical}: es la unica direccion posible, y es
 * la que V49 y {@code PlanTratamientoService} anticipan. {@code clinical} sigue sin saber que existen
 * los turnos.
 */
public interface ContextoClinicoDeAgenda {

	/** Los casos activos de la persona, cada uno con sus planes activos. */
	List<CasoAgendable> casosActivosDe(long organizationId, long personaId);

	Optional<CasoAgendable> caso(long organizationId, long casoId);

	Optional<PlanAgendable> plan(long organizationId, long planId);

	/** El numero de cada caso, para rotular una agenda sin preguntar uno por uno. */
	Map<Long, Integer> numerosDeCaso(long organizationId, Collection<Long> casoIds);

	/**
	 * @param responsableMembershipId profesional RESPONSABLE vigente del caso, o {@code null}. Es el
	 *                                profesional por defecto al programar (TURNO.md §15)
	 */
	record CasoAgendable(
			long casoId,
			long personaId,
			int numeroCaso,
			Long ofertaId,
			boolean activo,
			Long responsableMembershipId,
			List<PlanAgendable> planes) {

		public CasoAgendable {
			planes = planes == null ? List.of() : List.copyOf(planes);
		}
	}

	/** @param estado BORRADOR, ACTIVO, SUSPENDIDO o FINALIZADO. Solo un plan ACTIVO se programa. */
	record PlanAgendable(
			long planId,
			long casoId,
			long personaId,
			int numeroPlan,
			String estado,
			Integer frecuenciaSemanal,
			Integer duracionSemanas,
			List<ItemAgendable> items) {

		public PlanAgendable {
			items = items == null ? List.of() : List.copyOf(items);
		}

		public boolean activo() {
			return "ACTIVO".equals(estado);
		}

		public Optional<ItemAgendable> item(long ofertaId) {
			return items.stream().filter(item -> item.ofertaId() == ofertaId).findFirst();
		}
	}

	/**
	 * @param consultorioId sede de la oferta: una oferta pertenece a una sede y un plan puede tener
	 *                      items de varias
	 * @param realizadas    sesiones cerradas con el paciente presente, en ese caso y esa oferta
	 */
	record ItemAgendable(
			long ofertaId, long consultorioId, String ofertaNombre, int planificadas, int realizadas) {
	}
}
