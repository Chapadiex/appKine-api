package com.akine.notification.application;

import com.akine.notification.domain.ErrorSanitizer;
import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.SanitizedPayload;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Encolado de notificaciones. Implementa {@link NotificationOutbox}, que es lo unico que ven
 * los otros modulos.
 *
 * <p><b>{@code Propagation.MANDATORY}.</b> Este metodo participa de la transaccion del
 * llamador y jamas abre una propia. Es la esencia del patron: la fila del outbox se confirma
 * junto con la operacion de negocio o no se confirma ninguna de las dos. Con
 * {@code REQUIRED}, un llamador sin transaccion comitearia la notificacion por su cuenta y un
 * fallo posterior del negocio dejaria enviado un mail sobre algo que nunca paso;
 * {@code MANDATORY} convierte ese error de uso en una excepcion en el primer test que lo
 * ejercite.
 *
 * <p><b>Lo que este servicio NO hace: enviar.</b> El envio ocurre despues, en el worker, fuera
 * de esta transaccion. Por eso un SMTP caido no puede revertir una cuenta ya creada
 * (RN-M26-001).
 */
@Service
public class OutboxEnqueueService implements NotificationOutbox {

	private static final Logger log = LoggerFactory.getLogger(OutboxEnqueueService.class);

	private final NotificationOutboxRepositoryPort repository;
	private final NotificationClock clock;
	private final OutboxWorkerSettings settings;

	public OutboxEnqueueService(
			NotificationOutboxRepositoryPort repository,
			NotificationClock clock,
			OutboxWorkerSettings settings) {
		this.repository = repository;
		this.clock = clock;
		this.settings = settings;
	}

	/**
	 * La validacion de {@code datosDeRender}, sola y sin efectos.
	 *
	 * <p>Sin transaccion y sin tocar la base: no encola nada. Existe para que un productor pueda
	 * ejecutarla en el borde de su caso de uso, antes de ramificar por datos privados. Es
	 * literalmente el mismo constructor que usa {@link #enqueue}, asi que no hay dos reglas que
	 * puedan divergir.
	 */
	@Override
	public void validarDatosDeRender(java.util.Map<String, String> datosDeRender) {
		SanitizedPayload.of(datosDeRender);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public long enqueue(NotificationEnqueueCommand command) {
		if (command == null) {
			throw new IllegalArgumentException("El comando de encolado es obligatorio");
		}

		// Idempotencia (RF-M26-005, RN-M26-003, seccion 34). Este SELECT es la ruta feliz del
		// reintento, no la garantia: la garantia es el UNIQUE de clave_idempotente, que es
		// quien decide cuando dos productores concurrentes insertan a la vez.
		Optional<NotificationOutboxEntry> yaEncolada =
				repository.findByClaveIdempotente(command.claveIdempotente());
		if (yaEncolada.isPresent()) {
			NotificationOutboxEntry existente = yaEncolada.get();
			log.debug("Notificacion ya encolada, no se duplica: clave={} estado={}",
					command.claveIdempotente(), existente.getEstado());
			return existente.getId();
		}

		NotificationOutboxEntry entry = new NotificationOutboxEntry(
				command.tipo(),
				command.destinatario(),
				command.claveIdempotente(),
				command.organizationId(),
				command.referenciaTokenId(),
				SanitizedPayload.of(command.datosDeRender()),
				settings.backoff().maxIntentos(),
				clock.now());

		NotificationOutboxEntry guardada = repository.save(entry);
		log.info("Notificacion encolada: tipo={} destinatario={} organizationId={}",
				command.tipo(), ErrorSanitizer.maskEmail(command.destinatario()),
				command.organizationId());
		return guardada.getId();
	}
}
