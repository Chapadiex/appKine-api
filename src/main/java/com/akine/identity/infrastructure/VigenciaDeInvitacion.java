package com.akine.identity.infrastructure;

import com.akine.identity.domain.ColaboradorInvitacion;
import com.akine.identity.domain.port.ColaboradorInvitacionRepositoryPort;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.VigenciaDeNotificacion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Le dice al outbox si el correo de una invitacion todavia tiene sentido.
 *
 * <h2>El defecto</h2>
 *
 * <p>{@link IdentitySecureLinkResolver} lee el enlace de la invitacion del vault sin mirar la
 * invitacion, con el argumento de que "el encolado acaba de ocurrir". Con el relay de correo
 * caido eso deja de ser cierto: la fila espera en REINTENTABLE, el administrador cancela la
 * invitacion, el relay vuelve y el correo sale con un enlace que ya no abre nada. Lo encontro la
 * validacion de la guia de QA (PR #95).
 *
 * <h2>Que se mira</h2>
 *
 * <p>La clave idempotente la arma {@code ColaboradorInvitacionService#encolarCorreo} como
 * {@code invitacion:<id>:<expiraEn en ms>}, y por eso se parsea aca, en el modulo que la armo.
 * Con el id se busca la invitacion <b>en el tenant de la fila</b>; con el instante se sabe si el
 * correo corresponde a la emision vigente. El correo no sale si la invitacion:
 * <ul>
 *   <li>ya no existe en ese tenant;</li>
 *   <li>fue cancelada, aceptada o rechazada;</li>
 *   <li>fue <b>reemplazada</b> por un reenvio —el reenvio rota el token y mueve
 *       {@code expiraEn}, asi que el enlace de este correo ya no abre—;</li>
 *   <li>vencio.</li>
 * </ul>
 *
 * <p>La activacion y la recuperacion de contrasena no pasan por aca: su token vive en
 * {@code token_verificacion} y {@link IdentitySecureLinkResolver} ya lo revalida contra la base
 * antes de cada intento.
 */
@Component
public class VigenciaDeInvitacion implements VigenciaDeNotificacion {

	private static final String PREFIJO = "invitacion:";

	private final ColaboradorInvitacionRepositoryPort invitaciones;
	private final IdentityClock clock;

	public VigenciaDeInvitacion(ColaboradorInvitacionRepositoryPort invitaciones, IdentityClock clock) {
		this.invitaciones = invitaciones;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> motivoDeNoVigencia(NotificacionEnCurso notificacion) {
		if (notificacion.tipo() != NotificationType.INVITACION_COLABORADOR) {
			return Optional.empty();
		}
		ClaveDeInvitacion clave = ClaveDeInvitacion.de(notificacion.claveIdempotente());
		if (clave == null || notificacion.organizationId() == null) {
			// Una clave que no se reconoce no es motivo para callar un correo: ante la duda, sale.
			return Optional.empty();
		}

		Optional<ColaboradorInvitacion> encontrada =
				invitaciones.findByIdAndOrganizationId(clave.invitacionId(), notificacion.organizationId());
		if (encontrada.isEmpty()) {
			return Optional.of("No se envia: la invitacion ya no existe");
		}
		ColaboradorInvitacion invitacion = encontrada.get();
		return switch (invitacion.getEstado()) {
			case CANCELADA -> Optional.of("No se envia: la invitacion fue cancelada");
			case ACEPTADA -> Optional.of("No se envia: la invitacion ya fue aceptada");
			case RECHAZADA -> Optional.of("No se envia: la invitacion fue rechazada");
			case PENDIENTE -> motivoDeLaPendiente(invitacion, clave);
		};
	}

	private Optional<String> motivoDeLaPendiente(ColaboradorInvitacion invitacion, ClaveDeInvitacion clave) {
		// Tolerancia de 1 ms: la clave se armo con el instante en memoria y la base lo guarda en
		// DATETIME(6), que REDONDEA al microsegundo (05.04). Un redondeo que cruza el borde del
		// milisegundo no puede pasar por reenvio; un reenvio real mueve la fecha dias, no 1 ms.
		if (Math.abs(invitacion.getExpiraEn().toEpochMilli() - clave.expiraEnMs()) > 1) {
			return Optional.of("No se envia: la invitacion se reenvio y este enlace fue reemplazado");
		}
		if (invitacion.estaVencida(clock.now())) {
			return Optional.of("No se envia: la invitacion vencio");
		}
		return Optional.empty();
	}

	/** {@code invitacion:<id>:<expiraEn en ms>}, tal como la arma el servicio de invitaciones. */
	private record ClaveDeInvitacion(long invitacionId, long expiraEnMs) {

		static ClaveDeInvitacion de(String clave) {
			if (clave == null || !clave.startsWith(PREFIJO)) {
				return null;
			}
			String[] partes = clave.substring(PREFIJO.length()).split(":");
			if (partes.length != 2) {
				return null;
			}
			try {
				return new ClaveDeInvitacion(Long.parseLong(partes[0]), Long.parseLong(partes[1]));
			} catch (NumberFormatException noEsLaClaveEsperada) {
				return null;
			}
		}
	}
}
