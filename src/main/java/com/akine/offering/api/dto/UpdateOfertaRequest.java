package com.akine.offering.api.dto;

import com.akine.offering.domain.Modalidad;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edicion de una oferta.
 *
 * <p><b>El servicio no esta y no puede estar.</b> Cambiarlo convertiria esta oferta en otra
 * cosa mientras conserva su id y sus historicos: lo que corresponde es dar de baja esta y crear
 * la que si presta el otro servicio.
 *
 * <p><b>Los tres {@code limpiar*} existen porque {@code null} ya significa "no lo toques".</b>
 * Sin ellos no habria forma de expresar "sacale el precio" o "dejala sin fin previsto": mandar
 * {@code precioBase: null} es indistinguible de no mandarlo. Es fea la solucion y es la honesta;
 * la alternativa —un centinela como {@code -1} o una fecha imposible— esconde la intencion en un
 * valor que despues alguien interpreta mal.
 */
@Schema(description = "Campos a modificar de una oferta")
public record UpdateOfertaRequest(

		@Schema(description = "Nombre comercial nuevo. Null deja el actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre comercial no puede superar los 160 caracteres")
		String nombreComercial,

		@Schema(description = "Descripcion nueva. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(description = "Modalidad nueva. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Modalidad modalidad,

		@Schema(description = "Duracion nueva en minutos. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive(message = "La duracion tiene que ser mayor a cero")
		Integer duracionMinutos,

		@Schema(description = "Capacidad nueva. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive(message = "La capacidad tiene que ser mayor a cero")
		Integer capacidad,

		@Schema(description = "Precio nuevo. Null deja el actual; para borrarlo, limpiarPrecio",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@PositiveOrZero(message = "El precio no puede ser negativo")
		BigDecimal precioBase,

		@Schema(description = "Moneda nueva, ISO 4217. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 3, message = "La moneda es un codigo ISO 4217 de 3 letras")
		String moneda,

		@Schema(
				description = "Dejar la oferta SIN precio declarado. Ignora precioBase si ambos "
						+ "vienen. Si se omite, false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean limpiarPrecio,

		@Schema(description = "Esquema de cobro nuevo. Null deja el actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "El esquema de cobro no puede superar los 32 caracteres")
		String esquemaCobro,

		@Schema(description = "Dejar la oferta SIN esquema de cobro declarado. Si se omite, false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean limpiarEsquemaCobro,

		@Schema(description = "Null deja el actual", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean admiteObraSocial,

		@Schema(description = "Null deja el actual", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereCasoClinico,

		@Schema(description = "Null deja el actual", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean generaRegistroClinico,

		@Schema(description = "Null deja el actual", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereProfesional,

		@Schema(description = "Null deja el actual", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereEspacio,

		@Schema(description = "Inicio de vigencia nuevo. Null deja el actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "Fin de vigencia nuevo, INCLUSIVE. Null deja el actual; para "
						+ "dejarla sin fin previsto, limpiarVigenciaHasta",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(
				description = "Dejar la oferta SIN fin de vigencia. Ignora vigenciaHasta si "
						+ "ambos vienen. Si se omite, false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean limpiarVigenciaHasta,

		@Schema(
				description = "Version que el cliente cree estar editando. Si la fila avanzo "
						+ "desde entonces, responde 409 concurrent-modification y no pisa nada",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(value = 0, message = "expectedVersion no puede ser negativo")
		Long expectedVersion) {
}
