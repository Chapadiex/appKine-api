package com.akine.offering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * El conjunto COMPLETO de recursos habilitados para una oferta.
 *
 * <p><b>Reemplaza, no agrega.</b> Lo que entra y no estaba se crea, lo que estaba y no entra se da
 * de baja con motivo automatico, y lo que sigue no se toca —conservando su id, su vigencia y su
 * version—. Es lo que la pantalla necesita: una grilla de casillas se guarda entera. La
 * alternativa —altas y bajas de a una— obligaria al cliente a diffear, y un cliente que diffea mal
 * produce bajas que nadie pidio.
 *
 * <p><b>Una lista vacia no es un error: deja la oferta SIN RESTRINGIR</b>, o sea disponible para
 * cualquier profesional con vinculo vigente y en cualquier espacio de la sede. Es la operacion
 * legitima de "sacar la restriccion", y por eso no se rechaza. La respuesta devuelve
 * {@code restringida* = false} para que la pantalla lo pueda decir con palabras.
 */
@Schema(description = "Conjunto completo de recursos habilitados. Reemplaza, no agrega")
public record ReemplazarHabilitacionesRequest(

		@Schema(
				description = "Ids de los recursos que quedan habilitados: memberships de "
						+ "profesional o espacios, segun el endpoint. Lista vacia = quitar la "
						+ "restriccion",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La lista de habilitados es obligatoria; para no restringir, mandala vacia")
		List<Long> ids,

		@Schema(
				description = "Version de la OFERTA que el cliente cree estar configurando. Es lo "
						+ "que serializa a dos administradores editando la misma configuracion: "
						+ "sin esto, el segundo en guardar borra en silencio lo que agrego el "
						+ "primero",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(value = 0, message = "expectedVersion no puede ser negativo")
		Long expectedVersion) {
}
