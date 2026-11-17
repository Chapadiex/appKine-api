package com.akine.clinical.application;

import com.akine.clinical.domain.Ocupacion;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.OcupacionRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Catalogo de ocupaciones de la ficha de ingreso: las globales del producto mas las que agrega
 * cada centro. Leer alcanza con pertenecer al tenant (no es dato clinico); agregar exige
 * {@code hc:declarar}, que tienen todos los roles que cargan la ficha.
 */
@Service
public class OcupacionService {

	/** Una ocupacion tal como sale del servicio: la entidad no cruza a la capa api. */
	public record OcupacionView(long id, String nombre, boolean propia) {

		static OcupacionView de(Ocupacion ocupacion) {
			return new OcupacionView(ocupacion.getId(), ocupacion.getNombre(), ocupacion.isPropia());
		}
	}

	/** Una ocupacion y si se creo en este pedido (201) o ya existia (200). */
	public record Alta(OcupacionView ocupacion, boolean creada) {
	}

	private final OcupacionRepositoryPort ocupaciones;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public OcupacionService(OcupacionRepositoryPort ocupaciones, PermissionGuard permissionGuard, AuditTrail auditTrail) {
		this.ocupaciones = ocupaciones;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	@Transactional(readOnly = true)
	public List<OcupacionView> visibles(OperatingActor actor) {
		return ocupaciones.visibles(exigirContexto(actor)).stream().map(OcupacionView::de).toList();
	}

	/**
	 * Agrega una ocupacion del centro. Si el nombre normalizado ya existe (global o del centro) se
	 * devuelve esa: "Docente" y " dócente " son la misma.
	 */
	@Transactional
	public Alta agregar(OperatingActor actor, String nombre) {
		long organizationId = exigirContexto(actor);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.HC_DECLARAR, organizationId, actor.consultorioId(), null,
				Instant.now()));

		Ocupacion nueva = new Ocupacion(organizationId, nombre);
		String normalizado = Ocupacion.normalizar(nueva.getNombre());
		var existente = ocupaciones.buscarVisiblePorNormalizado(organizationId, normalizado);
		if (existente.isPresent()) {
			return new Alta(OcupacionView.de(existente.get()), false);
		}
		Ocupacion guardada;
		try {
			guardada = ocupaciones.saveAndFlush(nueva);
		} catch (DataIntegrityViolationException choque) {
			// Dos altas iguales a la vez: la otra gano. No hay nada que reintentar.
			throw new org.springframework.dao.OptimisticLockingFailureException(
					"Otra persona agrego esa ocupacion al mismo tiempo: volve a buscarla", choque);
		}
		auditTrail.record(new AuditEntry(organizationId, actor.consultorioId(), actor.accountId(),
				AuditEvents.OCUPACION_AGREGADA, AuditEvents.ENTITY_OCUPACION, guardada.getId(), null, null,
				Map.of("nombre", guardada.getNombre()), null, AuditEvents.correlationId(), Instant.now()));
		return new Alta(OcupacionView.de(guardada), true);
	}

	private static long exigirContexto(OperatingActor actor) {
		if (actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}
}
