package com.akine.organization.api.dto;

import com.akine.organization.application.ConsultorioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Sede (consultorio) de la organizacion.
 *
 * <p>01.01 publicaba lo minimo para armar el selector de contexto (ADR-0009). AKINE-02.01 la
 * expande con la configuracion de la sede. <b>El cambio es aditivo:</b> los cuatro campos
 * originales conservan su nombre, su tipo y su significado, asi que un cliente generado contra
 * el contrato 0.4.0 sigue compilando y leyendo lo mismo.
 *
 * <p>{@code estado} es un valor DERIVADO de {@code active} y {@code deletedAt}, no una columna.
 * Se publica igual porque es lo que la UI muestra, y calcularlo en el cliente seria repetir en
 * TypeScript una regla de negocio que el backend ya decidio.
 */
@Schema(description = "Sede (consultorio) de la organizacion")
public record ConsultorioResponse(

		@Schema(description = "Identificador del consultorio", example = "1")
		long id,

		@Schema(description = "Organizacion a la que pertenece", example = "1")
		long organizationId,

		@Schema(description = "Nombre de la sede. Unico entre las sedes vigentes del tenant",
				example = "Sede Central")
		String name,

		@Schema(description = "Zona horaria IANA efectiva de la sede. Es la que rige el dia "
				+ "operativo y la agenda, no la de la organizacion",
				example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(description = "Intervalo por defecto de la agenda, en minutos", example = "30")
		int slotMinutes,

		@Schema(description = "Razon social, si se cargo", example = "Centro Kinesico SRL")
		String legalName,

		@Schema(description = "Identificacion fiscal, si se cargo", example = "30-12345678-9")
		String taxId,

		@Schema(description = "Direccion, si se cargo", example = "Av. Colon 1234, Cordoba")
		String addressLine,

		@Schema(description = "Telefono de contacto, si se cargo", example = "+54 351 555-0000")
		String phone,

		@Schema(description = "Email de contacto, si se cargo", example = "sede@ejemplo.test")
		String contactEmail,

		@Schema(description = "false cuando la sede fue dada de baja logica", example = "true")
		boolean active,

		@Schema(description = "Estado derivado de la sede: ACTIVO o INACTIVO", example = "ACTIVO")
		String estado,

		@Schema(description = "Instante UTC de la baja logica. null si la sede esta activa")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. null si la sede esta activa",
				example = "Cierre de la sucursal")
		String deactivationReason,

		@Schema(description = "Version para el control de concurrencia optimista. Hay que "
				+ "reenviarla al editar: sin ella dos ediciones simultaneas se pisan",
				example = "0")
		long version) {

	public static ConsultorioResponse from(ConsultorioView view) {
		return new ConsultorioResponse(
				view.id(),
				view.organizationId(),
				view.name(),
				view.timezone(),
				view.slotMinutes(),
				view.legalName(),
				view.taxId(),
				view.addressLine(),
				view.phone(),
				view.contactEmail(),
				view.active(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
