package com.akine.encounter.api.dto;

import com.akine.encounter.domain.ContenidoDeSesion;
import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Tolerancia;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * La enmienda de una sesion cerrada (RF-M14-010).
 *
 * <h2>Lo que este record NO tiene es la mitad del diseño</h2>
 *
 * <p>No hay {@code asistencia}, no hay {@code numeroSesion}, no hay {@code numeroEnCaso} y no hay
 * {@code modo}. No se rechazan con una validacion: <b>no se pueden expresar</b>. Un servicio
 * disciplinado que valide "este campo no se toca" funciona hasta que alguien agrega un camino de
 * escritura; un tipo que no tiene el campo funciona siempre.
 *
 * <h2>El motivo es obligatorio, y se valida dos veces a proposito</h2>
 *
 * <p>Aca con {@code @NotBlank}, que da un 400 {@code validation-error} con el campo señalado, y
 * otra vez en el dominio, que da un 400 {@code enmienda-sin-motivo}. La de aca es comodidad para
 * el formulario; la del dominio es la que vale, porque ningun camino de escritura —ni uno futuro
 * que no pase por este endpoint— puede construir una enmienda sin motivo.
 *
 * <p>RN-M14-006 no prohibe corregir una sesion cerrada: prohibe corregirla <b>silenciosamente</b>.
 * Sin motivo, una enmienda es indistinguible de una correccion de tipeo.
 */
@Schema(
		name = "EnmendarSesion",
		description = "Correccion de una sesion cerrada. Escribe una version nueva; la anterior "
				+ "queda intacta y consultable. **No puede cambiar la asistencia ni los "
				+ "correlativos**: eso no es una enmienda clinica.")
public record EnmendarSesionRequest(

		@Schema(description = "Lo que trae al paciente, en palabras del profesional. No es un diagnostico",
				example = "Dolor lumbar al agacharse, desde hace dos semanas")
		@Size(max = 500) String motivoClinico,

		@Schema(description = "Escala visual analogica, 0 a 10", example = "4")
		@Min(0) @Max(10) Integer dolorEva,

		@Schema(description = "Zona corporal referida", example = "Lumbar")
		@Size(max = 120) String dolorZona,

		@Schema(description = "De que lado. **Exige zona**: \"derecha\" de que",
				allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"},
				example = "DERECHA")
		Lateralidad dolorLateralidad,

		@Schema(description = "Como venia respecto de la sesion anterior",
				allowableValues = {"MEJOR", "IGUAL", "PEOR", "SIN_REFERENCIA"},
				example = "MEJOR")
		Evolucion evolucion,

		@Schema(description = "Que se buscaba en esa sesion", example = "Reducir dolor en flexion")
		@Size(max = 500) String objetivoSesion,

		@Schema(description = "Que no podia hacer el paciente", example = "No podia atarse los cordones")
		@Size(max = 500) String limitacionFuncional,

		@Schema(
				description = "Que se hizo. **Sigue siendo obligatorio si el paciente asistio**: "
						+ "una enmienda no puede vaciar la nota de cierre de una prestacion que "
						+ "ocurrio, porque dejaria el registro sin nada que diga que se hizo.",
				example = "Terapia manual lumbar, ejercicios de estabilizacion, 30 minutos")
		@Size(max = 2000) String notaDeCierre,

		@Schema(description = "Como respondio a lo realizado", example = "Alivio inmediato del dolor")
		@Size(max = 500) String respuestaTratamiento,

		@Schema(description = "Como tolero lo que se le hizo",
				allowableValues = {"BUENA", "REGULAR", "MALA"}, example = "BUENA")
		Tolerancia tolerancia,

		@Schema(description = "Lo que el paciente se llevo", example = "Repetir el ejercicio 2 veces por dia")
		@Size(max = 1000) String indicaciones,

		@Schema(description = "Que seguia",
				allowableValues = {"CONTINUA", "ALTA", "DERIVA", "REEVALUA"}, example = "CONTINUA")
		ProximaConducta proximaConducta,

		@Schema(
				description = "**Por que se corrige. Obligatorio.** Sin esto una enmienda es "
						+ "indistinguible de una correccion de tipeo y el historial deja de servir "
						+ "para lo unico que sirve, que es entender por que cambio el registro "
						+ "(RN-M14-006).",
				example = "Se corrigio la lateralidad: el dolor era del lado derecho",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la enmienda es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo,

		@Schema(
				description = "Version de la SESION que el cliente leyo, para el bloqueo optimista. "
						+ "Si alguien la enmendo en el medio, esto responde 409 en vez de apilar "
						+ "una version sobre un contenido que el autor nunca vio. **No es "
						+ "`ultimoNumeroVersion`.**",
				example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull @PositiveOrZero Long version) {

	/**
	 * El contenido enmendado, sin la asistencia ni el modo: los pone la sesion.
	 *
	 * <p>Es <b>reemplazo completo, no parche</b>. Un campo ausente significa "queda vacio", no
	 * "dejalo como estaba": distinguir las dos cosas sobre campos que son legitimamente nulos
	 * —toda la evaluacion base lo es— no se puede expresar en JSON sin inventar un centinela. La
	 * pantalla manda el formulario completo, que es lo que ya hace al evaluar.
	 */
	public ContenidoDeSesion aDominio() {
		return new ContenidoDeSesion(
				motivoClinico, dolorEva, dolorZona, dolorLateralidad, evolucion, objetivoSesion,
				limitacionFuncional, notaDeCierre, respuestaTratamiento, tolerancia, indicaciones,
				proximaConducta);
	}
}
