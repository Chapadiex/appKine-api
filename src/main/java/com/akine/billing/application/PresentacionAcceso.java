package com.akine.billing.application;

import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.FinanciadorNoAccesibleException;
import com.akine.contracting.spi.CoberturaCatalogoDirectory;
import com.akine.contracting.spi.FinanciadorSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Las cuatro precondiciones que comparten todas las operaciones de M21, en un solo lugar.
 *
 * <p>Se extrajo por lo mismo que {@code CajaAcceso}: {@code PresentacionService} y
 * {@code FinanciadorPagoService} las repiten enteras, y la cuarta —el financiador— es la unica que
 * cruza un borde de modulo y conviene que se vea en un solo archivo.
 *
 * <h2>El permiso es {@code cobro:register} y no uno nuevo</h2>
 *
 * <p>El catalogo de la matriz §5 es cerrado y no tiene "presentar a financiador".
 * {@code cobro:register} ya esta declarado como <i>ver y administrar deuda y cobros</i> y su
 * asignacion base —{@code ORG_ADMIN} sobre la organizacion, {@code CONSULTORIO_ADMIN} y
 * {@code ADMINISTRATIVO} sobre la sede— coincide <b>exactamente</b> con los actores que M21 §2
 * declara. {@code PROFESIONAL} no lo tiene, que es lo correcto: presentar a una obra social no es
 * un acto clinico.
 *
 * <p>Inventar {@code presentacion:manage} habria exigido una enmienda a la matriz para repartir los
 * mismos roles con el mismo alcance. <b>Un permiso que nunca discrimina a nadie no es un control:
 * es una linea mas que mantener.</b>
 */
@Component
class PresentacionAcceso {

	private final ConsultorioDirectory consultorios;
	private final CoberturaCatalogoDirectory financiadores;
	private final PermissionGuard permissionGuard;

	PresentacionAcceso(
			ConsultorioDirectory consultorios,
			CoberturaCatalogoDirectory financiadores,
			PermissionGuard permissionGuard) {

		this.consultorios = consultorios;
		this.financiadores = financiadores;
		this.permissionGuard = permissionGuard;
	}

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException(
					"Las presentaciones a financiadores requieren un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/**
	 * La sede, acotada al tenant. Cross-tenant es <b>404 y nunca 403</b>: un 403 confirma que
	 * existe, y bastaria probar ids consecutivos para enumerar las sedes del SaaS.
	 */
	ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	/**
	 * El financiador, por el {@code spi} de {@code contracting} y nunca leyendo su tabla.
	 *
	 * <p>Es una <b>lectura viva</b> y se usa para decidir y para mostrar el nombre, nunca para
	 * guardarlo: lo que la presentacion persiste es el {@code financiador_id}, y el nombre se
	 * resuelve al leer. No hace falta congelarlo porque un lote no se imprime desde esta vista — el
	 * texto congelado que si importa es el concepto de cada prestacion, que el item copia.
	 */
	FinanciadorSnapshot exigirFinanciador(long organizationId, long financiadorId) {
		return financiadores.findFinanciador(organizationId, financiadorId)
				.filter(FinanciadorSnapshot::operable)
				.orElseThrow(() -> new FinanciadorNoAccesibleException(financiadorId));
	}

	/** El nombre para mostrar, sin exigir que sea operable: los lotes historicos siguen leyendose. */
	String nombreDe(long organizationId, long financiadorId) {
		return financiadores.findFinanciador(organizationId, financiadorId)
				.map(FinanciadorSnapshot::nombre)
				.orElse(null);
	}

	/** Exige {@code cobro:register} con la sede como alcance. */
	void exigirOperar(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.COBRO_REGISTER,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}
}
