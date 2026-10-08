package com.akine.platform.infrastructure.observability;

/**
 * Nombres de las claves que AKINE publica en el MDC del log (G-4).
 *
 * <p>Son los nombres de campo que aparecen en cada linea del log JSON, asi que cambiarlos rompe
 * cualquier consulta o dashboard armado sobre ellos. {@code traceId} y {@code spanId} no estan
 * aca porque no los pone AKINE: los publica Micrometer Tracing al abrir el span del request, y
 * los {@code AuditEvents} de cada modulo los leen por su nombre literal para llenar
 * {@code audit_event.correlation_id}.
 *
 * <p><b>Solo identificadores tecnicos.</b> Nada de lo que viaja en el MDC puede ser dato de
 * salud ni un secreto: termina en cada linea de log que el request produzca, incluidas las de
 * librerias de terceros que no pasan por ningun sanitizador.
 */
public final class ClavesDeMdc {

	/** Id de correlacion del request: el {@code X-Request-Id} recibido o uno generado. */
	public static final String REQUEST_ID = "requestId";

	/** Organizacion del contexto de trabajo resuelto por {@code TenantContextFilter}. */
	public static final String ORGANIZATION_ID = "organizationId";

	/** Consultorio del contexto de trabajo resuelto por {@code TenantContextFilter}. */
	public static final String CONSULTORIO_ID = "consultorioId";

	private ClavesDeMdc() {
	}
}
