package com.akine.notification.application;

import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.EmailTemplates;
import com.akine.notification.domain.ErrorSanitizer;
import com.akine.notification.domain.exception.EmailDeliveryException;
import com.akine.notification.domain.port.EmailSender;
import com.akine.notification.spi.SecureLinkResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Un tick del worker: recupera lo huerfano, reclama un lote y lo entrega.
 *
 * <p><b>Sin transaccion propia, a proposito.</b> Cada paso que toca la base se delega en
 * {@link OutboxDispatchService}, que abre y cierra su propia transaccion corta. El envio —lo
 * unico lento y lo unico que no es transaccional— ocurre entre esas transacciones, nunca
 * dentro.
 *
 * <p><b>Un fallo no arrastra al lote.</b> Cada notificacion se resuelve por separado, con su
 * propio try/catch y su propia transaccion de resultado. Una direccion invalida en la primera
 * fila no puede impedir que se entreguen las otras diecinueve.
 *
 * <p><b>Idempotencia y la ventana honesta.</b> Mandar un mail no es transaccional con la base:
 * si el proceso muere entre "el SMTP acepto el mensaje" y "la fila quedo ENVIADA", el lease
 * vencido devuelve la fila a la cola y esa persona puede recibir el mismo mail dos veces. Se
 * acepta y se documenta: lo que RN-M26-003 prohibe es duplicar el EFECTO DE NEGOCIO, y aca no
 * hay ninguno —el worker solo transporta; la cuenta, el token y la invitacion ya existian
 * antes de que el mail saliera—. El enlace es de un solo uso, asi que el segundo mail no
 * habilita una segunda activacion.
 *
 * <p><b>El enlace se resuelve antes de enviar y se suelta despues.</b> Resolver no consume:
 * consumir al resolver le daba a toda notificacion con enlace un presupuesto real de UN intento
 * —el segundo no encontraba el enlace y la fila moria FALLIDA por "token invalido" con el token
 * vivo—. El aviso de que ya no hace falta ({@code SecureLinkResolver#consumeLink}) sale tras un
 * envio exitoso y tras un fallo permanente; nunca tras uno transitorio, porque esa fila vuelve
 * a la cola y va a necesitarlo.
 *
 * <p><b>Que pasa entonces en la ventana honesta de arriba, y por que se eligio asi.</b> Si el
 * proceso muere entre "el SMTP acepto" y "la fila quedo ENVIADA", el lease vencido reencola la
 * fila y ahora el enlace sigue disponible: sale un <b>segundo correo valido</b> a la misma
 * persona. Antes de este cambio, ese mismo caso terminaba en FALLIDA con el correo ya
 * entregado. <b>Se prefiere el duplicado</b>, por tres razones:
 * <ol>
 *   <li>el duplicado no duplica ningun efecto de negocio (RN-M26-003) y el enlace es de un solo
 *       uso: la persona activa con el que abra primero y el otro queda muerto;</li>
 *   <li>una fila FALLIDA con el correo entregado <b>miente</b> a quien mira la cola: invita a
 *       reintentar a mano o a decirle a la persona que pida otro correo que no necesita, y
 *       falsea toda metrica de entrega;</li>
 *   <li>el modo de falla se elige por su peor caso. El del duplicado es un correo de mas,
 *       molesto y explicable. El de la fila mentirosa es una persona que no puede entrar
 *       mientras el sistema informa que el envio fallo.</li>
 * </ol>
 */
@Service
public class OutboxDispatcher {

	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

	private final OutboxDispatchService dispatchService;
	private final EmailSender emailSender;
	private final SecureLinkResolver linkResolver;

	public OutboxDispatcher(
			OutboxDispatchService dispatchService,
			EmailSender emailSender,
			SecureLinkResolver linkResolver) {
		this.dispatchService = dispatchService;
		this.emailSender = emailSender;
		this.linkResolver = linkResolver;
	}

	/**
	 * Ejecuta un ciclo completo.
	 *
	 * @return cuantas notificaciones se intentaron entregar
	 */
	public int runOnce() {
		dispatchService.recuperarLeasesVencidos();
		List<PendingDelivery> lote = dispatchService.reclamarLote();
		for (PendingDelivery pendiente : lote) {
			entregar(pendiente);
		}
		return lote.size();
	}

	private void entregar(PendingDelivery pendiente) {
		try {
			String enlace = resolverEnlace(pendiente);
			if (pendiente.tipo().requiereEnlaceSeguro() && enlace == null) {
				// El token de un solo uso ya no sirve: fue consumido, revocado o vencio.
				// No es transitorio y no se reintenta (T-11): reenviar un enlace muerto no
				// ayuda, la persona pide uno nuevo.
				dispatchService.registrarFalloPermanente(pendiente.id(),
						"El token referenciado ya no es valido: fue consumido, revocado o vencio");
				soltarEnlace(pendiente);
				return;
			}
			EmailMessage mensaje = EmailTemplates.render(
					pendiente.tipo(), pendiente.destinatario(), pendiente.payload(), enlace);
			emailSender.send(mensaje);
			dispatchService.registrarExito(pendiente.id());
			soltarEnlace(pendiente);
		} catch (EmailDeliveryException e) {
			registrarFallo(pendiente, ErrorSanitizer.sanitize(e), e.esTransitorio());
		} catch (IllegalArgumentException | IllegalStateException e) {
			// Datos o template mal formados: reintentar daria exactamente lo mismo.
			registrarFallo(pendiente, ErrorSanitizer.sanitize(e), false);
		} catch (RuntimeException e) {
			// Lo inesperado se trata como transitorio: puede ser un corte de red o una caida
			// momentanea de una dependencia, y descartar la notificacion por las dudas es peor
			// que reintentarla unas pocas veces mas antes de agotarla.
			registrarFallo(pendiente, ErrorSanitizer.sanitize(e), true);
		}
	}

	private String resolverEnlace(PendingDelivery pendiente) {
		if (!pendiente.tipo().requiereEnlaceSeguro()) {
			return null;
		}
		Optional<String> enlace =
				linkResolver.resolveLink(pendiente.tipo(), pendiente.referenciaTokenId());
		return enlace.orElse(null);
	}

	private void registrarFallo(PendingDelivery pendiente, String motivo, boolean transitorio) {
		log.debug("Fallo la entrega de la notificacion id={} (transitorio={})",
				pendiente.id(), transitorio);
		if (transitorio) {
			// El enlace NO se suelta: esta fila vuelve a la cola y el proximo intento lo necesita.
			dispatchService.registrarFalloTransitorio(pendiente.id(), motivo);
		} else {
			dispatchService.registrarFalloPermanente(pendiente.id(), motivo);
			soltarEnlace(pendiente);
		}
	}

	/**
	 * Avisa al dueño del token que el enlace ya no hace falta.
	 *
	 * <p>Se llama <b>despues</b> de registrar el resultado y nunca tras un fallo transitorio: la
	 * fila que vuelve a la cola necesita su enlace en el proximo intento. Cualquier excepcion se
	 * traga a proposito: el resultado ya esta escrito en la base y no puede desandarse por un
	 * problema al liberar memoria. Si se propagara, el catch de la entrega degradaria un envio
	 * exitoso a "fallo transitorio" y el mismo correo saldria de nuevo.
	 */
	private void soltarEnlace(PendingDelivery pendiente) {
		if (!pendiente.tipo().requiereEnlaceSeguro()) {
			return;
		}
		try {
			linkResolver.consumeLink(pendiente.tipo(), pendiente.referenciaTokenId());
		} catch (RuntimeException e) {
			log.warn("No se pudo soltar el enlace de la notificacion id={}: {}",
					pendiente.id(), ErrorSanitizer.sanitize(e));
		}
	}
}
