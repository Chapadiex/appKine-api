package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.scheduling.domain.exception.ContextoClinicoInvalidoException;
import org.springframework.stereotype.Component;

/**
 * Valida que el caso y el plan de un turno sean de esa persona y sirvan para esa prestacion
 * (TURNO.md §28). Lo usan la reserva, la serie y la lista de espera: una sola copia de la regla.
 *
 * <p>Los dos son opcionales. Sin ellos no se valida nada: una primera consulta no tiene ni caso ni
 * plan, y la reserva no le exige a nadie informacion clinica que todavia no existe (TURNO.md §11).
 */
@Component
class ContextoClinicoDeTurno {

	/** @param plan el plan ya leido, o {@code null}. Es lo que despues controla las sesiones */
	record Resuelto(Long casoId, Long planId, PlanAgendable plan) {

		static final Resuelto NINGUNO = new Resuelto(null, null, null);
	}

	private final ContextoClinicoDeAgenda contexto;

	ContextoClinicoDeTurno(ContextoClinicoDeAgenda contexto) {
		this.contexto = contexto;
	}

	/**
	 * Con plan y sin caso, el caso sale del plan: quien programa desde un plan no tiene por que
	 * repetirlo.
	 *
	 * @throws ContextoClinicoInvalidoException el caso o el plan no sirven para este turno (409)
	 */
	Resuelto resolver(long organizationId, long personaId, long ofertaId, Long casoId, Long planId) {
		if (casoId == null && planId == null) {
			return Resuelto.NINGUNO;
		}
		PlanAgendable plan = null;
		Long casoEfectivo = casoId;
		if (planId != null) {
			plan = contexto.plan(organizationId, planId)
					.orElseThrow(() -> new ContextoClinicoInvalidoException("El plan de tratamiento no existe."));
			if (plan.personaId() != personaId) {
				throw new ContextoClinicoInvalidoException("El plan de tratamiento es de otro paciente.");
			}
			if (!plan.activo()) {
				throw new ContextoClinicoInvalidoException(
						"El plan de tratamiento no esta activo: esta " + plan.estado().toLowerCase() + ".");
			}
			if (plan.item(ofertaId).isEmpty()) {
				throw new ContextoClinicoInvalidoException("La prestacion no esta incluida en el plan.");
			}
			if (casoId != null && casoId != plan.casoId()) {
				throw new ContextoClinicoInvalidoException("El plan de tratamiento no es de ese caso.");
			}
			casoEfectivo = plan.casoId();
		}
		CasoAgendable caso = contexto.caso(organizationId, casoEfectivo)
				.orElseThrow(() -> new ContextoClinicoInvalidoException("El caso clinico no existe."));
		if (caso.personaId() != personaId) {
			throw new ContextoClinicoInvalidoException("El caso clinico es de otro paciente.");
		}
		if (!caso.activo()) {
			throw new ContextoClinicoInvalidoException("El caso clinico esta cerrado.");
		}
		return new Resuelto(casoEfectivo, planId, plan);
	}
}
