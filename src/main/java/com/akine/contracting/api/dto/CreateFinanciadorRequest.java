package com.akine.contracting.api.dto;

import com.akine.contracting.domain.TipoFinanciador;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Alta de un financiador en el catalogo de la organizacion.
 *
 * <p><b>Sin {@code Idempotency-Key}</b>, con el mismo criterio que el catalogo de servicios: un
 * financiador no consume cupo de ningun plan, asi que lo unico que un reintento podria producir
 * es una fila duplicada, y contra eso el unique de codigo es una garantia mas fuerte que una
 * clave que depende de que el cliente la mande bien. El reintento responde 409 y no crea nada.
 */
@Schema(description = "Datos para dar de alta un financiador")
public record CreateFinanciadorRequest(

		@Schema(
				description = "Clave estable con la que las coberturas y convenios lo referencian. "
						+ "NO se puede cambiar despues. Unico entre los financiadores VIGENTES de "
						+ "la organizacion: el codigo de uno dado de baja si se puede reusar",
				example = "OSDE",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo del financiador es obligatorio")
		@Size(max = 64, message = "El codigo no puede superar los 64 caracteres")
		String codigo,

		@Schema(
				description = "Razon social o nombre visible. Unico entre los vigentes de la "
						+ "organizacion. SI se puede cambiar: renombrar es esto",
				example = "OSDE Binario",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre del financiador es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(
				description = "Que clase de financiador es. CLASIFICA y no habilita: ninguna regla "
						+ "del sistema ramifica por este valor. PARTICULAR no esta en la lista a "
						+ "proposito, es una modalidad y no un financiador (RN-M15-004)",
				example = "PREPAGA",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de financiador es obligatorio")
		TipoFinanciador tipo,

		@Schema(
				description = "CUIT. Se normaliza a 11 digitos: los guiones y los puntos se "
						+ "descartan antes de guardar y de comparar, asi que 30-71234567-8 y "
						+ "30712345678 son el mismo. Unico entre los vigentes cuando esta "
						+ "presente; varios financiadores SIN CUIT conviven sin chocar",
				example = "30712345678",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 20, message = "El CUIT no puede superar los 20 caracteres")
		String cuit,

		@Schema(description = "Contacto administrativo del financiador. Nunca de un paciente",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Email(message = "El email de contacto no tiene un formato valido")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres")
		String emailContacto,

		@Schema(description = "Telefono administrativo del financiador",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String telefonoContacto,

		@Schema(description = "Notas administrativas. NUNCA contenido clinico ni datos de pacientes",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones) {
}
