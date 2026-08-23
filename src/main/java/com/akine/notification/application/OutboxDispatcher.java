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
				return;
			}
			EmailMessage mensaje = EmailTemplates.render(
					pendiente.tipo(), pendiente.destinatario(), pendiente.payload(), enlace);
			emailSender.send(mensaje);
			dispatchService.registrarExito(pendiente.id());
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
			dispatchService.registrarFalloTransitorio(pendiente.id(), motivo);
		} else {
			dispatchService.registrarFalloPermanente(pendiente.id(), motivo);
		}
	}
}
