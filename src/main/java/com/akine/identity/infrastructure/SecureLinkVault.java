package com.akine.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traspaso EFIMERO del enlace ya construido, desde quien encola hasta quien envia.
 *
 * <h2>El problema que resuelve</h2>
 *
 * <p>Dos contratos que ya existen y que no coinciden. {@code identity.domain.port
 * .NotificationOutboxPort} entrega el enlace ya armado (challenge D-6: construirlo en identity
 * y que el worker solo lo transporte). {@code notification.spi.NotificationEnqueueCommand} no
 * tiene campo para un enlace y no lo va a tener: la tabla {@code notification_outbox} guarda
 * una referencia opaca al token y nada mas (T-11), y el enlace se reconstruye al enviar por
 * {@code notification.spi.SecureLinkResolver}.
 *
 * <p>Reconstruirlo de verdad es imposible: en la base solo esta el SHA-256 del token, y de un
 * digest no se vuelve. El unico lugar donde el valor en claro existe es la memoria del hilo
 * que lo genero, y el envio ocurre despues del commit, en otro hilo.
 *
 * <p>Este componente es ese puente y nada mas: un mapa en memoria, con vencimiento, del que el
 * enlace se saca UNA vez. Cumple las dos reglas que importan — el token en claro no toca la
 * base, ni el payload consultable, ni los backups, ni el log— sin obligar a {@code notification}
 * a conocer a {@code identity}.
 *
 * <h2>Lo que se pierde, dicho de frente</h2>
 *
 * <p>Si el proceso se reinicia entre el commit del negocio y el envio, el enlace desaparece.
 * La notificacion queda FALLIDA con motivo explicito y la persona pide otro correo. Es
 * exactamente el comportamiento que {@code SecureLinkResolver} documenta para un token que ya
 * no sirve, y es preferible a la alternativa: persistir la credencial para sobrevivir a un
 * reinicio.
 *
 * <p>Vive solo en el proceso: con mas de una instancia del backend, el worker de la instancia B
 * no ve lo que encolo la A. El outbox lo trata como enlace no disponible, no como un envio
 * roto. <b>TODO(AKINE-01.03):</b> cerrar esto de raiz —lo correcto es que el correo se redacte
 * en la misma transaccion, o que el token se cifre con una clave del entorno en vez de
 * hashearse— es una decision de diseño que excede esta etapa.
 */
@Component
public class SecureLinkVault {

	private static final Logger log = LoggerFactory.getLogger(SecureLinkVault.class);

	private final Map<String, EnlaceEnEspera> enEspera = new ConcurrentHashMap<>();
	private final Duration ttl;

	public SecureLinkVault(IdentityProperties properties) {
		this.ttl = properties.getLinks().getTtl();
	}

	/**
	 * Deja el enlace a disposicion del envio.
	 *
	 * <p>Sobreescribe si la referencia ya tenia uno: la ultima emision es la que vale, igual
	 * que en la base, donde emitir un token nuevo invalida los anteriores.
	 */
	public void guardar(String referenciaTokenId, String enlace, Instant ahora) {
		if (referenciaTokenId == null || enlace == null) {
			return;
		}
		purgarVencidos(ahora);
		enEspera.put(referenciaTokenId, new EnlaceEnEspera(enlace, ahora.plus(ttl)));
	}

	/**
	 * Saca el enlace y lo borra del mapa.
	 *
	 * <p>Se consume, no se lee: dejarlo mantendria una credencial viva en el heap despues de
	 * haber cumplido su unico proposito. Un reintento administrativo del envio no va a
	 * encontrarlo, y esta bien que asi sea.
	 */
	public Optional<String> tomar(String referenciaTokenId, Instant ahora) {
		if (referenciaTokenId == null) {
			return Optional.empty();
		}
		purgarVencidos(ahora);
		EnlaceEnEspera entrada = enEspera.remove(referenciaTokenId);
		if (entrada == null) {
			log.warn("No hay enlace disponible para la referencia de token pedida: el proceso se "
					+ "reinicio, el envio lo consumio antes o vencio. La notificacion no puede "
					+ "enviarse y la persona debe pedir una nueva");
			return Optional.empty();
		}
		if (entrada.venceEn().isBefore(ahora)) {
			return Optional.empty();
		}
		return Optional.of(entrada.enlace());
	}

	/** Cuantos enlaces estan esperando envio. Existe para los tests, no para el negocio. */
	int pendientes() {
		return enEspera.size();
	}

	/**
	 * Descarta los vencidos.
	 *
	 * <p>Sin esto, un correo que nunca se envia deja su enlace en memoria para siempre: una
	 * credencial retenida sin motivo y una fuga de memoria en la misma linea.
	 */
	private void purgarVencidos(Instant ahora) {
		Iterator<Map.Entry<String, EnlaceEnEspera>> iterador = enEspera.entrySet().iterator();
		while (iterador.hasNext()) {
			if (iterador.next().getValue().venceEn().isBefore(ahora)) {
				iterador.remove();
			}
		}
	}

	/** Un enlace esperando su envio, con el instante a partir del cual deja de servir. */
	private record EnlaceEnEspera(String enlace, Instant venceEn) {
	}
}
