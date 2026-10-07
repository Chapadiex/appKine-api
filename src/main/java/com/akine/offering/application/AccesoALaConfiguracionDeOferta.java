package com.akine.offering.application;

import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.PermissionCodes;
import com.akine.offering.domain.exception.ConsultorioNoAccesibleException;
import com.akine.offering.domain.exception.ConsultorioNoOperableException;
import com.akine.offering.domain.exception.OfertaInactivaException;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;

/**
 * Quien puede leer y quien puede reconfigurar una Oferta: habilitaciones (02.07) y practicas (A-9).
 *
 * <p>No es un bean: cada servicio la arma con sus propias dependencias. Existe para que las dos
 * configuraciones de la oferta apliquen <b>exactamente</b> las mismas reglas —contexto, permiso,
 * sede operable, oferta activa, version esperada y el force-increment— sin dos copias que con el
 * tiempo diverjan.
 *
 * <h2>La version es UNA para toda la configuracion</h2>
 *
 * <p>Reemplazar practicas y reemplazar habilitaciones cargan la oferta por el mismo metodo y mueven
 * la misma {@code @Version}. Una pantalla con la version vieja de cualquiera de las dos recibe 409:
 * es la misma oferta.
 */
final class AccesoALaConfiguracionDeOferta {

	private static final Logger log = LoggerFactory.getLogger(AccesoALaConfiguracionDeOferta.class);

	private final OfertaRepositoryPort ofertas;
	private final ConsultorioDirectory consultorioDirectory;
	private final AccountContextDirectory accountContextDirectory;
	private final PermissionGuard permissionGuard;

	AccesoALaConfiguracionDeOferta(
			OfertaRepositoryPort ofertas,
			ConsultorioDirectory consultorioDirectory,
			AccountContextDirectory accountContextDirectory,
			PermissionGuard permissionGuard) {

		this.ofertas = ofertas;
		this.consultorioDirectory = consultorioDirectory;
		this.accountContextDirectory = accountContextDirectory;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * La oferta, lista para reconfigurarla, o la excepcion que corresponde.
	 *
	 * <p>Se carga por {@code findWithLockByIdAndOrganizationIdAndConsultorioId} y no por
	 * {@link #cargar}: es lo que hace avanzar la version de la oferta al cerrar la transaccion. Un
	 * reemplazo solo escribe tablas hijas, ninguna columna de {@code oferta} queda sucia, y sin ese
	 * avance la comparacion de abajo nunca fallaria para el segundo administrador que guarda.
	 */
	OfertaServicioConsultorio exigirOfertaConfigurable(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			long expectedVersion) {

		exigirContextoDeLaSede(actor, organizationId, consultorioId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));

		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		if (!sede.active()) {
			throw new ConsultorioNoOperableException(sede.id());
		}

		// Primero el lock de fila, despues la carga. Ver el javadoc de
		// OfertaRepositoryPort#bloquearParaConfigurar: sin esto, dos reemplazos simultaneos que
		// insertan filas hijas podian trabarse en un deadlock (S por la FK, X por el
		// force-increment) y el perdedor recibia un 500 en vez del 409 de version.
		long versionBloqueada = ofertas
				.bloquearParaConfigurar(ofertaId, organizationId, consultorioId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));

		OfertaServicioConsultorio oferta = ofertas
				.findWithLockByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
		if (!oferta.isOperable()) {
			// Configurar una oferta dada de baja reabriria por la ventana lo que la baja cerro.
			throw new OfertaInactivaException(ofertaId, "configurar");
		}
		if (versionBloqueada != expectedVersion || oferta.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La oferta avanzo desde la version que el cliente creia estar editando");
		}
		return oferta;
	}

	/** Pertenencia a la organizacion del contexto, y la sede del tenant. */
	/**
	 * B-3 (RF-M16-009): el mismo control que {@link #exigirOfertaConfigurable} para escribir un
	 * precio particular, con el lock exclusivo de la fila de la oferta y <b>sin</b> version ni
	 * force-increment.
	 *
	 * <p>El lock es lo que hace cumplir el no-solapamiento de precios: dos altas concurrentes de la
	 * misma oferta se serializan, y la segunda lee la fila de la primera porque el servicio corre en
	 * {@code READ_COMMITTED}. La version de la oferta no se pide porque el precio no edita la oferta
	 * —obligar a la pantalla de precios a conocerla seria acoplar dos formularios que no se pisan—,
	 * y por eso tampoco se fuerza: un precio nuevo no invalida una edicion de la oferta en vuelo.
	 */
	OfertaServicioConsultorio exigirOfertaParaPrecio(
			OperatingActor actor, long organizationId, long consultorioId, long ofertaId) {

		exigirContextoDeLaSede(actor, organizationId, consultorioId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));

		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		if (!sede.active()) {
			throw new ConsultorioNoOperableException(sede.id());
		}
		ofertas.bloquearParaConfigurar(ofertaId, organizationId, consultorioId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
		OfertaServicioConsultorio oferta = cargar(organizationId, consultorioId, ofertaId);
		if (!oferta.isOperable()) {
			throw new OfertaInactivaException(ofertaId, "configurar");
		}
		return oferta;
	}

	ConsultorioSnapshot exigirLectura(OperatingActor actor, long organizationId, long consultorioId) {
		if (actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| !accountContextDirectory.hasActiveMembership(actor.accountId(), organizationId)) {
			log.info("Lectura de la configuracion de una oferta ajena rechazada: accountId={} "
					+ "organizationId={}", actor.accountId(), organizationId);
			throw new ConsultorioNoAccesibleException(consultorioId);
		}
		return exigirSedeDelTenant(organizationId, consultorioId);
	}

	/** La oferta para LEERLA: sin force-increment. */
	OfertaServicioConsultorio cargar(long organizationId, long consultorioId, long ofertaId) {
		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
	}

	private void exigirContextoDeLaSede(
			OperatingActor actor, long organizationId, long consultorioId) {

		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Configuracion de oferta sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| actor.consultorioId() != consultorioId) {
			log.info("Configuracion de oferta fuera del contexto: accountId={} "
					+ "organizationId={} consultorioId={}",
					actor.accountId(), organizationId, consultorioId);
			throw new ConsultorioNoAccesibleException(consultorioId);
		}
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}
}
