package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Contesta {@link AtencionProbe} desde el modulo que es dueno de la Sesion.
 *
 * <p>La interfaz vive en {@code scheduling.spi} y la implementacion aca porque la dependencia entre
 * los dos modulos ya va en este sentido: {@code encounter} conoce a {@code scheduling}, nunca al
 * reves. Ver el javadoc de {@code AtencionProbe}.
 *
 * <p><b>Traduccion de forma y nada mas.</b> Que hacer con la respuesta lo decide M12: un turno con
 * atencion no se cancela, no se mueve y no se marca ausente.
 */
@Component
public class EncounterAtencionProbe implements AtencionProbe {

	private final SesionRepositoryPort sesiones;

	public EncounterAtencionProbe(SesionRepositoryPort sesiones) {
		this.sesiones = sesiones;
	}

	/**
	 * <p>Usa {@code findVivaPorTurno}, que filtra por {@code deletedAt IS NULL} y <b>no</b> por
	 * estado: una sesion cerrada sigue siendo una atencion ocurrida, y es justamente el caso en el
	 * que cancelar el turno seria mas grave —desde M18 esa sesion ya devengo una obligacion—.
	 */
	@Override
	public boolean tieneAtencion(long organizationId, long consultorioId, long turnoId) {
		return sesiones.findVivaPorTurno(organizationId, turnoId).isPresent();
	}

	@Override
	public Map<Long, AtencionDeTurno> atencionesDe(long organizationId, Collection<Long> turnoIds) {
		if (turnoIds == null || turnoIds.isEmpty()) {
			return Map.of();
		}
		return sesiones.findVivasPorTurnos(organizationId, turnoIds).stream()
				.collect(Collectors.toMap(
						Sesion::getTurnoId,
						sesion -> new AtencionDeTurno(sesion.getId(), sesion.estaCerrada())));
	}
}
