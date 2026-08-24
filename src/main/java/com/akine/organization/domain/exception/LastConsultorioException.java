package com.akine.organization.domain.exception;

/**
 * La baja dejaria al tenant sin NINGUNA sede activa.
 *
 * <p><b>Ningun RF lo prohibe: es una restriccion AGREGADA</b> al comportamiento de RF-M03-004,
 * decidida por el usuario el 23/08/2026 (D-7 del diseno de 02.01) y declarada como tal en el
 * registro de cierre de la etapa.
 *
 * <p>El motivo es el mismo que el del invariante "ultimo admin" de 01.03: sin ninguna sede
 * activa, {@code GET /me/contexts} devuelve vacio para todos, nadie puede canjear contexto
 * (ADR-0009) y el tenant queda operativamente muerto. La unica salida seria SQL manual contra
 * la base, o sea operacion y no producto.
 *
 * <p><b>409 y no 403:</b> el actor tiene el permiso; lo que no admite la operacion es el estado
 * en el que dejaria al tenant. La diferencia importa en la pantalla: "no podes" es
 * inaccionable, "crea otra sede antes de cerrar esta" no.
 *
 * <p>El invariante NO lo puede hacer valer el {@code PlanGate}: el gate cuenta hacia arriba
 * contra {@code MAX_CONSULTORIOS} y esto cuenta hacia abajo, contra un piso de 1 que ningun
 * plan declara. Su carrera se cierra con el lock de la suscripcion y un conteo con
 * {@code FOR SHARE}; ver {@code ConsultorioService.deactivate}.
 */
public class LastConsultorioException extends RuntimeException {

	private final Long organizationId;

	public LastConsultorioException(Long organizationId) {
		super("La organizacion no puede quedar sin ninguna sede activa");
		this.organizationId = organizationId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}
}
