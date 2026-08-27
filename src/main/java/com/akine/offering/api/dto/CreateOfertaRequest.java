package com.akine.offering.api.dto;

import com.akine.offering.domain.Modalidad;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta de una oferta: como esta sede presta un servicio del catalogo global.
 *
 * <p>Obligatorios: el servicio, el nombre comercial, la duracion y la capacidad. El resto
 * <b>hereda del servicio</b> cuando se omite —modalidad, caso clinico, registro clinico—,
 * que es lo que hace que dar de alta una oferta no obligue al administrador a contestar quince
 * preguntas para el caso normal.
 *
 * <p>La sede NO viaja en el cuerpo: viene en la ruta, y el tenant sale del contexto validado del
 * request. Nombrar la organizacion en el cuerpo dejaria que el cliente afirme una pertenencia
 * que el servidor tiene que verificar igual.
 */
@Schema(description = "Datos para dar de alta una oferta de servicio en una sede")
public record CreateOfertaRequest(

		@Schema(
				description = "Servicio del catalogo global que esta oferta presta. Tiene que "
						+ "estar VIGENTE: sobre uno dado de baja responde 409 servicio-inactivo",
				example = "12",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El servicio de la oferta es obligatorio")
		Long servicioId,

		@Schema(
				description = "Como lo llama el centro. Unico entre las ofertas VIGENTES de esa "
						+ "sede, comparado sin distinguir mayusculas ni acentos",
				example = "Kinesiologia - sesion de 45 minutos",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre comercial de la oferta es obligatorio")
		@Size(max = 160, message = "El nombre comercial no puede superar los 160 caracteres")
		String nombreComercial,

		@Schema(description = "Descripcion libre. Nunca contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(
				description = "Individual o grupal. Si se omite, hereda la del servicio",
				example = "INDIVIDUAL",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Modalidad modalidad,

		@Schema(description = "Duracion de la prestacion en minutos", example = "45",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La duracion de la oferta es obligatoria")
		@Positive(message = "La duracion tiene que ser mayor a cero")
		Integer duracionMinutos,

		@Schema(
				description = "Personas que admite a la vez. OBLIGATORIA y sin default: una oferta "
						+ "INDIVIDUAL admite 1 y hay que decirlo. RF-M27-003 no la deriva de la "
						+ "modalidad, porque un box con dos camillas puede atender de a dos en "
						+ "individual",
				example = "1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La capacidad de la oferta es obligatoria")
		@Positive(message = "La capacidad tiene que ser mayor a cero")
		Integer capacidad,

		@Schema(
				description = "Precio de lista. Omitirlo NO es cero: es que el centro todavia no "
						+ "lo fijo. Va SIEMPRE junto con moneda: los dos o ninguno",
				example = "18000.00",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@PositiveOrZero(message = "El precio no puede ser negativo")
		BigDecimal precioBase,

		@Schema(
				description = "Moneda del precio, ISO 4217. NO tiene default y no puede tenerlo: un "
						+ "importe sin moneda no significa nada, y asumir ARS convertiria en pesos el "
						+ "precio de un centro que cobra en otra cosa. Va junto con precioBase: los dos "
						+ "o ninguno, y mandar uno solo responde 400",
				example = "ARS",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 3, message = "La moneda es un codigo ISO 4217 de 3 letras")
		String moneda,

		@Schema(
				description = "Esquema de cobro declarado, texto libre acotado. Se guarda sin "
						+ "resolverse: los modulos que lo interpretarian no existen todavia",
				example = "SESION_SUELTA",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "El esquema de cobro no puede superar los 32 caracteres")
		String esquemaCobro,

		@Schema(description = "Si se puede presentar a un financiador. Si se omite, false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean admiteObraSocial,

		@Schema(description = "Si exige caso clinico abierto. Si se omite, hereda del servicio",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereCasoClinico,

		@Schema(description = "Si genera registro clinico. Si se omite, hereda del servicio",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean generaRegistroClinico,

		@Schema(description = "Si la reserva exige asignar profesional. Si se omite, false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereProfesional,

		@Schema(description = "Si la reserva exige asignar espacio. Si se omite, false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereEspacio,

		@Schema(
				description = "Primer dia en que se puede reservar, en la zona de la sede. Si se "
						+ "omite, hoy",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "Ultimo dia INCLUSIVE. Omitirlo = sin fin previsto, que es un "
						+ "estado real y no un dato faltante",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta) {
}
