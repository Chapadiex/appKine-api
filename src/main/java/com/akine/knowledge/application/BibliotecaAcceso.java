package com.akine.knowledge.application;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.PermissionCodes;
import com.akine.knowledge.domain.exception.BibliotecaDeshabilitadaException;
import com.akine.knowledge.domain.exception.LibroNoEncontradoException;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Puerta unica de entrada a la biblioteca: interruptor, tenant y permiso. */
@Service
public class BibliotecaAcceso {

	private final BibliotecaAjustes ajustes;
	private final PermissionGuard permissionGuard;

	public BibliotecaAcceso(BibliotecaAjustes ajustes, PermissionGuard permissionGuard) {
		this.ajustes = ajustes;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * @throws BibliotecaDeshabilitadaException si el interruptor esta apagado (409)
	 * @throws AccessDeniedException si no hay contexto de organizacion
	 * @throws LibroNoEncontradoException si la organizacion de la ruta no es la del contexto (404)
	 */
	public void exigir(OperatingActor actor, long orgId) {
		exigir(actor, orgId, PermissionCodes.BIBLIOTECA_MANAGE, null);
	}

	/**
	 * Igual que {@link #exigir}, con el permiso {@code biblioteca:consultar} y la sede del
	 * contexto: sin ella el evaluador solo mira memberships de organizacion, y un
	 * CONSULTORIO_ADMIN o un profesional vinculado a una sede recibirian 403. La gestion sigue
	 * sin sede: es solo del ORG_ADMIN, y con sede ganaria una membership de sede mas especifica.
	 */
	public void exigirConsulta(OperatingActor actor, long orgId) {
		exigir(actor, orgId, PermissionCodes.BIBLIOTECA_CONSULTAR, actor.contextConsultorioId());
	}

	private void exigir(OperatingActor actor, long orgId, String permiso, Long consultorioId) {
		if (!ajustes.habilitada()) {
			throw new BibliotecaDeshabilitadaException();
		}
		Long contexto = actor.contextOrganizationId();
		if (contexto == null) {
			throw new AccessDeniedException("La operacion requiere una organizacion en contexto");
		}
		if (contexto != orgId) {
			throw new LibroNoEncontradoException();
		}
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso, orgId, consultorioId, null, Instant.now()));
	}
}
