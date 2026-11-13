package com.akine.scheduling.application;

import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
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
import java.util.Objects;

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
 *
 * <h2>Una operacion, un aviso: "tu turno cambio"</h2>
 *
 * <p>Reprogramar y cambiar de sede le dicen al paciente lo mismo —su turno ya no es el que era— y
 * los dos pasan por {@link #avisarModificacion}, el unico lugar que arma ese aviso. El cambio de
 * sede cancela un turno y reserva otro, pero el paciente no se entera de eso: recibe UN aviso
 * {@code TURNO_REPROGRAMADO} con lo que cambio (sede, fecha y hora, otro profesional, otro box),
 * nunca una cancelacion mas una reserva. El profesional y el box se anuncian sin nombre, por la
 * misma regla de PHI minima de arriba.
 */
@Component
public class AvisosDeTurno {

	private static final Logger log = LoggerFactory.getLogger(AvisosDeTurno.class);

	/** Formato legible para quien recibe el mail. La zona la pone la sede, no el servidor. */
	private static final DateTimeFormatter FORMATO =
			DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm");

	/** Valor de las marcas {@code cambioProfesional} y {@code cambioEspacio}. */
	private static final String SI = "si";

	private final NotificationOutbox outbox;
	private final ContactoDirectory contactos;
	private final ConsultorioMembershipDirectory memberships;

	public AvisosDeTurno(
			NotificationOutbox outbox, ContactoDirectory contactos, ConsultorioMembershipDirectory memberships) {
		this.outbox = outbox;
		this.contactos = contactos;
		this.memberships = memberships;
	}

	/**
	 * Como era el turno antes del cambio. En una reprogramacion es el mismo turno; en un cambio de sede
	 * es el original, ya cancelado.
	 *
	 * @param turnoId                 el turno de antes (en el cambio de sede, el original)
	 * @param sede                    su sede
	 * @param inicio                  su inicio
	 * @param profesionalMembershipId su profesional, o {@code null}
	 * @param espacioId               su box, o {@code null}
	 */
	public record Antes(
			long turnoId, ConsultorioSnapshot sede, Instant inicio, Long profesionalMembershipId, Long espacioId) {

		/** La foto de un turno antes de tocarlo. */
		public static Antes de(Turno turno, ConsultorioSnapshot sede) {
			return new Antes(turno.getId(), sede, turno.getInicio(),
					turno.getProfesionalMembershipId(), turno.getEspacioId());
		}
	}

	/** RF-M26-002. */
	public void avisarReserva(Turno turno, ConsultorioSnapshot sede, String servicioNombre) {
		avisarReserva(turno, sede, servicioNombre, contactoDe(turno.getOrganizationId(), turno.getPersonaId()));
	}

	/**
	 * RF-M26-002, con el contacto ya leido por {@link #contactoDe}.
	 *
	 * <p>Existe para la reserva, que lee el contacto ANTES de tomar el lock de {@code agenda_sede}:
	 * es una lectura de {@code person} que ninguna escritura de agenda modifica, y bajo el lock era
	 * una sentencia mas con las demas reservas de la sede esperando (docs/carga.md §10).
	 *
	 * @param contacto el de la persona del turno, o {@code null} si no tiene
	 */
	public void avisarReserva(
			Turno turno, ConsultorioSnapshot sede, String servicioNombre, ContactoDePersona contacto) {
		encolar(NotificationType.TURNO_RESERVADO,
				"turno-reservado:" + turno.getId(),
				turno, sede, servicioNombre, Map.of(), contacto);
	}

	/** El contacto de una persona, o {@code null} si no tiene. */
	public ContactoDePersona contactoDe(long organizationId, long personaId) {
		return contactos.findAll(organizationId, List.of(personaId)).get(personaId);
	}

	/** RF-M26-003, cancelacion. */
	public void avisarCancelacion(Turno turno, ConsultorioSnapshot sede, String servicioNombre) {
		encolar(NotificationType.TURNO_CANCELADO,
				"turno-cancelado:" + turno.getId(),
				turno, sede, servicioNombre, Map.of(),
				contactoDe(turno.getOrganizationId(), turno.getPersonaId()));
	}

	/**
	 * RF-M26-003: el turno cambio. El UNICO aviso de una reprogramacion y de un cambio de sede.
	 *
	 * <p>Dice lo que cambio, de antes a ahora: la sede, la fecha y hora, y si lo atiende otro
	 * profesional o en otro box (sin nombrarlos). Lo que no cambio no se menciona. El profesional se
	 * compara por cuenta, no por membership: en otra sede la misma persona tiene otro id. El box solo
	 * se menciona dentro de la misma sede: en otra sede es otro box siempre, y la sede ya lo dice.
	 *
	 * <p>Clave idempotente: reprogramar mueve el mismo turno, asi que es
	 * {@code turno-reprogramado:<id>:v<version>} (la version que deja el cambio, unica por cambio).
	 * El cambio de sede crea un turno, asi que es {@code turno-trasladado:<nuevoId>:desde:<origenId>}:
	 * unica por operacion y con los dos ids para rastrear el aviso. {@code VigenciaDeAvisoDeTurno}
	 * lee el segundo segmento —el turno que queda— para no mandarlo si ese turno se cancelo antes.
	 *
	 * @param resultante el turno como queda, YA flusheado: su version es la que deja este cambio
	 * @param sede       la sede del turno como queda
	 * @param antes      como era antes del cambio
	 */
	public void avisarModificacion(
			Turno resultante, ConsultorioSnapshot sede, String servicioNombre, Antes antes) {

		boolean otroTurno = antes.turnoId() != resultante.getId();
		String clave = otroTurno
				? "turno-trasladado:" + resultante.getId() + ":desde:" + antes.turnoId()
				: "turno-reprogramado:" + resultante.getId() + ":v" + resultante.getVersion();

		boolean otraSede = antes.sede().id() != sede.id();
		Map<String, String> cambios = new LinkedHashMap<>();
		if (otraSede) {
			cambios.put("consultorioNombreAnterior", antes.sede().name());
		}
		if (!antes.inicio().equals(resultante.getInicio())) {
			cambios.put("turnoInicioAnterior",
					FORMATO.format(antes.inicio().atZone(ZoneId.of(antes.sede().timezone()))));
		}
		if (!mismaPersona(resultante.getOrganizationId(),
				antes.profesionalMembershipId(), resultante.getProfesionalMembershipId())) {
			cambios.put("cambioProfesional", SI);
		}
		if (!otraSede && !Objects.equals(antes.espacioId(), resultante.getEspacioId())) {
			cambios.put("cambioEspacio", SI);
		}
		encolar(NotificationType.TURNO_REPROGRAMADO, clave, resultante, sede, servicioNombre, cambios,
				contactoDe(resultante.getOrganizationId(), resultante.getPersonaId()));
	}

	/** Si las dos memberships son de la misma cuenta. Dos {@code null} son "sin profesional": iguales. */
	private boolean mismaPersona(long organizationId, Long antes, Long ahora) {
		if (Objects.equals(antes, ahora)) {
			return true;
		}
		if (antes == null || ahora == null) {
			return false;
		}
		Long cuentaAntes = memberships.find(organizationId, antes)
				.map(ConsultorioMembershipSnapshot::accountId).orElse(null);
		return cuentaAntes != null && cuentaAntes.equals(memberships.find(organizationId, ahora)
				.map(ConsultorioMembershipSnapshot::accountId).orElse(null));
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private void encolar(
			NotificationType tipo,
			String claveIdempotente,
			Turno turno,
			ConsultorioSnapshot sede,
			String servicioNombre,
			Map<String, String> cambios,
			ContactoDePersona contacto) {

		if (contacto == null || !contacto.notificable()) {
			// Ni el id de la persona ni nada que la identifique: el turno alcanza para rastrearlo.
			log.info("Sin correo para notificar {}: turnoId={}", tipo, turno.getId());
			return;
		}

		ZoneId zona = ZoneId.of(sede.timezone());
		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", contacto.nombre());
		datos.put("turnoInicio", FORMATO.format(turno.getInicio().atZone(zona)));
		datos.put("consultorioNombre", sede.name());
		datos.put("servicioNombre", servicioNombre);
		// V140: la direccion y las coordenadas de la sede, SOLO si la sede las publica. El snapshot ya
		// viene filtrado por esa decision (null = no publicar), y el enlace "como llegar" lo arma el
		// template: una URL aca la rechazaria el outbox, y esa regla sigue valiendo.
		if (sede.direccionPublica() != null) {
			datos.put("consultorioDireccion", sede.direccionPublica());
			if (sede.latitud() != null && sede.longitud() != null) {
				datos.put("consultorioLatitud", sede.latitud().toPlainString());
				datos.put("consultorioLongitud", sede.longitud().toPlainString());
			}
		}
		datos.putAll(cambios);

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
