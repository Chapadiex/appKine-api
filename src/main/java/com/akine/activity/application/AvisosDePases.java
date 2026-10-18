package com.akine.activity.application;

import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.port.ActivityRepositoryPorts.CandidatoAAvisar;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los avisos de pases y abonos (RF-M26-008): vencimiento proximo y saldo reducido.
 *
 * <h2>Por el outbox, dentro de la transaccion que marca el pase</h2>
 *
 * <p>El aviso no se manda desde aca: se <b>encola</b> en {@code notification} con propagacion
 * {@code MANDATORY}, en la misma transaccion que marca el pase como avisado. Si la marca no
 * comitea, el aviso no existe; si comitea, el aviso existe y lo entrega el worker del outbox, que
 * reintenta si el relay esta caido. El fallo de una entrega no deshace nada (RN-M26-001).
 *
 * <h2>Un aviso por pase, y lo garantiza el motor</h2>
 *
 * <p>La marca ({@code aviso_vencimiento_en}, {@code aviso_saldo_bajo_en}, V103) la pone un
 * {@code UPDATE ... WHERE marca IS NULL}: dos instancias del job, o un tick que se repite, pelean
 * por la fila y solo una ve una fila afectada. La clave idempotente del outbox
 * ({@code pase-por-vencer:<id>}) es el cinturon. El {@code UPDATE} sube {@code version}, como todo
 * UPDATE nativo de {@code pase_servicio}.
 *
 * <h2>Avisar no toca el producto (CA-M26-008-06)</h2>
 *
 * <p>Ni saldo, ni estado, ni vigencia, ni la renovacion: el aviso informa. Si el abono tiene la
 * renovacion automatica prendida, el texto lo dice; renovarlo es otro job.
 *
 * <h2>Y a quien no tiene correo no se le avisa, sin fallar</h2>
 *
 * <p>Como en los avisos de clase: el padron admite personas sin email. El pase queda marcado igual
 * —el aviso esta resuelto— para no volver a mirarlo cada hora; si despues carga el correo, el aviso
 * de este pase ya paso.
 */
@Component
public class AvisosDePases {

	private static final Logger log = LoggerFactory.getLogger(AvisosDePases.class);

	private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final PaseServicioRepositoryPort pases;
	private final ConsultorioDirectory consultorios;
	private final ContactoDirectory contactos;
	private final NotificationOutbox outbox;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final Clock clock;

	@Autowired
	public AvisosDePases(
			PaseServicioRepositoryPort pases,
			ConsultorioDirectory consultorios,
			ContactoDirectory contactos,
			NotificationOutbox outbox,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {

		this(pases, consultorios, contactos, outbox, auditTrail, transactionManager, Clock.systemUTC());
	}

	AvisosDePases(
			PaseServicioRepositoryPort pases,
			ConsultorioDirectory consultorios,
			ContactoDirectory contactos,
			NotificationOutbox outbox,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager,
			Clock clock) {

		this.pases = pases;
		this.consultorios = consultorios;
		this.contactos = contactos;
		this.outbox = outbox;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.transaccion.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.clock = clock;
	}

	/**
	 * Avisa a los titulares de pases {@code ACTIVO} con saldo cuya vigencia termina dentro de
	 * {@code diasAntes} dias (hoy inclusive, en la zona de cada sede). <b>No es transaccional</b>:
	 * cada pase abre y cierra la suya.
	 *
	 * @return la pasada: procesados son los avisos que encolo
	 */
	public PasadaDeJob avisarVencimientos(int diasAntes, int maximo) {
		Instant ahora = clock.instant();
		LocalDate hoyUtc = LocalDate.ofInstant(ahora, ZoneOffset.UTC);
		// Un dia de margen a cada lado cubre todas las zonas; el filtro exacto es por sede, abajo.
		List<CandidatoAAvisar> candidatos =
				pases.candidatosAvisoDeVencimiento(hoyUtc.minusDays(1), hoyUtc.plusDays(diasAntes + 1L), maximo);
		PasadaDeJob.Cuenta cuenta = new PasadaDeJob.Cuenta();
		for (CandidatoAAvisar candidato : candidatos) {
			try {
				ConsultorioSnapshot sede = sede(candidato);
				LocalDate hoy = LocalDate.ofInstant(ahora, ZonaDeSede.de(sede));
				if (candidato.vigenciaHasta().isBefore(hoy) || candidato.vigenciaHasta().isAfter(hoy.plusDays(diasAntes))) {
					cuenta.contar(false);
					continue;
				}
				cuenta.contar(Boolean.TRUE.equals(transaccion.execute(estado -> avisar(
						candidato, sede, Tipo.VENCIMIENTO, 0, ahora))));
			} catch (RuntimeException e) {
				cuenta.fallo();
				logFallo("No se pudo avisar el vencimiento del pase", candidato, e);
			}
		}
		return cuenta.resumen();
	}

	/**
	 * Avisa a los titulares de pases {@code ACTIVO} que ya usaron creditos y tienen entre 1 y
	 * {@code umbral}. Uno por pase: si una devolucion le sube el saldo y lo vuelve a gastar, no hay
	 * un segundo aviso.
	 *
	 * @return la pasada: procesados son los avisos que encolo
	 */
	public PasadaDeJob avisarSaldosBajos(int umbral, int maximo) {
		if (umbral <= 0) {
			return PasadaDeJob.VACIA;
		}
		Instant ahora = clock.instant();
		PasadaDeJob.Cuenta cuenta = new PasadaDeJob.Cuenta();
		for (CandidatoAAvisar candidato : pases.candidatosAvisoDeSaldoBajo(umbral, maximo)) {
			try {
				ConsultorioSnapshot sede = sede(candidato);
				cuenta.contar(Boolean.TRUE.equals(transaccion.execute(estado -> avisar(
						candidato, sede, Tipo.SALDO_BAJO, umbral, ahora))));
			} catch (RuntimeException e) {
				cuenta.fallo();
				logFallo("No se pudo avisar el saldo bajo del pase", candidato, e);
			}
		}
		return cuenta.resumen();
	}

	private static void logFallo(String mensaje, CandidatoAAvisar candidato, RuntimeException e) {
		log.atError().addKeyValue("paseId", candidato.paseId())
				.addKeyValue("error", e.getClass().getSimpleName())
				.setCause(e)
				.log(mensaje + " {}", candidato.paseId());
	}

	// =================================================================================

	private enum Tipo {
		VENCIMIENTO(NotificationType.PASE_POR_VENCER, "pase-por-vencer:", "PASE_AVISO_DE_VENCIMIENTO"),
		SALDO_BAJO(NotificationType.PASE_SALDO_BAJO, "pase-saldo-bajo:", "PASE_AVISO_DE_SALDO_BAJO");

		final NotificationType notificacion;
		final String prefijoClave;
		final String evento;

		Tipo(NotificationType notificacion, String prefijoClave, String evento) {
			this.notificacion = notificacion;
			this.prefijoClave = prefijoClave;
			this.evento = evento;
		}
	}

	/** @return {@code true} si encolo un aviso */
	private boolean avisar(
			CandidatoAAvisar candidato, ConsultorioSnapshot sede, Tipo tipo, int umbral, Instant ahora) {

		long organizationId = candidato.organizationId();
		long paseId = candidato.paseId();
		int marcado = tipo == Tipo.VENCIMIENTO
				? pases.marcarAvisoDeVencimiento(organizationId, paseId, ahora)
				: pases.marcarAvisoDeSaldoBajo(organizationId, paseId, umbral, ahora);
		if (marcado != 1) {
			// Otra instancia ya aviso, o el pase cambio entre la lista y la marca.
			return false;
		}
		PaseServicio pase = pases.findByIdInScope(organizationId, candidato.consultorioId(), paseId)
				.orElseThrow(() -> new IllegalStateException("El pase " + paseId + " no esta en su sede"));
		ContactoDePersona contacto = contactos.findAll(organizationId, List.of(pase.getPersonaId()))
				.get(pase.getPersonaId());
		boolean renovado = tipo == Tipo.VENCIMIENTO && pases.renovacionDe(organizationId, paseId).isPresent();

		boolean encolado = false;
		if (renovado) {
			// El periodo siguiente ya esta vendido: "tu abono vence" no le dice nada util.
			log.info("Sin aviso de vencimiento: el abono {} ya se renovo", paseId);
		} else if (contacto == null || !contacto.notificable()) {
			log.info("Sin correo para avisar {}: paseId={}", tipo.notificacion, paseId);
		} else {
			outbox.enqueue(new NotificationEnqueueCommand(
					tipo.notificacion, contacto.email(), tipo.prefijoClave + paseId, organizationId, null,
					depurar(tipo, paseId, datos(pase, contacto, sede))));
			encolado = true;
		}
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("encolado", String.valueOf(encolado));
		detalles.put("creditosDisponibles", String.valueOf(pase.getCreditosDisponibles()));
		detalles.put("vigenciaHasta", pase.getVigenciaHasta().toString());
		auditTrail.record(new AuditEntry(
				organizationId, candidato.consultorioId(), null, tipo.evento, "PASE_SERVICIO", paseId,
				null, null, detalles, null, null, ahora));
		return encolado;
	}

	private static Map<String, String> datos(
			PaseServicio pase, ContactoDePersona contacto, ConsultorioSnapshot sede) {

		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", contacto.nombre() == null ? "" : contacto.nombre());
		datos.put("productoNombre", pase.getSnapshotNombre());
		datos.put("paseVencimiento", FORMATO.format(pase.getVigenciaHasta()));
		datos.put("creditosDisponibles", String.valueOf(pase.getCreditosDisponibles()));
		datos.put("consultorioNombre", sede.name() == null ? "" : sede.name());
		if (pase.isRenovacionAutomatica()) {
			datos.put("renovacionAutomatica", "si");
		}
		return datos;
	}

	/**
	 * Deja solo los datos que el outbox va a aceptar: un producto "Pack secreto" no puede hacer
	 * fallar la transaccion que marca el pase (el defecto de {@code AvisosDeClase} en E-5). Validar
	 * cada dato con {@link NotificationOutbox#validarDatosDeRender} permite omitir solo ese, y el
	 * template cae a su texto neutro.
	 */
	private Map<String, String> depurar(Tipo tipo, long paseId, Map<String, String> datos) {
		Map<String, String> aceptados = new LinkedHashMap<>();
		datos.forEach((clave, valor) -> {
			try {
				outbox.validarDatosDeRender(Map.of(clave, valor));
				aceptados.put(clave, valor);
			} catch (IllegalArgumentException rechazado) {
				log.warn("Dato de render omitido en {}: clave={} paseId={}", tipo.notificacion, clave, paseId);
			}
		});
		return aceptados;
	}

	private ConsultorioSnapshot sede(CandidatoAAvisar candidato) {
		return consultorios.find(candidato.organizationId(), candidato.consultorioId())
				.orElseThrow(() -> new IllegalStateException(
						"La sede " + candidato.consultorioId() + " del pase " + candidato.paseId() + " no existe"));
	}
}
