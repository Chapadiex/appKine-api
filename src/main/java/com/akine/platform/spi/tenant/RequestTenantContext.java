package com.akine.platform.spi.tenant;

/**
 * Contexto de tenant ya VALIDADO para el request en curso.
 *
 * <p>Esta es la unica autoridad sobre "en que tenant estamos". No lo son los claims del token
 * (son un hint, RN-M01-003) ni {@code account_active_context} (es la ultima seleccion del
 * usuario, no lo que este request esta haciendo). Todo repositorio de negocio de cualquier
 * modulo filtra por {@link #organizationId()}, y ese filtro es lo que impide que una consulta
 * cruce tenants.
 *
 * <p>Existe solo si {@code TenantContextFilter} lo publico: si el filtro rechazo el request, el
 * holder queda vacio y no hay contexto que usar.
 *
 * <p><b>Nombre.</b> Se llama {@code RequestTenantContext} y no {@code TenantContextRequest}
 * porque la convencion {@code dtos_agrupados} obliga a que todo lo que TERMINA en
 * {@code Request}/{@code Response} viva en {@code ..api.dto..}. El sufijo es el que manda, no
 * el prefijo: verificado contra {@code CodingConventionsTest}.
 *
 * @param accountId          cuenta autenticada que opera
 * @param organizationId     tenant efectivo del request
 * @param consultorioId      sede efectiva del request
 * @param roleCode           rol de la membership que habilito este contexto
 * @param operationalStatus  estado operativo del tenant al momento de resolver el contexto
 */
public record RequestTenantContext(
		long accountId,
		long organizationId,
		long consultorioId,
		String roleCode,
		TenantOperationalStatus operationalStatus) {

	public RequestTenantContext {
		if (roleCode == null || roleCode.isBlank()) {
			throw new IllegalArgumentException("roleCode es obligatorio en el contexto del request");
		}
		if (operationalStatus == null) {
			throw new IllegalArgumentException("operationalStatus es obligatorio en el contexto del request");
		}
	}
}
