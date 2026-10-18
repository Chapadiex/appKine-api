package com.akine.activity.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.akine.organization.spi.PermissionDecision;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditoriaAparte;

/**
 * Deja {@code SUPPORT_ACCESS_USED} cuando un permiso de {@code activity} se concedio por un acceso
 * de soporte (AKINE-09.02).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>El {@code PLATFORM_ADMIN} tiene {@code clase:read} e {@code inscripcion:read} con alcance
 * {@code SOPORTE}: con un acceso de soporte vigente lee la grilla, los participantes —nombre y
 * documento—, el detalle operativo y el panel de una clase. La matriz §7 exige que <b>cada</b>
 * operacion amparada por soporte quede auditada, y {@code RolePermissions} le dio
 * {@code inscripcion:read} con SOPORTE justamente porque devuelve personas. Hasta 09.02 todos los
 * servicios de {@code activity} descartaban la {@link PermissionDecision}: ninguna de esas lecturas
 * dejaba rastro.
 *
 * <p>Se escribe por {@link AuditoriaAparte}, como {@code reporting} y {@code person}: varias de
 * estas lecturas son {@code readOnly} —el flush es manual y la fila no llegaria a la base— y una
 * que termina en 404 igual ejercio el acceso de soporte.
 */
@Service
public class SoporteEnActividad {

	static final String EVENTO = "SUPPORT_ACCESS_USED";
	static final String ENTIDAD = "ACTIVIDAD";
	private static final String MDC_TRACE_ID = "traceId";

	private final AuditoriaAparte auditoria;

	public SoporteEnActividad(AuditoriaAparte auditoria) {
		this.auditoria = auditoria;
	}

	/** No hace nada si la decision no vino por soporte (o si no hay decision). */
	public void registrarSiCorresponde(
			PermissionDecision decision, long accountId, long organizationId, long consultorioId,
			String permiso) {

		if (decision == null || !decision.viaSupportAccess()) {
			return;
		}
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("permiso", permiso);
		auditoria.registrarAccesoDeSoporte(new AuditEntry(
				organizationId, consultorioId, accountId, EVENTO, ENTIDAD, null, null, null,
				detalle, null, MDC.get(MDC_TRACE_ID), Instant.now()));
	}
}
