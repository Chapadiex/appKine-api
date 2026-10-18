package com.akine.activity.infrastructure;

import com.akine.activity.domain.EstadoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.VigenciaDeNotificacion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Le dice al outbox si un aviso de pase todavia tiene sentido (RF-M26-008, AKINE-08.08).
 *
 * <p>El mismo defecto que el de la invitacion cancelada (PR #95) y el del turno cancelado: con el
 * relay caido el aviso espera en la cola, y mientras tanto el pase puede <b>anularse</b> —"tu pack
 * esta por vencer" de un pack que ya no existe— o <b>vencer</b> —"esta por vencer" de algo que ya
 * vencio—. En los dos casos no se manda. Un saldo bajo de un pase vencido o agotado sigue siendo
 * cierto, y se manda.
 *
 * <p>La clave la arma {@code AvisosDePases}: {@code pase-por-vencer:<id>} y
 * {@code pase-saldo-bajo:<id>}. Se parsea aca porque es este modulo el que la armo.
 */
@Component
class VigenciaDeAvisoDePase implements VigenciaDeNotificacion {

	private final PaseServicioRepository pases;

	VigenciaDeAvisoDePase(PaseServicioRepository pases) {
		this.pases = pases;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> motivoDeNoVigencia(NotificacionEnCurso notificacion) {
		if (notificacion.tipo() != NotificationType.PASE_POR_VENCER
				&& notificacion.tipo() != NotificationType.PASE_SALDO_BAJO) {
			return Optional.empty();
		}
		Long paseId = paseIdDe(notificacion.claveIdempotente());
		if (paseId == null || notificacion.organizationId() == null) {
			return Optional.empty();
		}
		Optional<PaseServicio> pase = pases.findById(paseId)
				.filter(p -> notificacion.organizationId().equals(p.getOrganizationId()));
		if (pase.isEmpty()) {
			return Optional.of("No se envia: el pase ya no existe");
		}
		EstadoPase estado = pase.get().getEstado();
		if (estado == EstadoPase.ANULADO) {
			return Optional.of("No se envia: el pase se anulo antes de que saliera este aviso");
		}
		if (notificacion.tipo() == NotificationType.PASE_POR_VENCER && estado == EstadoPase.VENCIDO) {
			return Optional.of("No se envia: el pase ya vencio antes de que saliera este aviso");
		}
		return Optional.empty();
	}

	/** El segundo segmento de {@code pase-por-vencer:<id>} o {@code pase-saldo-bajo:<id>}. */
	private static Long paseIdDe(String clave) {
		if (clave == null) {
			return null;
		}
		String[] partes = clave.split(":");
		if (partes.length != 2) {
			return null;
		}
		try {
			return Long.valueOf(partes[1]);
		} catch (NumberFormatException noEsLaClaveEsperada) {
			return null;
		}
	}
}
