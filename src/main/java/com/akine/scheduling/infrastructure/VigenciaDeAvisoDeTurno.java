package com.akine.scheduling.infrastructure;

import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.VigenciaDeNotificacion;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.Turno;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Le dice al outbox si un aviso de reserva o de reprogramacion todavia tiene sentido.
 *
 * <p>Es el mismo defecto que el de la invitacion cancelada (QA, PR #95) en otro modulo: con el
 * relay caido, el aviso "tu turno quedo reservado" espera en la cola; si mientras tanto el turno
 * se cancela, al volver el relay la persona recibia la reserva <b>y</b> la cancelacion —y nada
 * garantiza el orden, porque las dos filas compiten por {@code proxima_ejecucion_en}—. Un turno
 * cancelado ya tiene su propio aviso: el de reserva o el de reprogramacion sobre ese turno no
 * agrega nada y puede confundir.
 *
 * <p><b>El aviso de cancelacion no se revalida nunca</b>: un turno cancelado no vuelve atras
 * (EstadoTurno.CANCELADO es terminal), asi que lo que anuncia sigue siendo cierto.
 *
 * <p>La clave la arma {@code AvisosDeTurno}: {@code turno-reservado:<id>} y
 * {@code turno-reprogramado:<id>:v<version>}. Se parsea aca porque es este modulo el que la armo.
 */
@Component
public class VigenciaDeAvisoDeTurno implements VigenciaDeNotificacion {

	private final TurnoRepository turnos;

	public VigenciaDeAvisoDeTurno(TurnoRepository turnos) {
		this.turnos = turnos;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> motivoDeNoVigencia(NotificacionEnCurso notificacion) {
		if (notificacion.tipo() != NotificationType.TURNO_RESERVADO
				&& notificacion.tipo() != NotificationType.TURNO_REPROGRAMADO) {
			return Optional.empty();
		}
		Long turnoId = turnoIdDe(notificacion.claveIdempotente());
		if (turnoId == null || notificacion.organizationId() == null) {
			return Optional.empty();
		}
		Optional<Turno> turno = turnos.findById(turnoId)
				.filter(t -> notificacion.organizationId().equals(t.getOrganizationId()));
		if (turno.isEmpty()) {
			return Optional.of("No se envia: el turno ya no existe");
		}
		if (turno.get().getEstado() == EstadoTurno.CANCELADO) {
			return Optional.of("No se envia: el turno se cancelo antes de que saliera este aviso");
		}
		return Optional.empty();
	}

	/** El segundo segmento de {@code turno-reservado:<id>} o {@code turno-reprogramado:<id>:v<n>}. */
	private static Long turnoIdDe(String clave) {
		if (clave == null) {
			return null;
		}
		String[] partes = clave.split(":");
		if (partes.length < 2) {
			return null;
		}
		try {
			return Long.valueOf(partes[1]);
		} catch (NumberFormatException noEsLaClaveEsperada) {
			return null;
		}
	}
}
