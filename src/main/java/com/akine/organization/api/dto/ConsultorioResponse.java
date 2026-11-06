package com.akine.organization.api.dto;

import com.akine.organization.application.ConsultorioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Sede (consultorio) de la organizacion.
 *
 * <p>01.01 publicaba lo minimo para armar el selector de contexto (ADR-0009). AKINE-02.01 la
 * expande con la configuracion de la sede, y V140 con la direccion estructurada, la ubicacion,
 * las opciones y los datos fiscales efectivos. <b>Cada cambio es aditivo:</b> los campos
 * originales conservan su nombre, su tipo y su significado, asi que un cliente generado contra
 * un contrato anterior sigue compilando y leyendo lo mismo.
 *
 * <p>{@code estado}, {@code direccionFormateada}, {@code ubicacionPendiente} y
 * {@code datosFiscalesEfectivos} son valores DERIVADOS, no columnas. Se publican igual porque es
 * lo que la UI muestra, y calcularlos en el cliente seria repetir en TypeScript una regla de
 * negocio que el backend ya decidio.
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

		@Schema(description = "Razon social PROPIA de la sede, si se cargo. Cuenta solo con "
				+ "datosFiscalesPropios; los datos que rigen estan en datosFiscalesEfectivos",
				example = "Centro Kinesico SRL")
		String legalName,

		@Schema(description = "Identificacion fiscal PROPIA de la sede, si se cargo", example = "30-71234567-1")
		String taxId,

		@Schema(description = "Direccion FISCAL propia de la sede, si se cargo. La direccion fisica "
				+ "esta en pais, provincia, ciudad, calle, numero, codigoPostal y piso, y en una linea "
				+ "en direccionFormateada",
				example = "Av. Colon 1234, Cordoba")
		String addressLine,

		@Schema(description = "Telefono de contacto, si se cargo", example = "+54 351 555-0000")
		String phone,

		@Schema(description = "Email de contacto, si se cargo", example = "sede@ejemplo.test")
		String contactEmail,

		@Schema(description = "Nombre corto para la agenda y los listados, si se cargo", example = "Norte")
		String alias,

		@Schema(description = "Pais de la direccion fisica, ISO 3166-1 alpha-2", example = "AR")
		String pais,

		@Schema(description = "Provincia o estado", example = "Cordoba")
		String provincia,

		@Schema(description = "Ciudad o localidad", example = "Cordoba")
		String ciudad,

		@Schema(description = "Calle", example = "Av. Colon")
		String calle,

		@Schema(description = "Altura", example = "1234")
		String numero,

		@Schema(description = "Codigo postal", example = "X5000")
		String codigoPostal,

		@Schema(description = "Piso, oficina o departamento", example = "Piso 2, Of. B")
		String piso,

		@Schema(description = "Indicaciones para llegar", example = "Frente a la plaza")
		String referencias,

		@Schema(description = "Coordenadas de la sede. null si todavia no se ubico")
		UbicacionResponse ubicacion,

		@Schema(description = "La direccion fisica en una linea, armada con las partes cargadas: "
				+ "\"Calle Numero, Piso, Ciudad, Provincia, CP, Pais\". null si no hay ninguna",
				example = "Av. Colon 1234, Piso 2, Cordoba, Cordoba, X5000, AR")
		String direccionFormateada,

		@Schema(description = "true cuando hay direccion escrita pero ninguna coordenada: la sede "
				+ "todavia no esta en el mapa", example = "false")
		boolean ubicacionPendiente,

		@Schema(description = "false mientras la sede no reciba turnos: reservar, crear series o "
				+ "trasladar turnos hacia ella es 409 consultorio-sin-agenda", example = "true")
		boolean agendaHabilitada,

		@Schema(description = "Si la direccion fisica viaja en los avisos de turno al paciente",
				example = "true")
		boolean direccionPublica,

		@Schema(description = "true: factura con legalName, taxId y addressLine propios; false: con "
				+ "los de la organizacion", example = "false")
		boolean datosFiscalesPropios,

		@Schema(description = "Los datos fiscales que RIGEN para la sede, ya resueltos")
		DatosFiscalesEfectivosResponse datosFiscalesEfectivos,

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
				view.alias(),
				view.pais(),
				view.provincia(),
				view.ciudad(),
				view.calle(),
				view.numero(),
				view.codigoPostal(),
				view.piso(),
				view.referencias(),
				UbicacionResponse.from(view.ubicacion()),
				view.direccionFormateada(),
				view.ubicacionPendiente(),
				view.agendaHabilitada(),
				view.direccionPublica(),
				view.datosFiscalesPropios(),
				DatosFiscalesEfectivosResponse.from(view.datosFiscalesEfectivos()),
				view.active(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
