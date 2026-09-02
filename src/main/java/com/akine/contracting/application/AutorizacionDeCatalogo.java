package com.akine.contracting.application;

import com.akine.contracting.domain.PermissionCodes;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;

/**
 * Los controles de acceso que comparten los dos servicios de M15, en un solo lugar.
 *
 * <p>Utilidad estatica con dependencias por parametro, mismo criterio y mismos motivos que
 * {@code person.application.AutorizacionDePadron}: no hay estado que guardar, ningun servicio
 * conoce a otro, y los tests de cada uno siguen inyectando sus propios dobles.
 *
 * <h2>Las dos formas de autorizar de este modulo</h2>
 *
 * <pre>
 *   LEER   pertenencia al tenant: alcanza con tener contexto de organizacion activo. No hay
 *          convenio:read en el catalogo de la matriz y una etapa no lo inventa. El hueco que
 *          eso deja esta declarado en PermissionCodes.
 *   MUTAR  convenio:manage evaluado CON la sede del contexto. Que lleve sede aunque el
 *          financiador sea de la organizacion NO es obvio y esta explicado en PermissionCodes:
 *          sin sede, el evaluador deja afuera al CONSULTORIO_ADMIN.
 * </pre>
 *
 * <p><b>Falta de contexto es 403 y nunca 401.</b> El interceptor del frontend borra el token ante
 * cualquier 401 y deja al usuario en un bucle de login del que no sale.
 */
final class AutorizacionDeCatalogo {

	private static final Logger log = LoggerFactory.getLogger(AutorizacionDeCatalogo.class);

	private AutorizacionDeCatalogo() {
		// Utilidad de autorizacion.
	}

	/**
	 * Exige contexto de organizacion y lo devuelve. Es el control de las LECTURAS.
	 *
	 * <p>La organizacion nunca viaja por parametro ni por la ruta: sale del contexto que
	 * {@code TenantContextFilter} revalido contra la base en este request.
	 */
	static long exigirContexto(OperatingActor actor, String operacion) {
		if (actor.contextOrganizationId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/**
	 * Exige contexto de organizacion Y de sede, y evalua {@code convenio:manage} sobre esa sede.
	 * Es el control de las MUTACIONES.
	 *
	 * <p><b>Devuelve la decision y no la organizacion</b>, aunque la organizacion sea lo que el
	 * llamador usa despues: la decision es lo unico que sabe si el permiso se concedio por un
	 * acceso de soporte vigente, y en ese caso el llamador tendria que dejar la fila
	 * {@code SUPPORT_ACCESS_USED} que exige el invariante de la matriz §7. Hoy ningun rol de
	 * plataforma tiene asignacion base de {@code convenio:manage} —ver {@code PermissionCodes}—
	 * asi que esa rama no se puede alcanzar; devolver la decision es lo que hace que el dia que se
	 * conceda no haya que rehacer la firma.
	 */
	static PermissionDecision exigirGestionDelCatalogo(
			PermissionGuard permissionGuard, OperatingActor actor, String operacion) {

		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}

		return permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONVENIO_MANAGE,
				actor.contextOrganizationId(),
				actor.consultorioId(),
				null,
				Instant.now()));
	}
}
