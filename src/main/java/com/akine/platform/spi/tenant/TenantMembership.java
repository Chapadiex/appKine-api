package com.akine.platform.spi.tenant;

/**
 * Resultado de resolver una membership contra la base, en la forma que {@code platform}
 * entiende.
 *
 * <p><b>Es un record del {@code spi}, no una entity.</b> {@code MembershipDirectory} jamas
 * devuelve {@code com.akine.organization.domain.Membership}: eso obligaria a {@code platform} a
 * compilar contra {@code organization} —ciclo— y ademas expondria columnas internas de otro
 * modulo (baja logica, {@code version}, vigencia) a codigo que no es su propietario.
 *
 * <p>{@code roleCode} viaja como {@code String} y no como enum por el mismo motivo: el catalogo
 * de roles pertenece a la matriz de permisos que administra {@code organization}/01.03.
 * {@code platform} lo transporta hasta el contexto del request y no lo interpreta; quien decide
 * que puede hacer cada rol es el modulo que evalua permisos, no el filtro de tenancy.
 *
 * @param membershipId       id de la fila de membership vigente que habilito el contexto
 * @param roleCode           rol de la membership, tal cual esta en la matriz aprobada
 * @param operationalStatus  estado operativo del tenant, derivado de su suscripcion y de la
 *                           baja logica de la organizacion. El filtro lo compone con la
 *                           membership para decidir: ese es el {@code TenantAccessValidator}
 *                           que la decision T-3 elimino como interfaz separada
 */
public record TenantMembership(
		long membershipId,
		String roleCode,
		TenantOperationalStatus operationalStatus) {

	public TenantMembership {
		if (roleCode == null || roleCode.isBlank()) {
			throw new IllegalArgumentException("roleCode es obligatorio en una membership resuelta");
		}
		if (operationalStatus == null) {
			throw new IllegalArgumentException("operationalStatus es obligatorio en una membership resuelta");
		}
	}
}
