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
		if (referenciaTokenId == null) {
			return Optional.empty();
		}

		// La invitacion es el unico enlace seguro cuyo token NO vive en token_verificacion: esa
		// tabla exige cuenta_id NOT NULL y una invitacion se emite antes de que la cuenta exista
		// —lo dice TipoTokenVerificacion.INVITACION—. Su hash vive en colaborador_invitacion.
		//
		// Hasta que esto se corrigio, este metodo devolvia vacio para las invitaciones y el
		// correo NUNCA salia: el worker lo marcaba como "token muerto" sin haberlo buscado en
		// ningun lado. Se comprobo contra el stack real.
		//
		// Se lee el vault directo y no se revalida la invitacion contra la base: el enlace esta
		// en memoria porque el encolado acaba de ocurrir, y el vault tiene su propio
		// vencimiento. Queda una ventana chica —cancelar la invitacion entre el encolado y el
		// envio manda un enlace que ya no abre nada—, y se acepta porque aceptar la invitacion
		// revalida contra colaborador_invitacion de todas formas.
		if (tipo == NotificationType.INVITACION_COLABORADOR) {
			return secureLinkVault.leer(referenciaTokenId, Instant.now());
		}

		Optional<TipoTokenVerificacion> esperado = tipoDeTokenDe(tipo);
		if (esperado.isEmpty()) {
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

		return secureLinkVault.leer(referenciaTokenId, ahora);
	}

	/**
	 * Suelta el enlace: el worker termino con esta notificacion, con exito o para siempre.
	 *
	 * <p>No toca la base ni el token —consumir el token es cosa de quien usa el enlace, no de
	 * quien lo transporta—: solo libera la copia en claro que el vault sostenia mientras duraba
	 * el envio. Sin transaccion, idempotente y sin lanzar, como pide el puerto.
	 */
	@Override
	public void consumeLink(NotificationType tipo, String referenciaTokenId) {
		// Se pregunta si el TIPO lleva enlace, no si tiene fila en token_verificacion. Con la
		// pregunta vieja la invitacion se colaba por el return temprano y su copia en claro
		// quedaba en el vault hasta vencer, aunque el correo ya hubiera salido.
		if (tipo == null || !tipo.requiereEnlaceSeguro()) {
			return;
		}
		secureLinkVault.consumir(referenciaTokenId);
	}

	/**
	 * Que tipo de token de {@code token_verificacion} corresponde a cada correo.
	 *
	 * <p>{@code CUENTA_YA_REGISTRADA} no lleva enlace, y {@code INVITACION_COLABORADOR} no tiene
	 * fila en esa tabla: lo resuelve {@code resolveLink} antes de llegar aca, leyendo el vault.
	 * Los dos devuelven vacio en vez de buscar un token que no existe.
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
