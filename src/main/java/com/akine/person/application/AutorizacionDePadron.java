package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.domain.PermissionCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;

/**
 * Los controles de acceso que comparten los dos servicios de M07, en un solo lugar.
 *
 * <p>Utilidad estatica con dependencias por parametro, mismo criterio y mismos motivos que
 * {@code resource.application.AutorizacionDeSede}: no hay estado que guardar, ningun servicio
 * conoce a otro, y los tests de cada uno siguen inyectando sus propios dobles.
 *
 * <h2>Las dos formas de autorizar de este modulo</h2>
 *
 * <pre>
 *   LEER   paciente:read evaluado con la sede del contexto (AKINE-DU-6, DP-22). Hasta DU-6
 *          alcanzaba la pertenencia al tenant, y el rol PACIENTE leia el padron entero.
 *          Lo tiene todo el personal; PACIENTE no.
 *   MUTAR  paciente:manage evaluado CON la sede del contexto. El motivo de que lleve sede
 *          aunque la Persona sea de la organizacion esta en PermissionCodes.PACIENTE_MANAGE
 *          y NO es obvio: sin sede, el evaluador deja afuera a CONSULTORIO_ADMIN y a
 *          ADMINISTRATIVO, que son quienes hacen este trabajo.
 * </pre>
 *
 * <p><b>Falta de contexto es 403 y nunca 401.</b> El interceptor del frontend borra el token ante
 * cualquier 401 y deja al usuario en un bucle de login del que no sale.
 */
final class AutorizacionDePadron {

	private static final Logger log = LoggerFactory.getLogger(AutorizacionDePadron.class);

	private AutorizacionDePadron() {
		// Utilidad de autorizacion.
	}

	/**
	 * Exige contexto de organizacion, evalua {@code paciente:read} y devuelve la organizacion. Es
	 * el control de TODAS las LECTURAS del modulo (AKINE-DU-6, DP-22).
	 *
	 * <p>Hasta DU-6 alcanzaba con el contexto, y una membership con rol {@code PACIENTE} leia el
	 * padron entero. Ahora quien pertenece pero no tiene el permiso recibe <b>403</b>; quien pide
	 * una persona de otra organizacion sigue recibiendo <b>404</b>, porque la organizacion sale del
	 * contexto y la busqueda posterior no la encuentra.
	 *
	 * <p>El permiso se evalua con la sede del contexto, igual que {@code paciente:manage}: sin sede
	 * los alcances de consultorio no cubren la consulta. {@code TenantContextFilter} no publica un
	 * contexto sin sede, asi que en la practica siempre viaja.
	 *
	 * <p>La decision no se devuelve: ningun rol con alcance {@code SOPORTE} tiene
	 * {@code paciente:read}, asi que una lectura nunca se concede por acceso de soporte y no hay
	 * {@code SUPPORT_ACCESS_USED} que dejar. Si algun dia se le da a {@code PLATFORM_ADMIN}, este
	 * metodo tiene que empezar a devolverla, como {@link #exigirGestionDelPadron}.
	 */
	static long exigirLecturaDelPadron(
			PermissionGuard permissionGuard, OperatingActor actor, String operacion) {

		long organizationId = exigirContexto(actor, operacion);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.PACIENTE_READ,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));
		return organizationId;
	}

	/**
	 * Exige contexto de organizacion y lo devuelve.
	 *
	 * <p>La organizacion nunca viaja por parametro ni por la ruta: sale del contexto que
	 * {@code TenantContextFilter} revalido contra la base en este request.
	 */
	private static long exigirContexto(OperatingActor actor, String operacion) {
		if (actor.contextOrganizationId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/**
	 * Exige contexto de organizacion Y de sede, y evalua {@code paciente:manage} sobre esa sede.
	 * Es el control de las MUTACIONES.
	 *
	 * <p><b>Devuelve la decision y no la organizacion</b>, aunque la organizacion sea lo que el
	 * llamador usa a continuacion: la decision es lo unico que sabe si el permiso se concedio por
	 * un acceso de soporte vigente, y en ese caso el llamador tiene que dejar la fila
	 * {@code SUPPORT_ACCESS_USED}. La matriz seccion 4 le da "Soporte" a {@code PLATFORM_ADMIN}
	 * en la fila "Gestionar paciente": puede intervenir, y el invariante de la matriz seccion 7
	 * exige que esa intervencion sea justificada <b>y</b> auditada. Devolver solo el id dejaria
	 * la segunda mitad sin forma de cumplirse.
	 *
	 * <p>La organizacion sale de {@code actor.contextOrganizationId()}, que este metodo ya
	 * verifico que no sea nula.
	 */
	static PermissionDecision exigirGestionDelPadron(
			PermissionGuard permissionGuard, OperatingActor actor, String operacion) {

		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}

		return permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.PACIENTE_MANAGE,
				actor.contextOrganizationId(),
				actor.consultorioId(),
				null,
				Instant.now()));
	}
}
