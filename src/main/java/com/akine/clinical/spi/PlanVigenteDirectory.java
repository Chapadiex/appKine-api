package com.akine.clinical.spi;

import java.util.Optional;

/**
 * El plan vigente de un caso, para modulos que analizan su evolucion (M31).
 *
 * <p>No controla permisos: quien llama ya paso por {@link AccesoClinicoDirectory}. Tampoco audita:
 * la lectura la audita quien la muestra.
 */
public interface PlanVigenteDirectory {

	/** El plan ACTIVO o SUSPENDIDO del caso, o vacio si no tiene. */
	Optional<PlanVigenteSnapshot> delCaso(long organizationId, long casoId);
}
