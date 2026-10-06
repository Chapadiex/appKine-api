package com.akine.scheduling.application;

import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import com.akine.scheduling.domain.Turno;
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
 * Los avisos de turno al paciente: reserva (RF-M26-002), cancelacion y reprogramacion
 * (RF-M26-003). AKINE E-5.
 *
 * <h2>Se encolan DENTRO de la transaccion del negocio</h2>
 *
 * <p>{@link NotificationOutbox#enqueue} tiene propagacion {@code MANDATORY}: la fila del outbox
 * comitea junto con la reserva, la cancelacion o la reprogramacion, o no comitea ninguna de las
 * dos. Mandar el mail desde el servicio —o despues del commit— es como se termina avisando de un
 * turno que despues hizo rollback, y un correo no se puede desmandar (regla de 01.02).
 *
 * <h2>PHI minima (RN-M26-002)</h2>
 *
 * <p>Fecha, hora, sede y nombre comercial del servicio. <b>Nada mas</b>: ni el profesional, ni el
 * motivo de una cancelacion o reprogramacion —texto libre del recepcionista que puede decir algo
 * clinico—, ni un id interno. La lista blanca de {@code SanitizedPayload} lo hace cumplir.
 *
 * <h2>Lo que NUNCA hace: hacer fallar la operacion</h2>
 *
 * <p>RN-M26-001: un problema de la notificacion no revierte la operacion principal. Dos casos lo
 * ponen a prueba:
 *
 * <ul>
 *   <li><b>La persona no tiene correo.</b> El padron lo admite —se la atiende igual—; se saltea
 *       con un log y la reserva sigue.</li>
 *   <li><b>Un dato de render no pasa el sanitizador.</b> Una sede llamada "Jardin Secreto"
 *       contiene {@code secret}, que es uno de los fragmentos que el outbox rechaza por pinta de
 *       secreto. Si se lo mandara tal cual, {@code enqueue} lanzaria <b>dentro</b> de la
 *       transaccion, la dejaria marcada para rollback —atrapar la excepcion no la des-marca— y la
 *       reserva entera moriria por el nombre de la sede. Por eso cada dato se valida ANTES, con
 *       {@link NotificationOutbox#validarDatosDeRender}, que no participa de la transaccion; el que
 *       no pasa se omite y el template cae a su texto neutro.</li>
 * </ul>
 *
 * <h2>Idempotencia (RN-M26-003)</h2>
 *
 * <p>La clave sale del hecho de negocio, nunca del reloj: un turno se reserva una vez y se cancela
 * una vez, asi que alcanza con su id. Una reprogramacion puede repetirse sobre el mismo turno —y
 * volver a un horario anterior—, asi que lleva ademas la version que la reprogramacion dejo: es
 * unica por cambio y estable ante un reintento de la misma transaccion.
 */
@Component
public class AvisosDeTurno {

	private static final Logger log = LoggerFactory.getLogger(AvisosDeTurno.class);

	/** Formato legible para quien recibe el mail. La zona la pone la sede, no el servidor. */
	private static final DateTimeFormatter FORMATO =
			DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm");

	private final NotificationOutbox outbox;
	private final ContactoDirectory contactos;

	public AvisosDeTurno(NotificationOutbox outbox, ContactoDirectory contactos) {
		this.outbox = outbox;
		this.contactos = contactos;
	}

	/** RF-M26-002. */
	public void avisarReserva(Turno turno, ConsultorioSnapshot sede, String servicioNombre) {
		encolar(NotificationType.TURNO_RESERVADO,
				"turno-reservado:" + turno.getId(),
				turno, sede, servicioNombre, null);
	}

	/** RF-M26-003, cancelacion. */
	public void avisarCancelacion(Turno turno, ConsultorioSnapshot sede, String servicioNombre) {
		encolar(NotificationType.TURNO_CANCELADO,
				"turno-cancelado:" + turno.getId(),
				turno, sede, servicioNombre, null);
	}

	/**
	 * RF-M26-003, reprogramacion.
	 *
	 * @param turno         el turno YA movido y flusheado: su version es la que deja este cambio
	 * @param inicioAnterior de donde vino, para que el mail diga "tu turno del X paso al Y"
	 */
	public void avisarReprogramacion(
			Turno turno, ConsultorioSnapshot sede, String servicioNombre, Instant inicioAnterior) {
		encolar(NotificationType.TURNO_REPROGRAMADO,
				"turno-reprogramado:" + turno.getId() + ":v" + turno.getVersion(),
				turno, sede, servicioNombre, inicioAnterior);
	}

	private void encolar(
			NotificationType tipo,
			String claveIdempotente,
			Turno turno,
			ConsultorioSnapshot sede,
			String servicioNombre,
			Instant inicioAnterior) {

		ContactoDePersona contacto = contactos
				.findAll(turno.getOrganizationId(), List.of(turno.getPersonaId()))
				.get(turno.getPersonaId());
		if (contacto == null || !contacto.notificable()) {
			// Ni el id de la persona ni nada que la identifique: el turno alcanza para rastrearlo.
			log.info("Sin correo para notificar {}: turnoId={}", tipo, turno.getId());
			return;
		}

		ZoneId zona = ZoneId.of(sede.timezone());
		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", contacto.nombre());
		datos.put("turnoInicio", FORMATO.format(turno.getInicio().atZone(zona)));
		if (inicioAnterior != null) {
			datos.put("turnoInicioAnterior", FORMATO.format(inicioAnterior.atZone(zona)));
		}
		datos.put("consultorioNombre", sede.name());
		datos.put("servicioNombre", servicioNombre);

		outbox.enqueue(new NotificationEnqueueCommand(
				tipo,
				contacto.email(),
				claveIdempotente,
				turno.getOrganizationId(),
				null,
				depurar(datos, tipo, turno)));
	}

	/**
	 * Deja solo los datos que el outbox va a aceptar. Ver la cabecera: un dato rechazado adentro de
	 * {@code enqueue} se lleva puesta la transaccion del negocio.
	 */
	private Map<String, String> depurar(Map<String, String> datos, NotificationType tipo, Turno turno) {
		Map<String, String> aceptados = new LinkedHashMap<>();
		datos.forEach((clave, valor) -> {
			if (valor == null || valor.isBlank()) {
				return;
			}
			try {
				outbox.validarDatosDeRender(Map.of(clave, valor));
				aceptados.put(clave, valor);
			} catch (IllegalArgumentException rechazado) {
				log.warn("Dato de render omitido en {}: clave={} turnoId={}", tipo, clave, turno.getId());
			}
		});
		return aceptados;
	}
}
