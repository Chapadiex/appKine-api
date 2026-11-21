package com.akine.organization.application;

import com.akine.organization.domain.SolicitudDePrueba;
import com.akine.organization.domain.exception.SolicitudDePruebaNotFoundException;
import com.akine.organization.domain.exception.TerminosDelPedidoException;
import com.akine.organization.domain.port.SolicitudDePruebaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.terminos.TerminosVigentes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.akine.organization.spi.SolicitudDePruebaRecibida;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pedidos de prueba gratis de la landing (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md §5).
 *
 * <p>La recepcion es publica: no revela nada (siempre la misma respuesta) e ignora en silencio
 * lo que llena el campo trampa. La plataforma los ve en su bandeja, los descarta con motivo o
 * los aprueba al dar de alta el centro.
 */
@Service
public class SolicitudDePruebaService {

	private static final Logger log = LoggerFactory.getLogger(SolicitudDePruebaService.class);

	private final SolicitudDePruebaRepositoryPort repository;
	private final AuditTrail auditTrail;
	private final ApplicationEventPublisher eventos;
	private final TerminosVigentes terminos;

	public SolicitudDePruebaService(SolicitudDePruebaRepositoryPort repository, AuditTrail auditTrail,
			ApplicationEventPublisher eventos, TerminosVigentes terminos) {
		this.repository = repository;
		this.auditTrail = auditTrail;
		this.eventos = eventos;
		this.terminos = terminos;
	}

	/** Datos del formulario de la landing. {@code trampa} es el campo oculto para bots. */
	public record Pedido(String nombre, String apellido, String email, String telefono,
			String nombreCentro, String ciudad, boolean kinesiologia, boolean actividades,
			SolicitudDePrueba.Sedes sedes, boolean interesaIa, String comentario, String trampa,
			Boolean aceptaTerminos, String versionTerminos) {
	}

	/**
	 * Guarda el pedido. Idempotente por clave: el reintento no duplica. Un pedido con el campo
	 * trampa lleno no se guarda, y quien lo mando recibe la misma respuesta.
	 */
	@Transactional
	public void recibir(Pedido pedido, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
			throw new IllegalArgumentException("Falta la clave de idempotencia");
		}
		if (pedido.trampa() != null && !pedido.trampa().isBlank()) {
			log.info("Pedido de prueba descartado por el campo trampa");
			return;
		}
		String versionAceptada = exigirTerminos(pedido);
		if (repository.findByIdempotencyKey(idempotencyKey).isPresent()) {
			return;
		}
		try {
			SolicitudDePrueba nueva = new SolicitudDePrueba(
					limpio(pedido.nombre()), limpio(pedido.apellido()),
					pedido.email().strip().toLowerCase(), limpio(pedido.telefono()),
					limpio(pedido.nombreCentro()), limpio(pedido.ciudad()),
					pedido.kinesiologia(), pedido.actividades(), pedido.sedes(),
					pedido.interesaIa(), limpio(pedido.comentario()), idempotencyKey);
			if (versionAceptada != null) {
				nueva.aceptarTerminos(versionAceptada, Instant.now());
			}
			SolicitudDePrueba guardada = repository.saveAndFlush(nueva);
			// La plataforma se entera por correo (lo encola identity, en esta transaccion).
			eventos.publishEvent(new SolicitudDePruebaRecibida(guardada.getId(), guardada.getNombreCentro(),
					servicios(guardada), sedes(guardada.getSedes())));
		} catch (DataIntegrityViolationException carrera) {
			// uk_solicitud_de_prueba_clave: el mismo pedido entro por otro hilo. Ya esta guardado.
			log.info("Pedido de prueba repetido en carrera: se conserva el primero");
		}
	}

	/**
	 * La version aceptada, o {@code null} si el despliegue no exige terminos (solo tests). Se mira
	 * despues del campo trampa, para que un bot siga recibiendo la misma respuesta.
	 */
	private String exigirTerminos(Pedido pedido) {
		String vigente = terminos.version().orElse(null);
		if (vigente == null) {
			return null;
		}
		if (!Boolean.TRUE.equals(pedido.aceptaTerminos())) {
			throw new TerminosDelPedidoException("aceptaTerminos",
					"Aceptá los términos y condiciones para pedir la prueba");
		}
		if (pedido.versionTerminos() == null || !vigente.equals(pedido.versionTerminos().strip())) {
			throw new TerminosDelPedidoException("versionTerminos",
					"Los términos cambiaron (versión " + vigente + "). Volvé a leerlos y aceptá la versión actual");
		}
		return vigente;
	}

	@Transactional(readOnly = true)
	public Page<SolicitudDePruebaView> listar(SolicitudDePrueba.Estado estado, String q, Pageable pageable) {
		return repository.buscar(estado, q, pageable).map(SolicitudDePruebaView::de);
	}

	@Transactional(readOnly = true)
	public long pendientes() {
		return repository.countByEstado(SolicitudDePrueba.Estado.PENDIENTE);
	}

	@Transactional(readOnly = true)
	public SolicitudDePruebaView ver(long id) {
		return SolicitudDePruebaView.de(cargar(id));
	}

	private SolicitudDePrueba cargar(long id) {
		return repository.findById(id).orElseThrow(() -> new SolicitudDePruebaNotFoundException(id));
	}

	@Transactional
	public SolicitudDePruebaView descartar(long id, String motivo, long versionEsperada, long actorAccountId) {
		SolicitudDePrueba solicitud = exigirPendiente(id);
		if (solicitud.getVersion() != versionEsperada) {
			throw new OptimisticLockingFailureException("La solicitud cambio mientras la mirabas");
		}
		Instant ahora = Instant.now();
		solicitud.descartar(motivo, actorAccountId, ahora);
		SolicitudDePrueba guardada = repository.saveAndFlush(solicitud);
		auditar(guardada, actorAccountId, ahora);
		return SolicitudDePruebaView.de(guardada);
	}

	/** La aprueba el alta del centro, en su misma transaccion. */
	@Transactional
	void aprobar(long id, long organizationId, long actorAccountId) {
		SolicitudDePrueba solicitud = exigirPendiente(id);
		Instant ahora = Instant.now();
		solicitud.aprobar(organizationId, actorAccountId, ahora);
		auditar(repository.saveAndFlush(solicitud), actorAccountId, ahora);
	}

	/** 404 si no existe; 409 si ya la resolvio otro. */
	@Transactional(readOnly = true)
	SolicitudDePrueba exigirPendiente(long id) {
		SolicitudDePrueba solicitud = cargar(id);
		if (solicitud.getEstado() != SolicitudDePrueba.Estado.PENDIENTE) {
			throw new OptimisticLockingFailureException("La solicitud ya fue resuelta");
		}
		return solicitud;
	}

	private void auditar(SolicitudDePrueba solicitud, long actorAccountId, Instant ahora) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("centro", solicitud.getNombreCentro());
		if (solicitud.getMotivoDescarte() != null) {
			detalles.put("motivo", solicitud.getMotivoDescarte());
		}
		auditTrail.record(new AuditEntry(
				solicitud.getOrganizationId(), null, actorAccountId, AuditEvents.SOLICITUD_PRUEBA_RESUELTA,
				AuditEvents.ENTITY_SOLICITUD_PRUEBA, solicitud.getId(),
				SolicitudDePrueba.Estado.PENDIENTE.name(), solicitud.getEstado().name(),
				detalles, null, AuditEvents.correlationId(), ahora));
	}

	private static String servicios(SolicitudDePrueba s) {
		if (s.isKinesiologia() && s.isActividades()) {
			return "Kinesiologia y Actividades";
		}
		return s.isKinesiologia() ? "Kinesiologia" : "Actividades";
	}

	private static String sedes(SolicitudDePrueba.Sedes sedes) {
		return switch (sedes) {
			case UNA -> "Una";
			case HASTA_TRES -> "Hasta 3";
			case MAS_DE_TRES -> "Mas de 3";
		};
	}

	private static String limpio(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}
}
