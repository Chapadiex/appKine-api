package com.akine.encounter.application;

import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.PoliticaDeEnmienda;
import com.akine.encounter.domain.port.PoliticaDeEnmiendaRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * La ventana para enmendar una sesion cerrada, por organizacion (U-25, DP-43).
 *
 * <p>Una sola regla: desde el cierre, {@code ventanaDias} dias; sin politica propia, 30. La lee
 * {@link SesionService} al enmendar y al armar {@code enmendableHasta}; la fija quien administra la
 * organizacion ({@code consultorio:manage} con alcance organizacion, el ORG_ADMIN).
 *
 * <p><b>Cambiar la ventana alcanza tambien a las sesiones ya cerradas</b>: la ventana no se congela
 * en cada sesion. Es la lectura literal de "30 dias configurable por organizacion" y la que deja a
 * la organizacion corregir un valor mal puesto sin una sesion "atrapada" en el viejo.
 */
@Service
public class PoliticaDeEnmiendaService {

	private static final Logger log = LoggerFactory.getLogger(PoliticaDeEnmiendaService.class);

	static final String ENTITY = "PoliticaDeEnmienda";
	static final String POLITICA_FIJADA = "POLITICA_DE_ENMIENDA_FIJADA";

	private final PoliticaDeEnmiendaRepositoryPort politicas;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public PoliticaDeEnmiendaService(
			PoliticaDeEnmiendaRepositoryPort politicas,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {
		this.politicas = politicas;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	/**
	 * La politica vigente de la organizacion del contexto.
	 *
	 * @param porDefecto {@code true} si la organizacion nunca fijo la suya: rige
	 *                   {@link PoliticaDeEnmienda#VENTANA_POR_DEFECTO} y {@code version} es nula
	 */
	public record PoliticaView(int ventanaDias, boolean porDefecto, Long version, Instant actualizadaEn) {
	}

	@Transactional(readOnly = true)
	public PoliticaView ver(OperatingActor actor) {
		long organizationId = exigirAdministracion(actor);
		return vista(politicas.findById(organizationId).orElse(null));
	}

	/**
	 * Fija la ventana de la organizacion del contexto.
	 *
	 * @param expectedVersion la version leida, o {@code null} si se leyo la politica por defecto.
	 *                        Si no coincide con la que hay, 409 {@code concurrent-modification}
	 */
	@Transactional
	public PoliticaView fijar(OperatingActor actor, int ventanaDias, Long expectedVersion) {
		long organizationId = exigirAdministracion(actor);
		PoliticaDeEnmienda.exigirEnRango(ventanaDias);

		Instant ahora = Instant.now();
		PoliticaDeEnmienda existente = politicas.findById(organizationId).orElse(null);
		if (!Objects.equals(existente == null ? null : existente.getVersion(), expectedVersion)) {
			throw new OptimisticLockingFailureException("La politica de enmienda de la organizacion "
					+ organizationId + " cambio desde que se leyo");
		}

		int anterior = existente == null ? PoliticaDeEnmienda.VENTANA_POR_DEFECTO
				: existente.getVentanaDias();
		PoliticaDeEnmienda guardada;
		try {
			if (existente == null) {
				guardada = politicas.saveAndFlush(
						new PoliticaDeEnmienda(organizationId, ventanaDias, ahora, actor.accountId()));
			} else {
				existente.fijar(ventanaDias, ahora, actor.accountId());
				guardada = politicas.saveAndFlush(existente);
			}
		} catch (DataIntegrityViolationException carrera) {
			// Dos primeras altas a la vez: la otra gano la clave primaria. Es la misma situacion que
			// una version vieja, y el cliente la resuelve igual: relee y reintenta.
			throw new OptimisticLockingFailureException(
					"La politica de enmienda de la organizacion " + organizationId
							+ " se fijo al mismo tiempo desde otro pedido", carrera);
		}

		auditTrail.record(new AuditEntry(
				organizationId, null, actor.accountId(), POLITICA_FIJADA, ENTITY, organizationId,
				"VENTANA_" + anterior, "VENTANA_" + ventanaDias,
				Map.of("ventanaDias", String.valueOf(ventanaDias)),
				null, AuditEvents.correlationId(), ahora));
		log.info("Politica de enmienda fijada: organizationId={} ventanaDias={} anterior={}",
				organizationId, ventanaDias, anterior);

		return vista(guardada);
	}

	/** Los dias de ventana que rigen hoy para la organizacion. Sin permiso: lo usa la enmienda. */
	@Transactional(readOnly = true)
	public int ventanaDe(long organizationId) {
		return politicas.findById(organizationId)
				.map(PoliticaDeEnmienda::getVentanaDias)
				.orElse(PoliticaDeEnmienda.VENTANA_POR_DEFECTO);
	}

	private static PoliticaView vista(PoliticaDeEnmienda politica) {
		if (politica == null) {
			return new PoliticaView(PoliticaDeEnmienda.VENTANA_POR_DEFECTO, true, null, null);
		}
		return new PoliticaView(politica.getVentanaDias(), false, politica.getVersion(),
				politica.getActualizadaEn());
	}

	private long exigirAdministracion(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La politica de enmienda requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.CONSULTORIO_MANAGE, organizationId, null, null,
				Instant.now()));
		return organizationId;
	}
}
