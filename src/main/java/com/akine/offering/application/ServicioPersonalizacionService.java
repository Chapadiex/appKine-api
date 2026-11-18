package com.akine.offering.application;

import com.akine.offering.domain.PermissionCodes;
import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.exception.ServicioCodigoTakenException;
import com.akine.offering.domain.exception.ServicioNombreTakenException;
import com.akine.offering.domain.exception.ServicioNotAccessibleException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
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
import java.util.Locale;
import java.util.Map;

/**
 * Personalizacion de un servicio del catalogo de AKINE por una organizacion.
 *
 * <p>Un servicio GLOBAL lo comparten todas las organizaciones: editarlo desde un centro le
 * cambiaria el dato a los demas. Lo que el administrador de la organizacion "edita" es una COPIA
 * de su organizacion —vale para todas sus sedes, no para una— con los cambios aplicados, y las
 * ofertas de la organizacion que prestaban el global pasan a prestar la copia. El global y las
 * demas organizaciones no se enteran.
 *
 * <p>Vive aparte de {@link ServicioService} a proposito: ese servicio no puede alcanzar las
 * ofertas, y esa imposibilidad es la garantia de que la baja de un servicio no cascadea
 * ({@code ServicioServiceTest}). Aca las ofertas se mueven, pero solo las de la organizacion del
 * contexto y solo para apuntar a la copia.
 */
@Service
public class ServicioPersonalizacionService {

	private static final Logger log = LoggerFactory.getLogger(ServicioPersonalizacionService.class);

	private final ServicioRepositoryPort servicios;
	private final OfertaRepositoryPort ofertas;
	private final AuditTrail auditTrail;
	private final PermissionGuard permissionGuard;

	public ServicioPersonalizacionService(
			ServicioRepositoryPort servicios,
			OfertaRepositoryPort ofertas,
			AuditTrail auditTrail,
			PermissionGuard permissionGuard) {
		this.servicios = servicios;
		this.ofertas = ofertas;
		this.auditTrail = auditTrail;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * Una sola personalizacion vigente por organizacion y servicio: la segunda, o dos a la vez,
	 * reciben {@code concurrent-modification} porque la lista que las origino ya estaba vieja.
	 */
	@Transactional
	public ServicioView personalizar(
			OperatingActor actor, long servicioId, ServicioEdicionCommand command) {
		if (actor.platformAdmin()) {
			throw new AccessDeniedException(
					"La plataforma edita el servicio global directamente: no se personaliza");
		}
		long organizationId = exigirGestionDeLaOrganizacion(actor);
		Servicio global = servicios.findVisible(servicioId, ServicioService.owners(actor))
				.orElseThrow(() -> new ServicioNotAccessibleException(servicioId));
		if (!global.isGlobal()) {
			throw new IllegalArgumentException(
					"Solo se personaliza un servicio del catalogo de AKINE: uno propio se edita");
		}
		ServicioService.exigirOperable(global, "personalizar");
		ServicioService.exigirVersion(global, command.expectedVersion());
		if (servicios.findPersonalizacionVigente(organizationId, global.getId()).isPresent()) {
			throw yaPersonalizado();
		}

		String nombre = command.nombre() == null
				? null
				: ServicioService.normalizar(command.nombre(), "El nombre del servicio es obligatorio");
		Map<String, String> detalles = ServicioService.cambios(global, nombre, command);
		Servicio copia = Servicio.personalizacionDe(global, organizationId);
		copia.updateDatos(
				nombre,
				command.descripcion(),
				command.naturaleza(),
				command.modalidadDefault(),
				command.requiereCasoClinicoDefault(),
				command.generaRegistroClinicoDefault());

		Servicio creada;
		try {
			creada = servicios.saveAndFlush(copia);
		} catch (DataIntegrityViolationException choque) {
			String causa = String.valueOf(choque.getMostSpecificCause().getMessage())
					.toLowerCase(Locale.ROOT);
			if (causa.contains("personalizacion")) {
				throw yaPersonalizado();
			}
			if (ServicioService.esConflictoDeNombre(choque)) {
				throw new ServicioNombreTakenException(copia.getNombre());
			}
			throw new ServicioCodigoTakenException(copia.getCodigo());
		}

		int reapuntadas = ofertas.reapuntarServicio(organizationId, global.getId(), creada.getId());

		detalles.put("personalizaServicioId", String.valueOf(global.getId()));
		detalles.put("codigo", creada.getCodigo());
		detalles.put("ofertasReapuntadas", String.valueOf(reapuntadas));
		auditTrail.record(new AuditEntry(
				organizationId,
				null,
				actor.accountId(),
				AuditEvents.SERVICIO_PERSONALIZED,
				AuditEvents.ENTITY_SERVICIO,
				creada.getId(),
				null,
				"ACTIVO",
				detalles,
				null,
				AuditEvents.correlationId(),
				Instant.now()));
		log.info("Servicio personalizado: globalId={} copiaId={} organizationId={} ofertas={}",
				global.getId(), creada.getId(), organizationId, reapuntadas);
		return ServicioView.de(creada);
	}

	/** {@code catalogo:manage} en la organizacion del contexto: la copia es de la organizacion. */
	private long exigirGestionDeLaOrganizacion(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CATALOGO_MANAGE,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));
		return organizationId;
	}

	private static OptimisticLockingFailureException yaPersonalizado() {
		return new OptimisticLockingFailureException(
				"El servicio ya fue personalizado para esta organizacion");
	}
}
