package com.akine.organization.domain.exception;

/**
 * Esa cuenta ya tiene una membership con ese mismo alcance en esa organizacion.
 *
 * <p>Nace del unique {@code uk_membership_org_account_scope} (V10), no de un chequeo previo:
 * dos altas simultaneas leerian las dos "no existe" y las dos insertarian. El unique decide y
 * el perdedor llega aca como 409, no como 500 — mismo camino y misma leccion que
 * {@link GrantAlreadyActiveException}: {@code saveAndFlush}, traducir, y ninguna operacion JPA
 * despues del flush fallido.
 *
 * <p><b>Hay un caso en el que este 409 es incomprensible para el usuario, y esta abierto.</b>
 * El discriminador del unique incluye las filas historicas, asi que revocar a una persona y
 * volver a vincularla en la MISMA sede choca contra una fila revocada que sigue ocupando la
 * clave. Es la decision D-13 del diseño de AKINE-01.03 y no se resolvio en esta etapa: las dos
 * salidas —sacar del unique a las filas no vigentes, o reusar la fila y perder el historial—
 * tienen costos distintos y ninguna es obvia. Hasta que se decida, el mensaje dice
 * explicitamente que puede tratarse de un vinculo anterior ya revocado.
 */
public class MembershipAlreadyExistsException extends RuntimeException {

	private final Long organizationId;
	private final Long consultorioId;

	public MembershipAlreadyExistsException(Long organizationId, Long consultorioId) {
		super("Esa cuenta ya tiene un vinculo con ese alcance en la organizacion. "
				+ "Puede tratarse de un vinculo anterior ya revocado.");
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}
}
