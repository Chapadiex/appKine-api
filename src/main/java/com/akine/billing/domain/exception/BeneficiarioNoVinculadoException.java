package com.akine.billing.domain.exception;

/**
 * Se crea un egreso contra una membership que no existe o no esta habilitada. <b>409</b>.
 *
 * <p><b>Solo se valida al crear el borrador.</b> Confirmar y pagar un egreso cuyo beneficiario ya
 * se desvinculo tiene que funcionar: si el profesional se fue el 30 de septiembre, el centro le
 * sigue debiendo septiembre, y lo contrario convertiria una desvinculacion en una forma de no
 * pagar. Lo que esta excepcion impide es cargar una liquidacion <b>nueva</b> contra un vinculo que
 * ya no esta, que es un error de carga y no una deuda vieja.
 */
public class BeneficiarioNoVinculadoException extends RuntimeException {

	private final Long membershipId;

	public BeneficiarioNoVinculadoException(Long membershipId) {
		super("La membership " + membershipId + " no esta vigente en esta organizacion: "
				+ "no se puede cargar un egreso nuevo a su nombre");
		this.membershipId = membershipId;
	}

	public Long getMembershipId() {
		return membershipId;
	}
}
