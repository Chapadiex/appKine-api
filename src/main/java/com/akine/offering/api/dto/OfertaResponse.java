package com.akine.offering.api.dto;

import com.akine.offering.application.OfertaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Como una sede concreta presta un servicio del catalogo global (M27).
 *
 * <p><b>{@code estado} y {@code vigenteHoy} son dos cosas distintas</b>, con la misma distincion
 * que ya hace {@code espacio} entre activo y en servicio. {@code estado} es el ciclo de vida
 * administrativo: dice si la oferta fue dada de baja. {@code vigenteHoy} dice si ADEMAS hoy cae
 * dentro de su ventana de vigencia, calculado en la zona horaria de LA SEDE y no en la del
 * servidor ni en la del navegador. Una oferta ACTIVA que arranca el mes que viene tiene
 * {@code estado=ACTIVO} y {@code vigenteHoy=false}, y es correcto que no aparezca todavia en un
 * selector de reserva.
 */
@Schema(description = "Oferta de un servicio en una sede concreta")
public record OfertaResponse(

		@Schema(description = "Identificador de la oferta", example = "34")
		long id,

		@Schema(description = "Tenant propietario de la oferta", example = "7")
		long organizationId,

		@Schema(description = "Sede que presta el servicio", example = "3")
		long consultorioId,

		@Schema(description = "Servicio del catalogo global que esta oferta presta", example = "12")
		long servicioId,

		@Schema(
				description = "Como lo llama el centro. Unico entre las ofertas VIGENTES de esa "
						+ "sede, comparado sin distinguir mayusculas ni acentos",
				example = "Kinesiologia - sesion de 45 minutos")
		String nombreComercial,

		@Schema(description = "Descripcion libre. Nunca contenido clinico")
		String descripcion,

		@Schema(description = "Individual o grupal", example = "INDIVIDUAL",
				allowableValues = {"INDIVIDUAL", "GRUPAL"})
		String modalidad,

		@Schema(description = "Duracion de la prestacion en minutos", example = "45")
		int duracionMinutos,

		@Schema(
				description = "Personas que admite a la vez. Una oferta INDIVIDUAL admite 1",
				example = "1")
		int capacidad,

		@Schema(
				description = "Precio de lista declarado. Null cuando el centro todavia no lo "
						+ "fijo, que es un estado real y no un cero",
				example = "18000.00")
		BigDecimal precioBase,

		@Schema(description = "Moneda del precio, ISO 4217", example = "ARS")
		String moneda,

		@Schema(
				description = "Esquema de cobro DECLARADO, texto libre acotado. No lo resuelve "
						+ "nadie todavia: los modulos de facturacion y cobros no existen "
						+ "(RN-M27-006). Se guarda para no perder el dato, no para ramificar",
				example = "SESION_SUELTA")
		String esquemaCobro,

		@Schema(description = "Si la oferta se puede presentar a un financiador")
		boolean admiteObraSocial,

		@Schema(description = "Si exige un caso clinico abierto para reservarse")
		boolean requiereCasoClinico,

		@Schema(description = "Si al prestarse genera registro clinico")
		boolean generaRegistroClinico,

		@Schema(description = "Si la reserva exige asignar un profesional")
		boolean requiereProfesional,

		@Schema(description = "Si la reserva exige asignar un espacio fisico")
		boolean requiereEspacio,

		@Schema(description = "Primer dia en que la oferta se puede reservar, en la zona de la sede")
		LocalDate vigenciaDesde,

		@Schema(
				description = "Ultimo dia, INCLUSIVE, en que se puede reservar. Null = sin fin "
						+ "previsto, que es un estado real y no un dato faltante")
		LocalDate vigenciaHasta,

		@Schema(
				description = "Ciclo de vida administrativo. Una oferta INACTIVA se sigue leyendo "
						+ "con 200: sus historicos tienen que seguir resolviendo",
				example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(
				description = "Si HOY cae dentro de la ventana de vigencia, en la zona horaria de "
						+ "la sede. Distinto de estado: ver la descripcion del esquema")
		boolean vigenteHoy,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(description = "Version para el control optimista", example = "0")
		long version) {

	public static OfertaResponse de(OfertaView view) {
		return new OfertaResponse(
				view.id(),
				view.organizationId(),
				view.consultorioId(),
				view.servicioId(),
				view.nombreComercial(),
				view.descripcion(),
				view.modalidad(),
				view.duracionMinutos(),
				view.capacidad(),
				view.precioBase(),
				view.moneda(),
				view.esquemaCobro(),
				view.admiteObraSocial(),
				view.requiereCasoClinico(),
				view.generaRegistroClinico(),
				view.requiereProfesional(),
				view.requiereEspacio(),
				view.vigenciaDesde(),
				view.vigenciaHasta(),
				view.estado(),
				view.vigenteHoy(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
