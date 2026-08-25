package com.akine.resource.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Alta de una vigencia de un codigo dentro de un nomenclador (RF-M06-003, RN-M06-003).
 *
 * <p><b>No lleva alcance.</b> Una vigencia hereda el duenio de su nomenclador y no puede
 * apartarse de el: una vigencia contextual dentro de un nomenclador global seria un dato de un
 * tenant escondido en el catalogo comun, visible o no segun quien consulte. El servicio lo
 * copia del padre y no lo pregunta.
 *
 * <p>Actualizar el valor de un codigo <b>no es editar esta fila</b>: es cerrar la vigencia
 * anterior —ponerle {@code validUntil}— y crear una nueva. Editar la fila vieja borraria lo que
 * ese codigo decia cuando se uso, que es exactamente lo que RN-M06-002 protege.
 */
public record VigenciaAltaCommand(

		/** Prestacion que este codigo representa. Debe ser visible y estar vigente. */
		Long practicaId,

		/** Codigo dentro del nomenclador. Se repite entre vigencias del mismo concepto. */
		String codigo,

		/** Denominacion publicada para esta vigencia. Los renombres son parte de la historia. */
		String name,

		String descripcion,

		/** Valor o unidades que publica el nomenclador. NO es el arancel: eso es del convenio. */
		BigDecimal valorReferencia,

		/** Si viene {@code null}, el momento del alta. */
		Instant validFrom,

		/** {@code null} = vigencia abierta. */
		Instant validUntil) {
}
