package com.akine.contracting.application;

import com.akine.contracting.domain.PlanCobertura;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Proyeccion de lectura de un plan de cobertura.
 *
 * <p><b>{@link #estado} y {@link #vigente} son dos cosas distintas y las dos viajan.</b> El
 * estado es el ciclo de vida administrativo (ACTIVO/INACTIVO) y {@code vigente} dice si la fecha
 * de hoy cae dentro de la ventana. Un plan ACTIVO con vigencia vencida es el caso borde de la
 * etapa —"plan sin nuevas altas pero con pacientes vigentes"— y colapsar los dos campos en uno
 * dejaria a la pantalla sin poder explicar por que no se ofrece.
 *
 * @param vigente calculado contra la fecha que pidio el llamador, no contra un reloj implicito
 */
public record PlanCoberturaView(
		long id,
		long financiadorId,
		String codigo,
		String nombre,
		String descripcion,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		boolean requiereAutorizacion,
		boolean requiereCredencial,
		BigDecimal copago,
		String moneda,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static PlanCoberturaView de(PlanCobertura plan, LocalDate fecha) {
		return new PlanCoberturaView(
				plan.getId(),
				plan.getFinanciadorId(),
				plan.getCodigo(),
				plan.getNombre(),
				plan.getDescripcion(),
				plan.getVigenciaDesde(),
				plan.getVigenciaHasta(),
				plan.vigenteEl(fecha),
				plan.isRequiereAutorizacion(),
				plan.isRequiereCredencial(),
				plan.getCopago(),
				plan.getMoneda(),
				plan.isActive() ? "ACTIVO" : "INACTIVO",
				plan.getDeletedAt(),
				plan.getDeactivationReason(),
				plan.getVersion());
	}
}
