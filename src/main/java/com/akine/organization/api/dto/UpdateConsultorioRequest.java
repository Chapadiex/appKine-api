package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Edicion de una sede (RF-M03-003).
 *
 * <p>Semantica de PATCH: <b>cada campo omitido deja lo que estaba</b>. Para borrar un dato
 * institucional se manda cadena vacia. Un PUT obligaria al cliente a reenviar la sede entera y
 * convertiria cualquier campo que el frontend todavia no conozca en un borrado accidental.
 *
 * <p>{@code version} es obligatoria: es lo que impide que dos ediciones simultaneas se pisen. Si
 * quedo vieja, la respuesta es 409 {@code concurrent-modification} y el cliente recarga.
 *
 * <p><b>Cambiar la zona horaria no reinterpreta el pasado.</b> Los instantes ya registrados son
 * UTC y no se mueven; cambia como se proyectan de aca en adelante. La pantalla tiene que decirlo
 * con esas palabras.
 *
 * <p><b>La ubicacion tiene tres formas</b> (V140): omitir {@code ubicacion} la deja; mandarla con
 * latitud y longitud la fija; mandarla con las dos en {@code null} la borra. Una sola es 400.
 */
@Schema(description = "Cambios a aplicar sobre una sede. Los campos omitidos no se tocan")
public record UpdateConsultorioRequest(

		@Schema(description = "Nuevo nombre. Unico entre las sedes vigentes del tenant",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(description = "Nueva zona horaria IANA de la sede",
				example = "America/Argentina/Cordoba",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "La zona horaria no puede superar los 64 caracteres")
		String timezone,

		@Schema(description = "Nuevo intervalo por defecto de la agenda, en minutos",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 5, message = "El intervalo minimo es de 5 minutos")
		@Max(value = 480, message = "El intervalo maximo es de 480 minutos")
		Integer slotMinutes,

		@Schema(description = "Razon social propia de la sede. Cadena vacia la borra. Cuenta solo con "
				+ "datosFiscalesPropios",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 200, message = "La razon social no puede superar los 200 caracteres")
		String legalName,

		@Schema(description = "Identificacion fiscal propia de la sede. Cadena vacia la borra. Si tiene "
				+ "forma de CUIT (11 digitos, guiones opcionales) el digito verificador tiene que ser "
				+ "correcto: si no, 400 con errors.taxId. Cualquier otro formato se acepta como texto",
				example = "30-71234567-1",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "La identificacion fiscal no puede superar los 32 caracteres")
		String taxId,

		@Schema(description = "Direccion fiscal propia de la sede, en una linea. Cadena vacia la borra. "
				+ "La direccion FISICA va en pais, provincia, ciudad, calle, numero, codigoPostal y piso",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 240, message = "La direccion fiscal no puede superar los 240 caracteres")
		String addressLine,

		@Schema(description = "Telefono. Cadena vacia lo borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String phone,

		@Schema(description = "Email de contacto. Cadena vacia lo borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Email(message = "El email de contacto no tiene un formato valido")
		@Size(max = 160, message = "El email no puede superar los 160 caracteres")
		String contactEmail,

		@Schema(description = "Nombre corto. Cadena vacia lo borra", example = "Norte",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 80, message = "El alias no puede superar los 80 caracteres")
		String alias,

		@Schema(description = "Pais de la direccion fisica, ISO 3166-1 alpha-2. Cadena vacia lo borra",
				example = "AR", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Pattern(regexp = "|[A-Za-z]{2}", message = "El pais tiene que ser un codigo ISO de dos letras")
		String pais,

		@Schema(description = "Provincia o estado. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 80, message = "La provincia no puede superar los 80 caracteres")
		String provincia,

		@Schema(description = "Ciudad o localidad. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 120, message = "La ciudad no puede superar los 120 caracteres")
		String ciudad,

		@Schema(description = "Calle. Cadena vacia la borra", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "La calle no puede superar los 160 caracteres")
		String calle,

		@Schema(description = "Altura. Cadena vacia la borra", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 20, message = "El numero no puede superar los 20 caracteres")
		String numero,

		@Schema(description = "Codigo postal. Cadena vacia lo borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 16, message = "El codigo postal no puede superar los 16 caracteres")
		String codigoPostal,

		@Schema(description = "Piso, oficina o departamento. Cadena vacia lo borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 60, message = "El piso no puede superar los 60 caracteres")
		String piso,

		@Schema(description = "Indicaciones para llegar. Cadena vacia las borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 255, message = "Las referencias no pueden superar los 255 caracteres")
		String referencias,

		@Schema(description = "Coordenadas. Omitido: no se toca. Con latitud y longitud: se fija. Con "
				+ "las dos en null ({\"latitud\":null,\"longitud\":null}): se borra. Una sola es 400 con "
				+ "errors.ubicacion",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Valid
		UbicacionRequest ubicacion,

		@Schema(description = "Si la sede recibe turnos. Omitido: no se toca. En false reservar, crear "
				+ "series o trasladar turnos hacia ella es 409 consultorio-sin-agenda; los turnos ya "
				+ "dados no se tocan",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean agendaHabilitada,

		@Schema(description = "Si la direccion fisica viaja en los avisos de turno. Omitido: no se toca",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean direccionPublica,

		@Schema(description = "true: factura con los datos fiscales propios; false: con los de la "
				+ "organizacion. Omitido: no se toca",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean datosFiscalesPropios,

		@Schema(
				description = "Version leida del recurso. Si quedo vieja, la edicion se rechaza "
						+ "con 409 en vez de pisar el cambio de otro",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@PositiveOrZero(message = "La version no puede ser negativa")
		long version) {
}
