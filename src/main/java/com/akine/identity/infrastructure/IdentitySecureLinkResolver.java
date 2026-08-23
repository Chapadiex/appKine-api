package com.akine.identity.infrastructure;

import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.SecureLinkResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Implementacion del puerto invertido {@link SecureLinkResolver} que declara
 * {@code notification}.
 *
 * <p>Lo declara {@code notification} y lo implementa {@code identity}, que es el dueño de los
 * tokens. La flecha resultante —{@code identity -> notification.spi}— es la misma que ya existe
 * cuando identity encola, asi que el grafo sigue siendo aciclico. Registrar este bean apaga el
 * resolutor de reserva de {@code NotificationConfig}, que devuelve vacio siempre.
 *
 * <p>Hace dos comprobaciones, en este orden, y las dos importan:
 * <ol>
 *   <li><b>el token todavia sirve</b>, consultado contra la base: no consumido, no invalidado y
 *       no vencido, y del tipo que ese correo dice llevar. Un enlace de reset entregado por un
 *       correo de activacion seria una via de toma de cuenta;</li>
 *   <li><b>el enlace sigue disponible</b> en {@link SecureLinkVault}. Si no esta, no se puede
 *       reconstruir: la base guarda el SHA-256 del token y de un digest no se vuelve.</li>
 * </ol>
 *
 * <p>Cualquiera de los dos casos devuelve {@link Optional#empty()}, y el worker marca la fila
 * como fallida sin reintentar. Es lo correcto: reenviar un enlace muerto —o no enviar ninguno—
 * no se arregla insistiendo, se arregla emitiendo un token nuevo.
 */
@Component
public class IdentitySecureLinkResolver implements SecureLinkResolver {

	private static final Logger log = LoggerFactory.getLogger(IdentitySecureLinkResolver.class);

	private final TokenVerificacionRepository tokenRepository;
	private final SecureLinkVault secureLinkVault;

	public IdentitySecureLinkResolver(
			TokenVerificacionRepository tokenRepository, SecureLinkVault secureLinkVault) {
		this.tokenRepository = tokenRepository;
		this.secureLinkVault = secureLinkVault;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> resolveLink(NotificationType tipo, String referenciaTokenId) {
		Optional<TipoTokenVerificacion> esperado = tipoDeTokenDe(tipo);
		if (esperado.isEmpty() || referenciaTokenId == null) {
			return Optional.empty();
		}

		Long tokenId = idDe(referenciaTokenId);
		if (tokenId == null) {
			log.warn("Referencia de token no numerica en el outbox: no se puede resolver el enlace");
			return Optional.empty();
		}

		Instant ahora = Instant.now();
		Optional<TokenVerificacion> token = tokenRepository.findById(tokenId)
				.filter(fila -> fila.getTipo() == esperado.get())
				.filter(fila -> fila.esUtilizableEn(ahora));

		if (token.isEmpty()) {
			log.info("El token referenciado ya no sirve: la notificacion no se envia");
			return Optional.empty();
		}

		return secureLinkVault.tomar(referenciaTokenId, ahora);
	}

	/**
	 * Que tipo de token de identidad corresponde a cada correo.
	 *
	 * <p>{@code CUENTA_YA_REGISTRADA} no lleva enlace y {@code INVITACION_COLABORADOR} es de
	 * 01.03: los dos devuelven vacio en vez de buscar un token que no existe.
	 */
	private static Optional<TipoTokenVerificacion> tipoDeTokenDe(NotificationType tipo) {
		if (tipo == null) {
			return Optional.empty();
		}
		return switch (tipo) {
			case ACTIVACION_CUENTA -> Optional.of(TipoTokenVerificacion.ACTIVACION);
			case RECUPERACION_PASSWORD -> Optional.of(TipoTokenVerificacion.RESET);
			case INVITACION_COLABORADOR, CUENTA_YA_REGISTRADA -> Optional.empty();
		};
	}

	private static Long idDe(String referenciaTokenId) {
		try {
			return Long.valueOf(referenciaTokenId.strip());
		} catch (NumberFormatException noEsUnId) {
			return null;
		}
	}
}
