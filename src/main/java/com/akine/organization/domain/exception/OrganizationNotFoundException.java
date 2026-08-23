package com.akine.organization.domain.exception;

/**
 * La organizacion pedida no existe, o existe pero no es alcanzable desde el contexto actual.
 *
 * <p><b>Los dos casos son el mismo a proposito.</b> Distinguir "no existe" de "existe pero es
 * de otro tenant" filtra la existencia de organizaciones ajenas: alcanza con probar ids para
 * enumerar clientes del SaaS. Por eso cross-tenant se responde igual que inexistente, y por
 * eso el mensaje nunca incluye datos de la organizacion buscada.
 */
public class OrganizationNotFoundException extends RuntimeException {

	private final Long organizationId;

	public OrganizationNotFoundException(Long organizationId) {
		super("Organizacion no encontrada o no accesible desde el contexto actual");
		this.organizationId = organizationId;
	}

	/** Id pedido. Sirve para el log correlacionado, jamas para el cuerpo de la respuesta. */
	public Long getOrganizationId() {
		return organizationId;
	}
}
