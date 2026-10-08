package com.akine.person.api.dto;

import com.akine.person.application.OrdenView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una orden medica tal como sale de la API.
 *
 * <p>{@code vigente}, {@code vencida} y {@code diasParaVencer} se <b>calculan</b> contra la fecha
 * que se pregunta y no se guardan. Es lo que hace posible la alerta de vencimiento de RF-M17-006
 * sin ningun job que mueva estados, y lo que garantiza que una orden vencida siga siendo
 * consultable con la fila intacta.
 *
 * <p>{@code estado} es el CICLO DE VIDA y no la vigencia. Una ACTIVA vencida es el caso normal.
 */
@Schema(description = "Orden medica de un paciente")
public record OrdenResponse(

		@Schema(description = "Identificador de la orden", example = "51")
		long id,

		@Schema(description = "Paciente al que se le prescribio", example = "1204")
		long personaId,

		@Schema(description = "Sede desde la que se cargo. Dato de auditoria, no de propiedad",
				example = "7")
		Long consultorioId,

		@Schema(description = "Cobertura para la que se presento. Null = vale para cualquiera",
				example = "412")
		Long coberturaId,

		@Schema(description = "Numero impreso en la orden", example = "OM-2026-004512")
		String numero,

		@Schema(description = "Profesional que firma", example = "Dra. Sintetica Prueba")
		String profesionalEmisor,

		@Schema(description = "Matricula del emisor", example = "MP 12345")
		String matriculaEmisor,

		@Schema(description = "Fecha que la orden declara", example = "2026-09-01")
		LocalDate fechaEmision,

		@Schema(description = "Transcripcion administrativa. NO es registro clinico")
		String indicacion,

		@Schema(description = "Sesiones indicadas. Informativa", example = "10")
		Integer sesionesPrescriptas,

		@Schema(description = "Primer dia en que se puede presentar", example = "2026-09-01")
		LocalDate vigenciaDesde,

		@Schema(description = "ULTIMO dia, INCLUSIVE. Null = sin vencimiento",
				example = "2026-12-31")
		LocalDate vigenciaHasta,

		@Schema(description = "Se puede presentar la fecha consultada")
		boolean vigente,

		@Schema(description = "Ya paso su fin de vigencia. Vencida NO es dada de baja: la orden "
				+ "sigue siendo consultable y editable")
		boolean vencida,

		@Schema(description = "Dias que faltan para el vencimiento. Negativo si ya vencio, null si "
				+ "no vence. Es la alerta de RF-M17-006, calculada al leer", example = "45")
		Long diasParaVencer,

		@Schema(description = "Adjunto vinculado con el escaneo, si se cargo", example = "907")
		Long adjuntoId,

		@Schema(description = "Notas administrativas")
		String observaciones,

		@Schema(description = "Ciclo de vida: ACTIVA o INACTIVA. NO es la vigencia",
				example = "ACTIVA", allowableValues = {"ACTIVA", "INACTIVA"})
		String estado,

		@Schema(description = "Instante de la baja logica")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja")
		String deactivationReason,

		@Schema(description = "Version para el control optimista", example = "0")
		long version,

		@Schema(description = "Estado de la orden DERIVADO de su vigencia y de las "
				+ "autorizaciones activas que la usan, calculado contra la fecha consultada. No "
				+ "se guarda. Precedencia: ANULADA (dada de baja), CUMPLIDA (las sesiones "
				+ "prescriptas ya se consumieron), VENCIDA, EN_CURSO (hay consumo), AUTORIZADA "
				+ "(alguna APROBADA), EN_TRAMITE (alguna PENDIENTE u OBSERVADA), RECHAZADA (todas "
				+ "rechazadas), SIN_AUTORIZACION (ninguna la usa; normal si la prestacion no exige "
				+ "autorizacion)",
				example = "EN_CURSO",
				allowableValues = {"ANULADA", "CUMPLIDA", "VENCIDA", "EN_CURSO", "AUTORIZADA",
						"EN_TRAMITE", "RECHAZADA", "SIN_AUTORIZACION"})
		String situacion,

		@Schema(description = "Sesiones consumidas en las autorizaciones activas y APROBADAS "
				+ "que usan esta orden", example = "3")
		int sesionesConsumidas) {

	public static OrdenResponse de(OrdenView vista) {
		return new OrdenResponse(
				vista.id(),
				vista.personaId(),
				vista.consultorioId(),
				vista.coberturaId(),
				vista.numero(),
				vista.profesionalEmisor(),
				vista.matriculaEmisor(),
				vista.fechaEmision(),
				vista.indicacion(),
				vista.sesionesPrescriptas(),
				vista.vigenciaDesde(),
				vista.vigenciaHasta(),
				vista.vigente(),
				vista.vencida(),
				vista.diasParaVencer(),
				vista.adjuntoId(),
				vista.observaciones(),
				vista.estado(),
				vista.deletedAt(),
				vista.deactivationReason(),
				vista.version(),
				vista.situacion(),
				vista.sesionesConsumidas());
	}
}
