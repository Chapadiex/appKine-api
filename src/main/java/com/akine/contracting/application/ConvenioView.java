package com.akine.contracting.application;

import com.akine.contracting.domain.Convenio;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Un convenio tal como sale de la capa de aplicacion.
 *
 * <p><b>{@code estado} y {@code vigente} son dos cosas distintas y viajan las dos.</b> El estado es
 * el ciclo de vida administrativo; {@code vigente} dice si la fecha consultada cae dentro de la
 * ventana. Un convenio ACTIVO con {@code vigente = false} —el caso borde "convenio vencido" de la
 * etapa— es un estado real, y colapsarlos dejaria a la pantalla sin poder explicar por que ese
 * convenio no resuelve. Misma decision que 03.03 tomo para {@code PlanCoberturaView}.
 */
public record ConvenioView(
		long id,
		long consultorioId,
		long financiadorId,
		long planId,
		String codigo,
		String nombre,
		String modalidad,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		String moneda,
		boolean requiereOrden,
		boolean requiereAutorizacion,
		boolean requiereCredencial,
		Integer limiteSesionesMensual,
		String documentacionRequerida,
		String observaciones,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	/** {@code fecha} es el dia contra el que se calcula {@code vigente}, nunca un reloj implicito. */
	public static ConvenioView de(Convenio convenio, LocalDate fecha) {
		return new ConvenioView(
				convenio.getId(),
				convenio.getConsultorioId(),
				convenio.getFinanciadorId(),
				convenio.getPlanId(),
				convenio.getCodigo(),
				convenio.getNombre(),
				convenio.getModalidad().name(),
				convenio.getVigenciaDesde(),
				convenio.getVigenciaHasta(),
				convenio.vigencia().cubre(fecha),
				convenio.getMoneda(),
				convenio.isRequiereOrden(),
				convenio.isRequiereAutorizacion(),
				convenio.isRequiereCredencial(),
				convenio.getLimiteSesionesMensual(),
				convenio.getDocumentacionRequerida(),
				convenio.getObservaciones(),
				convenio.isActive() ? "ACTIVO" : "INACTIVO",
				convenio.getDeletedAt(),
				convenio.getDeactivationReason(),
				convenio.getVersion());
	}
}
