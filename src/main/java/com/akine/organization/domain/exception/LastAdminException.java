package com.akine.organization.domain.exception;

/**
 * La operacion dejaria a la organizacion sin ningun {@code ORG_ADMIN} vigente.
 *
 * <p><b>Es un 409 y no un 403:</b> el actor tiene el permiso; lo que no admite la operacion es
 * el ESTADO en el que dejaria al tenant. Confundirlo con un permiso haria que el mensaje al
 * usuario fuera inaccionable ("no podes" en vez de "promove a otro primero").
 *
 * <p><b>Por que el invariante importa mas de lo que parece.</b> Con la decision D-1 cerrada
 * como opcion B, AKINE-01.03 no expone flujo de invitacion: si el ultimo administrador de un
 * tenant pierde su rol, dentro del producto no queda NINGUNA via de rescate. Se repara con SQL
 * manual o con un {@code PLATFORM_ADMIN} amparado por acceso de soporte, y las dos cosas son
 * operacion, no producto.
 *
 * <p>El invariante es sobre la ORGANIZACION, no sobre el consultorio: una sede puede quedarse
 * sin {@code CONSULTORIO_ADMIN} —la administra el {@code ORG_ADMIN}—, y 01.03 no inventa un
 * segundo invariante que la matriz no pide.
 */
public class LastAdminException extends RuntimeException {

	private final Long organizationId;

	public LastAdminException(Long organizationId) {
		super("La organizacion no puede quedar sin ningun administrador vigente");
		this.organizationId = organizationId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}
}
