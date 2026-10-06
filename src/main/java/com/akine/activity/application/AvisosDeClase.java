package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los avisos de M28 (RF-M26-006 y RF-M26-007).
 *
 * <h2>Se encolan DENTRO de la transaccion del negocio</h2>
 *
 * <p>{@link NotificationOutbox#enqueue} tiene propagacion {@code MANDATORY}: si el negocio no
 * comitea, el aviso no sale; si comitea, el aviso existe. Mandar el mail desde el servicio —o
 * despues del commit— es como se termina avisando de una reprogramacion que despues hizo rollback,
 * y no hay forma de desmandar un correo.
 *
 * <h2>Nadie se entera de quien mas esta anotado</h2>
 *
 * <p>Una fila del outbox por destinatario, y el payload pasa por la lista blanca de claves de
 * {@code SanitizedPayload}: no hay forma de que el nombre de otro participante entre en el mensaje
 * aunque alguien lo intente. Es CA-M26-006-06 —"notificar sin exponer la lista completa"— hecho
 * cumplir por construccion y no por cuidado.
 *
 * <h2>Y a quien no tiene correo no se le avisa, sin fallar</h2>
 *
 * <p>El padron admite personas sin email: se las atiende igual. Que una clase no se pueda cancelar
 * porque un participante de hace un ano no cargo su correo seria una regla peor que la que
 * intentaria proteger. Se saltea y se deja constancia en el log.
 */
@Component
public class AvisosDeClase {

	private static final Logger log = LoggerFactory.getLogger(AvisosDeClase.class);

	/** Formato legible para el humano que recibe el mail. La zona la pone la sede, no el servidor. */
	private static final DateTimeFormatter FORMATO =
			DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm");

	private final NotificationOutbox outbox;
	private final ContactoDirectory contactos;

	public AvisosDeClase(NotificationOutbox outbox, ContactoDirectory contactos) {
		this.outbox = outbox;
		this.contactos = contactos;
	}

	/**
	 * Avisa a todos los inscriptos de que la clase se movio o se cancelo (RF-M26-006).
	 *
	 * <p>Un solo batch de contactos y no uno por participante: resolverlos de a uno convierte una
	 * reprogramacion en tantas consultas como inscriptos haya, que crece justo con lo llena que
	 * este la clase.
	 *
	 * @param momentoDelCambio entra en la clave idempotente para que dos cambios distintos de la
	 *                         misma clase produzcan dos avisos, y un reintento del mismo cambio
	 *                         siga produciendo uno
	 */
	public void avisarCambioDeClase(
			ClaseProgramada clase,
			String sedeNombre,
			String zonaHoraria,
			List<InscripcionClase> destinatarios,
			Instant momentoDelCambio) {

		if (destinatarios.isEmpty()) {
			return;
		}
		Map<Long, ContactoDePersona> contactoPorPersona = contactos.findAll(
				clase.getOrganizationId(),
				destinatarios.stream().map(InscripcionClase::getPersonaId).toList());

		for (InscripcionClase inscripcion : destinatarios) {
			encolar(
					NotificationType.CLASE_MODIFICADA,
					contactoPorPersona.get(inscripcion.getPersonaId()),
					"clase-modificada:" + inscripcion.getId() + ":" + momentoDelCambio,
					clase, sedeNombre, zonaHoraria);
		}
	}

	/** Avisa a quien acaba de entrar desde la lista de espera (RF-M26-007). */
	public void avisarCupoLiberado(
			ClaseProgramada clase,
			String sedeNombre,
			String zonaHoraria,
			InscripcionClase promovida) {

		Map<Long, ContactoDePersona> contacto = contactos.findAll(
				clase.getOrganizationId(), List.of(promovida.getPersonaId()));

		encolar(
				NotificationType.CUPO_LIBERADO,
				contacto.get(promovida.getPersonaId()),
				// La clave es el id de la inscripcion y nada mas: una inscripcion se promueve una
				// sola vez, asi que un reintento de la transaccion no manda un segundo mail.
				"cupo-liberado:" + promovida.getId(),
				clase, sedeNombre, zonaHoraria);
	}

	private void encolar(
			NotificationType tipo,
			ContactoDePersona contacto,
			String claveIdempotente,
			ClaseProgramada clase,
			String sedeNombre,
			String zonaHoraria) {

		if (contacto == null || !contacto.notificable()) {
			log.info("Sin correo para notificar {}: claseId={}", tipo, clase.getId());
			return;
		}
		outbox.enqueue(new NotificationEnqueueCommand(
				tipo,
				contacto.email(),
				claveIdempotente,
				clase.getOrganizationId(),
				null,
				depurar(tipo, clase, Map.of(
						"nombre", contacto.nombre() == null ? "" : contacto.nombre(),
						"claseTitulo", clase.getTitulo() == null ? "" : clase.getTitulo(),
						"claseInicio", FORMATO.format(
								clase.getInicio().atZone(ZoneId.of(zonaHoraria))),
						"consultorioNombre", sedeNombre == null ? "" : sedeNombre))));
	}

	/**
	 * Deja solo los datos que el outbox va a aceptar (defecto corregido en AKINE E-5).
	 *
	 * <p>{@code enqueue} rechaza un valor con pinta de secreto, y uno de los fragmentos es
	 * {@code secret}: una sede "Jardin Secreto" o una clase "Yoga secreto" lo contienen. El rechazo
	 * ocurre DENTRO de la transaccion del negocio, la deja marcada para rollback —atrapar la
	 * excepcion no la des-marca— y cancelar o reprogramar la clase moria por su nombre, contra
	 * RN-M26-001. Validar cada dato antes con {@link NotificationOutbox#validarDatosDeRender}, que
	 * no participa de la transaccion, permite omitir solo el que no pasa: el template cae a su
	 * texto neutro y el aviso sale igual.
	 */
	private Map<String, String> depurar(
			NotificationType tipo, ClaseProgramada clase, Map<String, String> datos) {
		Map<String, String> aceptados = new LinkedHashMap<>();
		datos.forEach((clave, valor) -> {
			try {
				outbox.validarDatosDeRender(Map.of(clave, valor));
				aceptados.put(clave, valor);
			} catch (IllegalArgumentException rechazado) {
				log.warn("Dato de render omitido en {}: clave={} claseId={}", tipo, clave, clase.getId());
			}
		});
		return aceptados;
	}
}
