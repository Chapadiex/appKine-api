package com.akine.scheduling.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.domain.EstadoRecepcion;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.RecepcionEvento;
import com.akine.scheduling.domain.TipoEventoRecepcion;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Persiste una transicion de recepcion con su evento y su auditoria, siempre juntos (DP-05).
 *
 * <p>Vive aparte de {@link CicloDeRecepcionService} porque la cancelacion del turno
 * ({@link CicloDeTurnoService}) tambien cierra recepciones, y las dos tienen que dejar
 * exactamente el mismo rastro. Corre dentro de la transaccion de quien la llama.
 */
@Component
public class RegistroDeRecepcion {

	private static final String ENTIDAD = "Recepcion";

	private final RecepcionRepositoryPort recepciones;
	private final RecepcionEventoRepositoryPort eventos;
	private final AuditTrail auditTrail;

	public RegistroDeRecepcion(
			RecepcionRepositoryPort recepciones,
			RecepcionEventoRepositoryPort eventos,
			AuditTrail auditTrail) {
		this.recepciones = recepciones;
		this.eventos = eventos;
		this.auditTrail = auditTrail;
	}

	public Optional<Recepcion> vigente(long organizationId, long turnoId) {
		return recepciones.findVigente(organizationId, turnoId);
	}

	/** {@code true} si el turno tiene una recepcion abierta: alguien llego y no termino. */
	public boolean tieneAbierta(long organizationId, long turnoId) {
		return vigente(organizationId, turnoId).filter(Recepcion::estaAbierta).isPresent();
	}

	/**
	 * Guarda la recepcion ya transicionada y deja su evento y su auditoria.
	 *
	 * <p>{@code saveAndFlush} antes del evento: la llegada necesita el id que asigna la base, y en
	 * las demas el flush es el que hace chocar el {@code @Version} aca y no al commitear, con un
	 * error que nombra a la recepcion.
	 */
	public Recepcion registrar(
			Recepcion recepcion, TipoEventoRecepcion tipo, EstadoRecepcion anterior,
			String motivo, long actorCuentaId, Instant ahora) {

		Recepcion guardada = recepciones.saveAndFlush(recepcion);
		eventos.registrar(RecepcionEvento.de(guardada, tipo, anterior, motivo, actorCuentaId, ahora));
		auditTrail.record(new AuditEntry(
				guardada.getOrganizationId(),
				guardada.getConsultorioId(),
				actorCuentaId,
				"RECEPCION_" + tipo.name(),
				ENTIDAD,
				guardada.getId(),
				anterior == null ? null : anterior.name(),
				guardada.getEstado().name(),
				Map.of("turnoId", String.valueOf(guardada.getTurnoId())),
				motivo,
				null,
				ahora));
		return guardada;
	}

	/**
	 * Cierra la recepcion abierta del turno porque el turno se cancelo, conservando la llegada.
	 * No hace nada si no hay recepcion abierta.
	 */
	public void cerrarPorCancelacion(
			long organizationId, long turnoId, String motivo, long actorCuentaId, Instant ahora) {

		vigente(organizationId, turnoId)
				.filter(Recepcion::estaAbierta)
				.ifPresent(recepcion -> {
					EstadoRecepcion anterior = recepcion.getEstado();
					recepcion.cerrarPorCancelacion(motivo, actorCuentaId, ahora);
					registrar(recepcion, TipoEventoRecepcion.CIERRE_POR_CANCELACION, anterior,
							motivo, actorCuentaId, ahora);
				});
	}
}
