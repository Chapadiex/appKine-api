package com.akine.person.application;

import com.akine.person.domain.OrdenMedica;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una orden medica tal como sale del backend.
 *
 * <p>{@code vigente}, {@code vencida} y {@code diasParaVencer} se CALCULAN contra la fecha que
 * pregunta y no se guardan: es lo que hace posible RF-M17-006 sin ningun job que mueva estados y
 * sin que una orden vencida deje de ser consultable.
 *
 * <p>{@code estado} es el CICLO DE VIDA —ACTIVA o INACTIVA—, que no es lo mismo que la vigencia.
 * Una orden ACTIVA vencida es el caso normal; una INACTIVA es una que nunca debio cargarse.
 */
public record OrdenView(
		long id,
		long personaId,
		Long consultorioId,
		Long coberturaId,
		String numero,
		String profesionalEmisor,
		String matriculaEmisor,
		LocalDate fechaEmision,
		String indicacion,
		Integer sesionesPrescriptas,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		boolean vencida,
		Long diasParaVencer,
		Long adjuntoId,
		String observaciones,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static OrdenView de(OrdenMedica orden, LocalDate fecha) {
		Long dias = orden.diasParaVencer(fecha);
		return new OrdenView(
				orden.getId(),
				orden.getPersonaId(),
				orden.getConsultorioId(),
				orden.getCoberturaId(),
				orden.getNumero(),
				orden.getProfesionalEmisor(),
				orden.getMatriculaEmisor(),
				orden.getFechaEmision(),
				orden.getIndicacion(),
				orden.getSesionesPrescriptas(),
				orden.getVigenciaDesde(),
				orden.getVigenciaHasta(),
				orden.vigenteEl(fecha),
				dias != null && dias < 0,
				dias,
				orden.getAdjuntoId(),
				orden.getObservaciones(),
				orden.isActive() ? "ACTIVA" : "INACTIVA",
				orden.getDeletedAt(),
				orden.getDeactivationReason(),
				orden.getVersion());
	}
}
