package com.akine.scheduling.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditoriaAparte;

/**
 * Evalua un permiso de {@code scheduling} y deja {@code SUPPORT_ACCESS_USED} cuando se concedio
 * por un acceso de soporte (R13 del threat model).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>El {@code PLATFORM_ADMIN} tiene {@code turno:read} con alcance {@code SOPORTE}
 * ({@code RolePermissions}): con un acceso de soporte vigente lee la agenda de una oferta, la
 * agenda unificada, los turnos del dia de recepcion —con el nombre del paciente de cada uno—, un
 * turno suelto, su historial, el de su recepcion y las series. La matriz §7 exige que <b>cada</b>
 * operacion amparada por soporte quede auditada, y hasta R13 todos esos servicios descartaban la
 * {@link PermissionDecision}: ninguna de esas lecturas dejaba rastro. Es el mismo hueco que
 * AKINE-09.02 cerro en {@code activity} con {@code SoporteEnActividad}.
 *
 * <p>Hoy ninguna ruta de {@code scheduling} lleva la organizacion, asi que por HTTP un
 * administrador de plataforma cae antes en el 403 por falta de contexto. La auditoria vive igual
 * en el servicio, junto a la evaluacion: el dia que una ruta de soporte llegue aca, la fila ya se
 * escribe, en vez de depender de que alguien se acuerde.
 *
 * <p>Se escribe por {@link AuditoriaAparte}, como {@code activity}, {@code reporting} y
 * {@code person}: estas lecturas son {@code readOnly} —el flush es manual y la fila no llegaria a
 * la base— y una que termina en 404 igual ejercio el acceso de soporte.
 */
@Service
public class SoporteEnAgenda {

	static final String EVENTO = "SUPPORT_ACCESS_USED";
	static final String ENTIDAD = "AGENDA";
	private static final String MDC_TRACE_ID = "traceId";

	private final PermissionGuard permissionGuard;
	private final AuditoriaAparte auditoria;

	public SoporteEnAgenda(PermissionGuard permissionGuard, AuditoriaAparte auditoria) {
		this.permissionGuard = permissionGuard;
		this.auditoria = auditoria;
	}

	/**
	 * Exige el permiso con la sede como alcance y, si se concedio por soporte, deja la fila.
	 *
	 * @throws org.springframework.security.access.AccessDeniedException sin el permiso (403)
	 */
	public void exigir(OperatingActor actor, String permiso, long organizationId, long consultorioId) {
		Instant ahora = Instant.now();
		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso, organizationId, consultorioId, null, ahora));
		registrarSiCorresponde(decision, actor.accountId(), organizationId, consultorioId, permiso, ahora);
	}

	/** No hace nada si la decision no vino por soporte (o si no hay decision). */
	void registrarSiCorresponde(
			PermissionDecision decision, long accountId, long organizationId, long consultorioId,
			String permiso, Instant ahora) {

		if (decision == null || !decision.viaSupportAccess()) {
			return;
		}
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("permiso", permiso);
		auditoria.registrarAccesoDeSoporte(new AuditEntry(
				organizationId, consultorioId, accountId, EVENTO, ENTIDAD, null, null, null,
				detalle, null, MDC.get(MDC_TRACE_ID), ahora));
	}
}
