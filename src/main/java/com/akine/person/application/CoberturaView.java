package com.akine.person.application;

import com.akine.person.domain.CoberturaPaciente;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Proyeccion de lectura de una cobertura del paciente.
 *
 * <p><b>Todo lo que sale de aca viene de las columnas propias de la cobertura</b>, jamas de una
 * consulta al catalogo. Ese es el punto entero de la etapa: si el nombre del plan se resolviera
 * al mostrar, renombrar el plan reescribiria la cobertura firmada el año pasado. Ver
 * {@code contracting.spi.ReferenciaDeCobertura}.
 *
 * <p>{@link #estado} y {@link #vigente} son dos cosas distintas y viajan las dos, mismo criterio
 * que {@code PlanCoberturaView}: el estado es el ciclo de vida (ACTIVA/INACTIVA) y {@code vigente}
 * dice si la fecha consultada cae dentro de la ventana.
 *
 * @param credencialVencida alerta, NO invalidez. Una credencial vencida no anula la cobertura:
 *                          RNF-M08-005 pide avisarlo, no bloquear el mostrador
 */
public record CoberturaView(
		long id,
		long personaId,
		String tipo,
		Long financiadorId,
		String financiadorCodigo,
		String financiadorNombre,
		String financiadorTipo,
		Long planId,
		String planCodigo,
		String planNombre,
		Boolean requeriaAutorizacion,
		Boolean requeriaCredencial,
		BigDecimal copago,
		String moneda,
		Instant referenciaCapturadaEl,
		String numeroAfiliado,
		LocalDate credencialVigenciaHasta,
		boolean credencialVencida,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		boolean principal,
		String observaciones,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static CoberturaView de(CoberturaPaciente cobertura, LocalDate fecha) {
		return new CoberturaView(
				cobertura.getId(),
				cobertura.getPersonaId(),
				cobertura.getTipo().name(),
				cobertura.getFinanciadorId(),
				cobertura.getFinanciadorCodigo(),
				cobertura.getFinanciadorNombre(),
				cobertura.getFinanciadorTipo(),
				cobertura.getPlanId(),
				cobertura.getPlanCodigo(),
				cobertura.getPlanNombre(),
				cobertura.getRequeriaAutorizacion(),
				cobertura.getRequeriaCredencial(),
				cobertura.getCopago(),
				cobertura.getMoneda(),
				cobertura.getReferenciaCapturadaEl(),
				cobertura.getNumeroAfiliado(),
				cobertura.getCredencialVigenciaHasta(),
				cobertura.credencialVencidaEl(fecha),
				cobertura.getVigenciaDesde(),
				cobertura.getVigenciaHasta(),
				cobertura.vigenteEl(fecha),
				cobertura.isPrincipal(),
				cobertura.getObservaciones(),
				cobertura.isActive() ? "ACTIVA" : "INACTIVA",
				cobertura.getDeletedAt(),
				cobertura.getDeactivationReason(),
				cobertura.getVersion());
	}
}
