package com.akine.resource.api.dto;

import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.spi.MedicionTipo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Alta de una definicion de medida (RF-M14-004).
 *
 * <p><b>El alcance viaja en el cuerpo y no en la ruta</b>, igual que en el resto de M06: la ruta
 * dice QUE se crea y el cuerpo dice PARA QUIEN. Dos arboles de endpoints —uno de plataforma y
 * otro de tenant— habrian duplicado el contrato entero para expresar una diferencia que el
 * servidor tiene que verificar contra el rol del actor de todos modos.
 *
 * <p>La coherencia entre {@code tipo}, {@code unidad} y el rango <b>no se valida aca</b>: una
 * medida NUMERICA exige unidad y una de TEXTO la prohibe, y eso es una regla del modelo que la
 * entidad hace cumplir en un solo lugar. Repetirla con anotaciones daria dos definiciones de lo
 * mismo y la primera en divergir decidiria que filas entran.
 */
@Schema(description = "Datos de alta de una definicion de medida del examen fisico")
public record CreateMedicionDefinicionRequest(

		@Schema(
				description = "Duenio de la definicion. ORGANIZACION exige consultorio:manage "
						+ "sobre la sede del contexto; GLOBAL exige rol de plataforma. Si se "
						+ "omite, ORGANIZACION",
				example = "ORGANIZACION",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		CatalogoAlcance alcance,

		@Schema(
				description = "Clave estable de la medida. Es lo que queda copiado en cada "
						+ "medicion tomada, asi que no se puede cambiar despues",
				example = "ROM_RODILLA_FLEX",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo de la medida es obligatorio")
		@Size(max = 48, message = "El codigo no puede superar los 48 caracteres")
		String codigo,

		@Schema(
				description = "Nombre visible de la medida",
				example = "ROM de rodilla en flexion",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre de la medida es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(
				description = "Como se toma la medida. Queda a la vista del profesional",
				example = "Goniometro, paciente en decubito supino",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(
				description = "Que clase de valor admite. Decide en que columna vive el valor y "
						+ "es INMUTABLE: cambiarlo dejaria mediciones cuyo valor esta en una "
						+ "columna que el tipo nuevo no admite",
				example = "NUMERICO",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de la medida es obligatorio")
		MedicionTipo tipo,

		@Schema(
				description = "Unidad de la medida. OBLIGATORIA en NUMERICO y ESCALA, prohibida "
						+ "en TEXTO y BOOLEANO. Se copia en cada medicion tomada",
				example = "grados",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 24, message = "La unidad no puede superar los 24 caracteres")
		String unidad,

		@Schema(
				description = "Piso del rango admitido, INCLUSIVE. Solo en los tipos numericos. "
						+ "Se evalua AL REGISTRAR y nunca al leer",
				example = "0",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		BigDecimal minimo,

		@Schema(
				description = "Techo del rango admitido, INCLUSIVE. Solo en los tipos numericos",
				example = "160",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		BigDecimal maximo) {
}
