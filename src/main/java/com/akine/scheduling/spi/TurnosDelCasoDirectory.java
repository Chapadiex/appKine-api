package com.akine.scheduling.spi;

import java.util.List;

/**
 * Los turnos de un Caso Clinico, para analizar su evolucion (M31).
 *
 * <p>Incluye los cancelados, que cancelar sella con {@code deleted_at}: son justo los que importan
 * para la adherencia. Un turno reprogramado es una sola fila —reprogramar mueve el turno— y no
 * aparece dos veces. No controla permisos: quien llama ya autorizo la lectura clinica.
 */
public interface TurnosDelCasoDirectory {

	/** Los turnos del caso en la organizacion, por inicio ascendente. */
	List<TurnoDelCasoSnapshot> delCaso(long organizationId, long casoId);
}
