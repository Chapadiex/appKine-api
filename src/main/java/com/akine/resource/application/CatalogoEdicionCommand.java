package com.akine.resource.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Edicion parcial de un concepto del catalogo.
 *
 * <p>Semantica de PATCH: los campos {@code null} no se tocan. Para borrar la descripcion se
 * manda cadena vacia; para dejar la vigencia sin fin se manda {@code clearValidUntil}.
 *
 * <p><b>El codigo no esta y no es un olvido.</b> Es la clave estable con la que los convenios y
 * las sesiones referencian el concepto: mutarlo haria que un historico de 2024 apunte a algo
 * que hoy significa otra cosa, que es exactamente lo que RN-M06-002 prohibe. Renombrar es
 * cambiar {@code name}.
 *
 * <p><b>El alcance tampoco.</b> Promover un concepto contextual a global lo haria visible para
 * todos los tenants sin que ninguno lo haya pedido, y cambiaria el significado de los hechos
 * que ya lo referencian. El camino es una solicitud de alta (RF-M06-005).
 *
 * <p>{@code expectedVersion} es obligatoria y se compara antes de mutar: sin ella dos ediciones
 * simultaneas se pisan y el segundo en guardar borra el cambio del primero sin que nadie se
 * entere.
 */
public record CatalogoEdicionCommand(

		String name,

		String descripcion,

		Instant validFrom,

		Instant validUntil,

		/**
		 * Deja la vigencia sin fin. Hace explicito lo que un campo omitido no puede expresar:
		 * "no toques el fin" y "sacale el fin" son dos intenciones distintas y con un solo campo
		 * nulable la segunda no se puede pedir.
		 */
		boolean clearValidUntil,

		/** Solo para una vigencia de nomenclador: corrige el valor publicado. */
		BigDecimal valorReferencia,

		long expectedVersion) {
}
