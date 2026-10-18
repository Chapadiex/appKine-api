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
 *   LEER   convenio:read evaluado con la sede del contexto (U-07, mismo criterio que DP-22).
 *          Hasta U-07 alcanzaba la pertenencia al tenant, y el rol PACIENTE leia financiadores,
 *          planes, convenios y aranceles. Lo tiene todo el personal; PACIENTE no.
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
	 * Exige contexto de organizacion, evalua {@code convenio:read} y devuelve la organizacion. Es
	 * el control de TODAS las LECTURAS del modulo (U-07, mismo criterio que DP-22).
	 *
	 * <p>Quien pertenece sin el permiso recibe <b>403</b>; quien pide un financiador, plan o
	 * convenio de otra organizacion sigue recibiendo <b>404</b>, porque la organizacion sale del
	 * contexto y la busqueda posterior no lo encuentra.
	 *
	 * <p>Se evalua con la sede <b>del contexto</b> y no con la de la ruta, igual que
	 * {@code paciente:read}: sin sede los alcances de consultorio no cubren la consulta, y usar la
	 * de la ruta le cerraria a un {@code PROFESIONAL} los convenios de otra sede de su organizacion,
	 * que hasta U-07 leia. El unico cambio de comportamiento buscado es el del {@code PACIENTE}.
	 *
	 * <p>La decision no se devuelve: ningun rol con alcance {@code SOPORTE} tiene
	 * {@code convenio:read}, asi que no hay {@code SUPPORT_ACCESS_USED} que dejar.
	 */
	static long exigirLectura(
			PermissionGuard permissionGuard, OperatingActor actor, String operacion) {

		long organizationId = exigirContexto(actor, operacion);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONVENIO_READ,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));
		return organizationId;
	}

	/**
	 * Exige contexto de organizacion y lo devuelve. Es el primer paso de las mutaciones de M16,
	 * que despues evaluan {@code convenio:manage} sobre la sede de la ruta.
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

	/**
	 * Exige contexto de organizacion y evalua {@code convenio:manage} sobre <b>la sede de la
	 * ruta</b>. Es el control de las mutaciones de M16 (AKINE-03.05).
	 *
	 * <p><b>Por que la sede sale de la ruta y no del contexto, a diferencia de M15.</b> Un convenio
	 * es contextual al consultorio (RN-M16-001): la sede no es un detalle del request, es parte de
	 * la identidad del recurso que se administra. Evaluar el permiso sobre la sede del contexto
	 * mientras se escribe sobre la de la ruta dejaria pasar a alguien con permiso en la sede A a
	 * modificar los convenios de la B — y la ruta seguiria diciendo la verdad, que es lo que hace
	 * que el defecto sea dificil de ver.
	 *
	 * <p>Sigue exigiendo contexto de organizacion: la organizacion nunca viaja por la ruta, sale de
	 * lo que {@code TenantContextFilter} revalido contra la base en este request. Que la sede de la
	 * ruta pertenezca a esa organizacion lo comprueba el llamador con {@code ConsultorioDirectory},
	 * y una sede ajena responde 404 antes de llegar a esta evaluacion.
	 *
	 * <p>Un {@code ORG_ADMIN} pasa para cualquier sede de su organizacion, porque su alcance es
	 * ORGANIZACION. Un {@code CONSULTORIO_ADMIN} pasa solo para la suya, que es exactamente lo que
	 * la matriz §2 le concede.
	 */
	static PermissionDecision exigirGestionDeLaSede(
			PermissionGuard permissionGuard,
			OperatingActor actor,
			long consultorioId,
			String operacion) {

		if (actor.contextOrganizationId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}

		return permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONVENIO_MANAGE,
				actor.contextOrganizationId(),
				consultorioId,
				null,
				Instant.now()));
	}
}
