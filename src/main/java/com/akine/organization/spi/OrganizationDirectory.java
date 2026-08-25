package com.akine.organization.spi;

import java.util.Optional;

/**
 * Consulta minima de una organizacion desde otro modulo.
 *
 * <p>Existe por el mismo motivo que {@link ConsultorioDirectory}: hay un modulo —{@code
 * identity}— que necesita el <b>nombre</b> de la organizacion para redactar la invitacion, y no
 * puede leer la tabla. Un correo que dijera "te invitaron a la organizacion 42" no lo abre
 * nadie.
 *
 * <p>Devuelve un record del {@code spi} y nunca la entity, igual que el resto de los directorios
 * del modulo.
 */
public interface OrganizationDirectory {

	/** La organizacion, si existe. {@code Optional.empty()} si no. */
	Optional<OrganizationSnapshot> find(long organizationId);
}
