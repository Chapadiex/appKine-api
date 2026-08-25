package com.akine.resource.api.dto;

import com.akine.resource.domain.CatalogoAlcance;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Alta de una especialidad, una practica o un nomenclador (RF-M06-001..003).
 *
 * <p>Un solo cuerpo para los tres, porque el alta es la misma operacion. {@code especialidadId}
 * es obligatorio al crear una practica y se ignora en los otros dos tipos.
 *
 * <p><b>{@code alcance} viaja en el cuerpo y no en la ruta</b>, y es la decision de diseno
 * central de este contrato: la ruta dice QUE se crea, el cuerpo dice PARA QUIEN. Dos arboles de
 * endpoints —uno de plataforma y otro de tenant— habrian duplicado el contrato entero para
 * expresar una sola diferencia que el servidor tiene que verificar de todos modos contra el rol
 * del actor.
 *
 * <p><b>Ningun componente de este record es un primitivo</b>, y no es casualidad: Jackson 3 pasa
 * {@code null} por cada componente AUSENTE de un record y {@code FAIL_ON_NULL_FOR_PRIMITIVES}
 * —activo por defecto— lo rechaza con un 400 que no nombra el campo culpable. Un solo primitivo
 * opcional vuelve ilegible cualquier request parcial.
 */
@Schema(description = "Datos para dar de alta un concepto del catalogo clinico")
public record CreateCatalogoConceptoRequest(

		@Schema(
				description = "Duenio del concepto. GLOBAL lo publica en el catalogo de "
						+ "plataforma y exige rol de plataforma; ORGANIZACION lo crea para el "
						+ "tenant del contexto. Si se omite, ORGANIZACION",
				example = "ORGANIZACION",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		CatalogoAlcance alcance,

		@Schema(
				description = "Clave estable. Es lo que los convenios y las sesiones guardan, y "
						+ "por eso NO se puede cambiar despues. Unico entre los conceptos "
						+ "VIGENTES del mismo duenio: el codigo de uno dado de baja se puede "
						+ "reusar",
				example = "KIN",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo del concepto es obligatorio")
		@Size(max = 48, message = "El codigo no puede superar los 48 caracteres")
		String codigo,

		@Schema(
				description = "Nombre visible. Unico entre los conceptos vigentes del mismo "
						+ "duenio, comparado sin distinguir mayusculas ni acentos",
				example = "Kinesiologia",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre del concepto es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(description = "Descripcion libre. Nunca contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(
				description = "Especialidad a la que pertenece. OBLIGATORIO al crear una "
						+ "practica, ignorado en los otros tipos. Una practica GLOBAL solo puede "
						+ "colgar de una especialidad GLOBAL: si no, 409 catalogo-scope-mismatch",
				example = "1",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long especialidadId,

		@Schema(
				description = "Instante UTC desde el que el concepto se puede elegir. Si se "
						+ "omite, el momento del alta. Es la VIGENCIA, distinta de la baja logica",
				example = "2026-09-01T00:00:00Z",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validFrom,

		@Schema(
				description = "Instante UTC hasta el que se puede elegir, EXCLUSIVO. Omitirlo "
						+ "significa sin fin previsto, que es lo normal",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validUntil) {
}
