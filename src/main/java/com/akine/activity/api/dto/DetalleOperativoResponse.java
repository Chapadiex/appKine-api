package com.akine.activity.api.dto;

import com.akine.activity.application.DetalleOperativoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * El detalle operativo de una clase: cabecera mas una pagina de participantes (RF-M28-009).
 *
 * <p><b>Lo que NO lleva es la mitad del punto</b> (RNF-M28-002): ni un dato clinico, ni cobertura,
 * ni estado de deuda, ni pase, ni abono. Lo clinico no esta porque quien toma lista no puede ver a
 * que se atiende nadie; lo economico no esta porque todavia no existe.
 */
@Schema(
		name = "DetalleOperativoDeClase",
		description = "Cabecera y listado compacto y paginado (CA-M28-009-06). Separa indicadores "
				+ "operativos de datos clinicos y economicos: aca no hay ninguno de los dos.")
public record DetalleOperativoResponse(

		@Schema(example = "77") long claseId,
		@Schema(example = "42") long ofertaId,
		@Schema(description = "Rotulo de esta ocurrencia") String titulo,
		Instant inicio,
		Instant fin,

		@Schema(description = "PROGRAMADA, EN_CURSO, REALIZADA o CANCELADA", example = "EN_CURSO")
		String estado,

		Instant iniciadaEn,
		Instant cerradaEn,
		Long profesionalMembershipId,

		@Schema(example = "8") int capacidad,
		@Schema(example = "8") int capacidadEfectiva,
		@Schema(example = "6") int ocupados,
		@Schema(example = "2") int enEspera,
		@Schema(example = "4") int presentes,
		@Schema(example = "1") int ausentes,

		@Schema(
				description = "Cuantos tienen lugar y todavia no tienen resultado. Es lo que el "
						+ "cierre va a marcar como ausentes.",
				example = "1")
		int sinResolver,

		List<ParticipanteOperativoResponse> content,

		@Schema(example = "0") int page,
		@Schema(example = "20") int size,
		@Schema(example = "8") long totalElements,
		@Schema(example = "1") int totalPages) {

	public static DetalleOperativoResponse de(DetalleOperativoView view) {
		int totalPaginas = view.size() <= 0
				? 0
				: (int) Math.ceil((double) view.totalElements() / view.size());
		return new DetalleOperativoResponse(
				view.claseId(), view.ofertaId(), view.titulo(), view.inicio(), view.fin(),
				view.estado(), view.iniciadaEn(), view.cerradaEn(),
				view.profesionalMembershipId(), view.capacidad(), view.capacidadEfectiva(),
				view.ocupados(), view.enEspera(), view.presentes(), view.ausentes(),
				view.sinResolver(),
				view.contenido().stream().map(ParticipanteOperativoResponse::de).toList(),
				view.page(), view.size(), view.totalElements(), totalPaginas);
	}
}
