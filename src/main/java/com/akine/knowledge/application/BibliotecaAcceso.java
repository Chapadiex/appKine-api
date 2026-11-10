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
		permissionGuard.requirePermission(PermissionQuery.of(
				actor.accountId(), PermissionCodes.BIBLIOTECA_MANAGE, orgId, Instant.now()));
	}
}
