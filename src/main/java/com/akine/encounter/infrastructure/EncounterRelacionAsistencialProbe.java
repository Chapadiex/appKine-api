package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.scheduling.spi.TurnoDirectory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Contesta {@link RelacionAsistencialProbe} desde el modulo que es dueno de la Sesion.
 *
 * <p>Hay relacion asistencial si el actor atendio o inicio una sesion de esa persona en la sede, o
 * si tiene un turno vivo con ella. La sesion se consulta primero: si hay una, no se miran turnos.
 * Reemplaza a la implementacion sin agenda: queda un solo bean.
 */
@Component
public class EncounterRelacionAsistencialProbe implements RelacionAsistencialProbe {

	private final SesionRepositoryPort sesiones;
	private final TurnoDirectory turnos;
	private final HistoriaClinicaDirectory historias;
	private final AccountContextDirectory cuentas;

	// @Lazy: HistoriaClinicaDirectory -> HistoriaClinicaService -> AdjuntoClinicoService vuelven a
	// pedir este probe; el proxy perezoso corta el ciclo de beans al arrancar el contexto.
	public EncounterRelacionAsistencialProbe(
			SesionRepositoryPort sesiones,
			TurnoDirectory turnos,
			@Lazy HistoriaClinicaDirectory historias,
			AccountContextDirectory cuentas) {
		this.sesiones = sesiones;
		this.turnos = turnos;
		this.historias = historias;
		this.cuentas = cuentas;
	}

	@Override
	public boolean tieneRelacionAsistencial(
			long organizationId, long consultorioId, long actorAccountId, long personaId) {
		var membership = cuentas.membership(actorAccountId, organizationId);
		if (membership.isEmpty()) {
			return false;
		}
		var historia = historias.find(organizationId, personaId);
		if (historia.isEmpty()) {
			return false;
		}
		long membershipId = membership.get().membershipId();
		return sesiones.existeSesionDelActor(
						organizationId, consultorioId, historia.get().id(), membershipId, actorAccountId)
				|| turnos.existeTurnoVivoDeProfesionalConPersona(
						organizationId, consultorioId, membershipId, personaId);
	}
}
