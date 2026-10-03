package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.scheduling.spi.TurnoDirectory;
import java.time.Instant;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Contesta {@link RelacionAsistencialProbe} desde el modulo que es dueno de la Sesion.
 *
 * <p>El actor es el profesional y se lo identifica por sus vinculos habilitados: los de la sede
 * consultada y los de alcance organizacion, que cubren todas. Un vinculo suspendido, revocado,
 * vencido o dado de baja no cuenta.
 *
 * <p>Hay relacion asistencial si, con alguno de esos vinculos, el actor atendio o inicio una
 * sesion de esa persona en la sede, o tiene un turno vivo con ella. Por cada vinculo la sesion se
 * consulta primero: si hay una, no se miran turnos. Reemplaza a la implementacion sin agenda:
 * queda un solo bean.
 */
@Component
public class EncounterRelacionAsistencialProbe implements RelacionAsistencialProbe {

	private final SesionRepositoryPort sesiones;
	private final TurnoDirectory turnos;
	private final HistoriaClinicaDirectory historias;
	private final ConsultorioMembershipDirectory vinculos;

	// @Lazy: HistoriaClinicaDirectory -> HistoriaClinicaService -> AdjuntoClinicoService vuelven a
	// pedir este probe; el proxy perezoso corta el ciclo de beans al arrancar el contexto.
	public EncounterRelacionAsistencialProbe(
			SesionRepositoryPort sesiones,
			TurnoDirectory turnos,
			@Lazy HistoriaClinicaDirectory historias,
			ConsultorioMembershipDirectory vinculos) {
		this.sesiones = sesiones;
		this.turnos = turnos;
		this.historias = historias;
		this.vinculos = vinculos;
	}

	@Override
	public boolean tieneRelacionAsistencial(
			long organizationId, long consultorioId, long actorAccountId, long personaId) {
		Instant ahora = Instant.now();
		var habilitados = vinculos.findByAccount(organizationId, actorAccountId).stream()
				.filter(v -> v.validAt(ahora) && v.cubreConsultorio(consultorioId))
				.toList();
		if (habilitados.isEmpty()) {
			return false;
		}
		var historia = historias.find(organizationId, personaId);
		if (historia.isEmpty()) {
			return false;
		}
		for (var v : habilitados) {
			if (sesiones.existeSesionDelActor(
							organizationId,
							consultorioId,
							historia.get().id(),
							v.membershipId(),
							actorAccountId)
					|| turnos.existeTurnoVivoDeProfesionalConPersona(
							organizationId, consultorioId, v.membershipId(), personaId)) {
				return true;
			}
		}
		return false;
	}
}
