package com.akine.encounter.api.dto;

import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ParametroAplicado;
import com.akine.encounter.domain.TipoDatoParametro;
import com.akine.encounter.domain.TratamientoAplicado;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Una intervencion realmente aplicada en la sesion.
 *
 * <p>Sirve para registrar (POST) y para reemplazar (PUT): el reemplazo es total, con sus
 * parametros, y por eso no hay un endpoint por parametro — el enunciado de la etapa pide "evitar
 * endpoint por cada tipo si modelo polimorfico existente resuelve".
 *
 * <p><b>El {@code orden} no esta y no es un olvido.</b> Lo asigna el servidor: es la secuencia
 * <b>cronologica</b> de la visita, no una preferencia de presentacion, y dejarlo entrar desde
 * afuera permitiria intercalar una intervencion en un pasado que no ocurrio asi.
 *
 * <p>Tampoco estan el codigo y el nombre de la practica ni el del espacio: son <b>snapshots</b> y
 * salen del catalogo. Dejarlos entrar permitiria que la historia clinica afirme que se aplico una
 * practica con un nombre que esa practica nunca tuvo.
 */
@Schema(
		name = "RegistrarTratamiento",
		description = "Intervencion realmente aplicada en la atencion (RF-M14-005) con el espacio "
				+ "realmente utilizado (RF-M04-005). Planificado no equivale a realizado "
				+ "(RN-M14-004): esto NO mueve el Plan de Tratamiento.")
public record RegistrarTratamientoRequest(

		@Schema(
				description = "Practica del catalogo clinico (M06) efectivamente aplicada. Tiene "
						+ "que estar **vigente al registrar**: una dada de baja responde 409 "
						+ "`practica-no-utilizable`, y una inexistente o de otro tenant, 404. Lo "
						+ "ya guardado sigue resolviendo aunque despues se de de baja.",
				example = "41")
		@NotNull @Positive Long practicaId,

		@Schema(
				description = "Tecnica o maniobra utilizada. Opcional: no toda practica tiene una "
						+ "tecnica declarable.",
				example = "TENS convencional")
		@Size(max = 160) String tecnica,

		@Schema(description = "Zona tratada", example = "Lumbar")
		@Size(max = 120) String zona,

		@Schema(
				description = "De que lado. **Exige zona**: \"derecha\" de que. `NO_APLICA` no es "
						+ "lo mismo que dejarlo vacio — una zona central no tiene lado.",
				allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"},
				example = "BILATERAL")
		Lateralidad lateralidad,

		@Schema(
				description = "Duracion de **esta** intervencion. Opcional: no siempre se "
						+ "cronometra. La duracion total de la sesion se calcula sumando, no se "
						+ "guarda.",
				example = "20")
		@Min(1) @Max(1440) Integer duracionMinutos,

		@Schema(
				description = "Co-atencion: quien aplico esta intervencion, si no fue el "
						+ "profesional de la sesion. **Anotar que otro participo no le da permiso "
						+ "de escritura**: quien escribe sigue siendo el dueño de la atencion. "
						+ "Tiene que tener membership vigente en la sede o es 409.",
				example = "88")
		@Positive Long profesionalMembershipId,

		@Schema(
				description = "Espacio **realmente utilizado** (RF-M04-005). Puede diferir del "
						+ "reservado en el turno, que es el punto del requerimiento. **No se "
						+ "valida ocupacion**: esto registra un hecho consumado, no una reserva.",
				example = "12")
		@Positive Long espacioId,

		@Schema(description = "Observacion breve de la intervencion", example = "Tolero bien")
		@Size(max = 500) String observacion,

		@Schema(
				description = "Parametros **tipados** de la intervencion. Un parametro sin "
						+ "`tipoDato` se rechaza con 400 `parametro-invalido`: normalizar "
						+ "adivinando el tipo es como se cuela un valor de dosificacion "
						+ "interpretado al reves. Que parametros corresponden a cada practica lo "
						+ "decide la pantalla: el backend no tiene esquemas por practica.")
		@Valid List<ParametroRequest> parametros,

		@Schema(
				description = "Version de la **SESION** que el cliente leyo. Un 409 "
						+ "`concurrent-modification` significa que alguien escribio antes y hay "
						+ "que recargar. **Cada escritura de tratamiento hace avanzar esa "
						+ "version**: la respuesta trae la nueva.",
				example = "3")
		@NotNull @PositiveOrZero Long version) {

	/** Un parametro tipado. Ver {@link com.akine.encounter.domain.TipoDatoParametro}. */
	@Schema(
			name = "ParametroDeTratamiento",
			description = "Parametro tipado. El valor va en el campo que corresponde al tipo "
					+ "declarado y en ninguno mas.")
	public record ParametroRequest(

			@Schema(description = "Nombre del parametro", example = "intensidad")
			@NotBlank @Size(max = 64) String clave,

			@Schema(
					description = "Tipo del valor. **Obligatorio**: un parametro sin tipo no se "
							+ "puede interpretar y se rechaza.",
					allowableValues = {"NUMERICO", "TEXTO", "BOOLEANO"},
					example = "NUMERICO")
			@NotNull TipoDatoParametro tipoDato,

			@Schema(description = "Valor cuando el tipo es NUMERICO", example = "25.5")
			BigDecimal valorNumerico,

			@Schema(description = "Valor cuando el tipo es TEXTO", example = "Deslizamiento")
			@Size(max = 280) String valorTexto,

			@Schema(description = "Valor cuando el tipo es BOOLEANO", example = "true")
			Boolean valorBooleano,

			@Schema(
					description = "Unidad del valor numerico. Opcional y **solo** para NUMERICO: "
							+ "hay numericos adimensionales, como \"3 series\". Una unidad sobre "
							+ "un texto es 400: \"mA\" de que.",
					example = "mA")
			@Size(max = 24) String unidad) {

		ParametroAplicado aDominio() {
			return new ParametroAplicado(
					clave, tipoDato, valorNumerico, valorTexto, valorBooleano, unidad);
		}
	}

	public TratamientoAplicado aDominio() {
		return new TratamientoAplicado(
				practicaId,
				tecnica,
				zona,
				lateralidad,
				duracionMinutos,
				profesionalMembershipId,
				espacioId,
				observacion,
				parametros == null
						? List.of()
						: parametros.stream().map(ParametroRequest::aDominio).toList());
	}
}
