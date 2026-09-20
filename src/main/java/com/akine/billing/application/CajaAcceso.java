package com.akine.billing.application;

import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Las tres precondiciones que comparten todas las operaciones de caja, en un solo lugar.
 *
 * <p>Se extrajo porque {@code CajaService} y {@code MovimientoCajaService} las repiten enteras y
 * porque la tercera —la fecha de negocio— es la que mas facil se hace mal: calcularla con la zona
 * del servidor en vez de con la de la sede hace que un movimiento de las 21:30 en Ushuaia caiga en
 * el dia equivocado y que el arqueo no cierre por una razon invisible.
 */
@Component
class CajaAcceso {

	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;

	CajaAcceso(ConsultorioDirectory consultorios, PermissionGuard permissionGuard) {
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
	}

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La caja requiere un contexto de trabajo activo");
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

	/** Exige {@code caja:operate} con la sede como alcance. */
	void exigirOperarCaja(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CAJA_OPERATE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	/**
	 * El dia operativo de la sede para un instante dado.
	 *
	 * <p><b>Se calcula con la zona IANA de la sede, nunca con la del servidor.</b> Es la unica razon
	 * por la que {@code ConsultorioSnapshot} expone {@code timezone} —V16 lo dice con todas las
	 * letras al hablar del "corte de caja"— y el descuido produce el peor de los errores posibles
	 * en esta etapa: un movimiento imputado al dia que no es, en un arqueo que despues nadie puede
	 * explicar.
	 */
	static LocalDate fechaDeNegocio(ConsultorioSnapshot sede, Instant instante) {
		return instante.atZone(ZoneId.of(sede.timezone())).toLocalDate();
	}
}
