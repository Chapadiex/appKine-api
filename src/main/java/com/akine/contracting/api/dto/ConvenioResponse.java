package com.akine.contracting.api.dto;

import com.akine.contracting.application.ConvenioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Un convenio de una sede (M16).
 *
 * <p><b>{@code estado} y {@code vigente} son dos cosas distintas y viajan las dos.</b> El estado es
 * el ciclo de vida administrativo; {@code vigente} dice si la fecha consultada cae dentro de la
 * ventana. Un convenio ACTIVO con {@code vigente = false} es el caso borde "convenio vencido" de la
 * etapa, y colapsarlos en un solo campo dejaria a la pantalla sin poder explicar por que ese
 * convenio no resuelve.
 */
@Schema(description = "Convenio de una sede con un plan de un financiador")
public record ConvenioResponse(

		@Schema(description = "Identificador del convenio", example = "140")
		long id,

		@Schema(description = "Sede a la que pertenece. No cambia nunca", example = "20")
		long consultorioId,

		@Schema(description = "Financiador con el que se negocio. No cambia", example = "31")
		long financiadorId,

		@Schema(description = "Plan de cobertura. No cambia", example = "88")
		long planId,

		@Schema(description = "Clave estable dentro de la sede. No cambia",
				example = "OSDE-210-2026")
		String codigo,

		@Schema(description = "Nombre visible", example = "OSDE 210 - 2026")
		String nombre,

		@Schema(description = "Como se pacto la prestacion", example = "POR_PRESTACION",
				allowableValues = {"POR_PRESTACION", "POR_SESION", "MODULO", "CAPITA"})
		String modalidad,

		@Schema(description = "Primer dia en que se aplica", example = "2026-01-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Ultimo dia en que se aplica, INCLUSIVE. Null = sin fin previsto")
		LocalDate vigenciaHasta,

		@Schema(description = "Si la fecha consultada cae dentro de la vigencia. DISTINTO de estado")
		boolean vigente,

		@Schema(description = "ISO 4217 de sus aranceles", example = "ARS")
		String moneda,

		@Schema(description = "Si la prestacion exige orden medica")
		boolean requiereOrden,

		@Schema(description = "Si exige autorizacion previa del financiador")
		boolean requiereAutorizacion,

		@Schema(description = "Si exige numero de credencial del paciente")
		boolean requiereCredencial,

		@Schema(description = "Tope de sesiones por mes. Null = sin tope pactado", example = "20")
		Integer limiteSesionesMensual,

		@Schema(description = "Documentacion administrativa que el financiador exige")
		String documentacionRequerida,

		@Schema(description = "Notas administrativas")
		String observaciones,

		@Schema(description = "Ciclo de vida. Un convenio INACTIVO se sigue leyendo con 200",
				example = "ACTIVO", allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version) {

	public static ConvenioResponse de(ConvenioView view) {
		return new ConvenioResponse(
				view.id(),
				view.consultorioId(),
				view.financiadorId(),
				view.planId(),
				view.codigo(),
				view.nombre(),
				view.modalidad(),
				view.vigenciaDesde(),
				view.vigenciaHasta(),
				view.vigente(),
				view.moneda(),
				view.requiereOrden(),
				view.requiereAutorizacion(),
				view.requiereCredencial(),
				view.limiteSesionesMensual(),
				view.documentacionRequerida(),
				view.observaciones(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
