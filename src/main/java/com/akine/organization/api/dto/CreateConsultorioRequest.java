package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Alta de una sede adicional (RF-M03-001).
 *
 * <p>La primera sede NO se crea por aca: la crea el onboarding compuesto junto con la
 * organizacion, en una sola transaccion (ADR-0008). Este endpoint es el de las sedes 2..N.
 *
 * <p><b>Solo el nombre es obligatorio.</b> El wizard de alta tiene dos pasos y el segundo
 * —datos institucionales— es enteramente salteable: RNF-M03-005 pide interacciones compactas, y
 * ningun RF de M03 exige un CUIT ni una direccion para abrir una sede.
 *
 * <p><b>Primer box y horario general (A-8, RF-M03-002), opcionales y aditivos.</b> Si vienen se
 * crean en la misma transaccion que la sede; un cliente que no los manda sigue dando de alta la
 * sede sola, exactamente como antes.
 *
 * <p><b>Direccion fisica, ubicacion y opciones (V140), opcionales y aditivas.</b> La direccion
 * fisica es la estructurada ({@code pais}, {@code calle}, {@code numero}...); {@code addressLine}
 * pasa a ser la direccion FISCAL y cuenta solo con {@code datosFiscalesPropios}. Una sede puede
 * nacer con {@code agendaHabilitada = false} y abrir la agenda cuando termine de configurarse.
 */
@Schema(description = "Datos para dar de alta una sede adicional")
public record CreateConsultorioRequest(

		@Schema(
				description = "Nombre de la sede. Unico entre las sedes VIGENTES del tenant: el "
						+ "nombre de una sede dada de baja se puede reusar",
				example = "Sede Norte",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre de la sede es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(
				description = "Zona horaria IANA de la sede. Si se omite, se hereda la de la "
						+ "organizacion. Solo se aceptan identificadores IANA: un offset fijo "
						+ "como -03:00 no conoce el horario de verano y correria la agenda",
				example = "America/Argentina/Cordoba",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "La zona horaria no puede superar los 64 caracteres")
		String timezone,

		@Schema(
				description = "Intervalo por defecto de la agenda, en minutos. Si se omite, 30",
				example = "30",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 5, message = "El intervalo minimo es de 5 minutos")
		@Max(value = 480, message = "El intervalo maximo es de 480 minutos")
		Integer slotMinutes,

		@Schema(description = "Razon social propia de la sede. Cuenta solo con datosFiscalesPropios",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 200, message = "La razon social no puede superar los 200 caracteres")
		String legalName,

		@Schema(description = "Identificacion fiscal propia de la sede. Si tiene forma de CUIT "
				+ "(11 digitos, guiones opcionales) el digito verificador tiene que ser correcto: si no, "
				+ "400 con errors.taxId. Cualquier otro formato se acepta como texto",
				example = "30-71234567-1",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "La identificacion fiscal no puede superar los 32 caracteres")
		String taxId,

		@Schema(description = "Direccion fiscal propia de la sede, en una linea. Cuenta solo con "
				+ "datosFiscalesPropios. La direccion FISICA va en pais, provincia, ciudad, calle, "
				+ "numero, codigoPostal y piso",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 240, message = "La direccion fiscal no puede superar los 240 caracteres")
		String addressLine,

		@Schema(description = "Telefono de contacto", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String phone,

		@Schema(description = "Email de contacto", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Email(message = "El email de contacto no tiene un formato valido")
		@Size(max = 160, message = "El email no puede superar los 160 caracteres")
		String contactEmail,

		@Schema(description = "Nombre corto para la agenda y los listados", example = "Norte",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 80, message = "El alias no puede superar los 80 caracteres")
		String alias,

		@Schema(description = "Pais de la direccion fisica, ISO 3166-1 alpha-2. Se guarda en mayusculas",
				example = "AR", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Pattern(regexp = "[A-Za-z]{2}", message = "El pais tiene que ser un codigo ISO de dos letras")
		String pais,

		@Schema(description = "Provincia o estado", example = "Cordoba",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 80, message = "La provincia no puede superar los 80 caracteres")
		String provincia,

		@Schema(description = "Ciudad o localidad", example = "Cordoba",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 120, message = "La ciudad no puede superar los 120 caracteres")
		String ciudad,

		@Schema(description = "Calle", example = "Av. Colon", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "La calle no puede superar los 160 caracteres")
		String calle,

		@Schema(description = "Altura. Texto: admite S/N o 1234 bis", example = "1234",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 20, message = "El numero no puede superar los 20 caracteres")
		String numero,

		@Schema(description = "Codigo postal", example = "X5000",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 16, message = "El codigo postal no puede superar los 16 caracteres")
		String codigoPostal,

		@Schema(description = "Piso, oficina o departamento", example = "Piso 2, Of. B",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 60, message = "El piso no puede superar los 60 caracteres")
		String piso,

		@Schema(description = "Indicaciones para llegar", example = "Frente a la plaza",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 255, message = "Las referencias no pueden superar los 255 caracteres")
		String referencias,

		@Schema(description = "Coordenadas de la sede. Las dos juntas: una sola es 400 con "
				+ "errors.ubicacion. Si se omite, la sede queda con la direccion escrita y sin ubicar "
				+ "(ubicacionPendiente)",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Valid
		UbicacionRequest ubicacion,

		@Schema(description = "Si la sede ya recibe turnos. Si se omite, true. En false la sede existe "
				+ "y se configura pero reservar, crear series o trasladar turnos hacia ella es 409 "
				+ "consultorio-sin-agenda",
				example = "true", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean agendaHabilitada,

		@Schema(description = "Si la direccion fisica viaja en los avisos de turno al paciente. Si se "
				+ "omite, true",
				example = "true", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean direccionPublica,

		@Schema(description = "true: la sede factura con legalName, taxId y addressLine propios. "
				+ "false (default): con los datos fiscales de la organizacion",
				example = "false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean datosFiscalesPropios,

		@Schema(
				description = "Primer box de la sede (RF-M03-002). Si viene, se crea en la misma "
						+ "transaccion que la sede: si algo falla no queda ni la sede ni el box. "
						+ "Omitirlo da de alta la sede sola, como antes",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Valid
		AltaSedePrimerBoxRequest primerBox,

		@Schema(
				description = "Horario general de la sede (RF-M03-002): franjas semanales en la "
						+ "zona de la sede. Desde 0.74.0 (DP-19) LIMITA la agenda: ningun turno "
						+ "se ofrece ni se reserva fuera de este horario. NO sustituye la "
						+ "disponibilidad de cada profesional (RN-M03-004): la recorta. Omitido, "
						+ "no limita nada. Se crea en la misma transaccion que la sede; dos franjas "
						+ "del mismo dia que se pisan, o una que termina antes de empezar, "
						+ "rechazan el alta entera con 400. Se lee y se edita despues por "
						+ "/consultorios/{id}/calendario",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 28, message = "El horario general admite como maximo 28 franjas")
		List<@Valid AltaSedeFranjaHorariaRequest> horarioGeneral) {
}
