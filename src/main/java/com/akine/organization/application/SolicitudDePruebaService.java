package com.akine.organization.application;

import com.akine.organization.domain.SolicitudDePrueba;
import com.akine.organization.domain.exception.SolicitudDePruebaNotFoundException;
import com.akine.organization.domain.port.SolicitudDePruebaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	public SolicitudDePruebaService(SolicitudDePruebaRepositoryPort repository, AuditTrail auditTrail) {
		this.repository = repository;
		this.auditTrail = auditTrail;
	}

	/** Datos del formulario de la landing. {@code trampa} es el campo oculto para bots. */
	public record Pedido(String nombre, String apellido, String email, String telefono,
			String nombreCentro, String ciudad, boolean kinesiologia, boolean actividades,
			SolicitudDePrueba.Sedes sedes, boolean interesaIa, String comentario, String trampa) {
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
		if (repository.findByIdempotencyKey(idempotencyKey).isPresent()) {
			return;
		}
		try {
			repository.saveAndFlush(new SolicitudDePrueba(
					limpio(pedido.nombre()), limpio(pedido.apellido()),
					pedido.email().strip().toLowerCase(), limpio(pedido.telefono()),
					limpio(pedido.nombreCentro()), limpio(pedido.ciudad()),
					pedido.kinesiologia(), pedido.actividades(), pedido.sedes(),
					pedido.interesaIa(), limpio(pedido.comentario()), idempotencyKey));
		} catch (DataIntegrityViolationException carrera) {
			// uk_solicitud_de_prueba_clave: el mismo pedido entro por otro hilo. Ya esta guardado.
			log.info("Pedido de prueba repetido en carrera: se conserva el primero");
		}
	}

	@Transactional(readOnly = true)
	public Page<SolicitudDePrueba> listar(SolicitudDePrueba.Estado estado, String q, Pageable pageable) {
		return repository.buscar(estado, q, pageable);
	}

	@Transactional(readOnly = true)
	public long pendientes() {
		return repository.countByEstado(SolicitudDePrueba.Estado.PENDIENTE);
	}

	@Transactional(readOnly = true)
	public SolicitudDePrueba ver(long id) {
		return repository.findById(id).orElseThrow(() -> new SolicitudDePruebaNotFoundException(id));
	}

	@Transactional
	public SolicitudDePrueba descartar(long id, String motivo, long versionEsperada, long actorAccountId) {
		SolicitudDePrueba solicitud = exigirPendiente(id);
		if (solicitud.getVersion() != versionEsperada) {
			throw new OptimisticLockingFailureException("La solicitud cambio mientras la mirabas");
		}
		Instant ahora = Instant.now();
		solicitud.descartar(motivo, actorAccountId, ahora);
		SolicitudDePrueba guardada = repository.saveAndFlush(solicitud);
		auditar(guardada, actorAccountId, ahora);
		return guardada;
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
		SolicitudDePrueba solicitud = ver(id);
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

	private static String limpio(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}
}
