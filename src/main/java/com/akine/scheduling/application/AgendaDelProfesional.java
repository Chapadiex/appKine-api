package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.PermissionDecision;
import com.akine.resource.spi.BloqueoDeAgendaDeProfesional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * La agenda de UNA PERSONA dentro de la organizacion: de quien es un turno y como se serializan
 * las escrituras sobre ella en todas sus sedes (agenda de profesionales, checklist §1.10, §9.2).
 *
 * <h2>Alcance propio</h2>
 *
 * <p>El {@code PROFESIONAL} tiene {@code turno:manage} con alcance {@code ACTIVIDAD_PROPIA}: el
 * evaluador le concede la sede y le avisa al llamador que recorte. El recorte es este: cada
 * profesional nombrado por la operacion —el del turno, el de destino de una reprogramacion, el de
 * una reserva— tiene que ser una membership <b>de la misma cuenta</b> que el actor. Se compara por
 * cuenta y no por id de membership porque con memberships por sede la misma persona tiene un id en
 * cada una. Un turno sin profesional no es de nadie y tampoco es propio. Ajeno es <b>403</b>
 * ({@code forbidden}): el turno es de su sede, ya lo ve en la agenda, y negarlo no revela nada.
 *
 * <h2>Lock por persona</h2>
 *
 * <p>Delega en {@code resource.spi.BloqueoDeAgendaDeProfesional} (V129). El orden fijo es: lock de la
 * sede, despues lock de la persona. Ver {@link RevalidadorDeSlot}.
 */
@Component
public class AgendaDelProfesional {

	private final ConsultorioMembershipDirectory memberships;
	private final BloqueoDeAgendaDeProfesional bloqueo;

	public AgendaDelProfesional(
			ConsultorioMembershipDirectory memberships, BloqueoDeAgendaDeProfesional bloqueo) {
		this.memberships = memberships;
		this.bloqueo = bloqueo;
	}

	/**
	 * Si la decision vino recortada a la actividad propia, exige que todos los profesionales
	 * nombrados sean el actor. Con cualquier otro alcance no hace nada.
	 *
	 * @throws AccessDeniedException si alguno es de otra persona o no hay profesional (403)
	 */
	public void exigirPropio(
			PermissionDecision decision, OperatingActor actor, long organizationId, Long... profesionales) {

		if (decision == null || !decision.limitadaAActividadPropia()) {
			return;
		}
		for (Long profesional : profesionales) {
			if (!esDelActor(actor, organizationId, profesional)) {
				throw new AccessDeniedException(
						"Con este perfil solo se operan los turnos de la propia agenda");
			}
		}
	}

	/** Si la membership es de la misma cuenta que el actor. {@code null} nunca lo es. */
	public boolean esDelActor(OperatingActor actor, long organizationId, Long profesionalMembershipId) {
		return profesionalMembershipId != null && memberships.find(organizationId, profesionalMembershipId)
				.map(m -> m.accountId() == actor.accountId())
				.orElse(false);
	}

	/** Crea las filas-lock de esas personas. SIN transaccion abierta (FilaLockPrevia). */
	public void asegurar(long organizationId, Collection<Long> profesionales) {
		bloqueo.asegurar(organizationId, profesionales);
	}

	/** Toma el lock de la persona y devuelve todas sus memberships en la organizacion. */
	Set<Long> bloquear(long organizationId, long profesional) {
		return bloqueo.bloquear(organizationId, profesional);
	}

	/** Toma los locks de varias personas en orden de cuenta. */
	void bloquearEnOrden(long organizationId, Collection<Long> profesionales) {
		bloqueo.bloquearEnOrden(organizationId, profesionales);
	}

	/** Las memberships de la misma persona, sin lock. */
	Set<Long> deLaMismaPersona(long organizationId, long profesional) {
		return bloqueo.deLaMismaPersona(organizationId, profesional);
	}
}
