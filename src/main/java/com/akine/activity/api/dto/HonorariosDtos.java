package com.akine.activity.api.dto;

import com.akine.activity.application.HonorariosViews.BorradorDeHonorariosView;
import com.akine.activity.application.HonorariosViews.ClaseExcluidaView;
import com.akine.activity.application.HonorariosViews.ConfirmarLiquidacionCommand;
import com.akine.activity.application.HonorariosViews.InstructorAdicionalView;
import com.akine.activity.application.HonorariosViews.InstructoresDeClaseView;
import com.akine.activity.application.HonorariosViews.LineaDeHonorarioView;
import com.akine.activity.application.HonorariosViews.LiquidacionDeHonorariosView;
import com.akine.activity.application.HonorariosViews.ResumenDeHonorariosView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Cuerpos de los honorarios por clase y de los instructores de una clase (AKINE-08.09b, DP-38). */
public final class HonorariosDtos {

	private HonorariosDtos() {
	}

	// =================================================================================
	// Instructores
	// =================================================================================

	@Schema(name = "InstructoresDeClase",
			description = "Los instructores de una clase. El orden estable del reparto de honorarios es: "
					+ "el principal y despues los adicionales por membership ascendente.")
	public record InstructoresDeClaseResponse(
			@Schema(example = "501") long claseId,
			@Schema(description = "Membership del profesional principal (el de la clase). Null si la "
					+ "oferta no exige profesional", example = "31")
			Long principalMembershipId,
			@Schema(description = "Nombre actual del principal. Null si no resuelve")
			String principalNombre,
			@Schema(description = "Instructores adicionales vigentes, por membership ascendente")
			List<InstructorAdicionalResponse> adicionales) {

		public static InstructoresDeClaseResponse de(InstructoresDeClaseView v) {
			return new InstructoresDeClaseResponse(v.claseId(), v.principalMembershipId(),
					v.principalNombre(), v.adicionales().stream().map(InstructorAdicionalResponse::de).toList());
		}
	}

	@Schema(name = "InstructorAdicional", description = "Un instructor adicional de una clase")
	public record InstructorAdicionalResponse(
			@Schema(example = "44") long membershipId,
			@Schema(description = "Nombre actual. Null si no resuelve") String nombre,
			Instant agregadoEn) {

		static InstructorAdicionalResponse de(InstructorAdicionalView v) {
			return new InstructorAdicionalResponse(v.membershipId(), v.nombre(), v.agregadoEn());
		}
	}

	@Schema(name = "ReemplazarInstructoresDeClaseRequest",
			description = "La lista completa de instructores ADICIONALES. Vacia los quita a todos")
	public record ReemplazarInstructoresRequest(
			@Schema(description = "Memberships de los adicionales, sin repetir y sin el principal",
					example = "[44, 45]", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "La lista de instructores es obligatoria (vacia para quitarlos)")
			@Size(max = 10, message = "Una clase admite hasta 10 instructores adicionales")
			List<Long> adicionales) {
	}

	// =================================================================================
	// Borrador y liquidacion
	// =================================================================================

	@Schema(name = "LineaDeHonorario",
			description = "Una clase que entra en la liquidacion, con todo lo que explica su importe")
	public record LineaDeHonorarioResponse(
			@Schema(example = "501") long claseId,
			Instant inicio,
			@Schema(description = "Dia local de la clase en la sede: el que eligio la tarifa", example = "2026-10-07")
			LocalDate fecha,
			String titulo,
			@Schema(example = "34") long ofertaId,
			@Schema(example = "Pilates reformer") String ofertaNombre,
			@Schema(description = "PRESENTE + PRESENTE_TARDE", example = "7") int presentes,
			@Schema(description = "La tarifa aplicada", example = "12") long honorarioId,
			@Schema(example = "1500.00") BigDecimal importePorParticipante,
			@Schema(description = "importePorParticipante x presentes", example = "10500.00") BigDecimal importeClase,
			@Schema(description = "Cuantos instructores se reparten la clase", example = "2") int instructores,
			@Schema(description = "1 = el primero del orden estable: se lleva el centavo sobrante", example = "1")
			int posicion,
			@Schema(description = "La parte de este colaborador", example = "5250.00") BigDecimal importeColaborador,
			@Schema(example = "ARS") String moneda) {

		static LineaDeHonorarioResponse de(LineaDeHonorarioView v) {
			return new LineaDeHonorarioResponse(v.claseId(), v.inicio(), v.fecha(), v.titulo(), v.ofertaId(),
					v.ofertaNombre(), v.presentes(), v.honorarioId(), v.importePorParticipante(),
					v.importeClase(), v.instructores(), v.posicion(), v.importeColaborador(), v.moneda());
		}
	}

	@Schema(name = "ClaseExcluidaDeHonorarios", description = "Una clase dictada que no entra en el borrador")
	public record ClaseExcluidaResponse(
			@Schema(example = "502") long claseId,
			Instant inicio,
			LocalDate fecha,
			String titulo,
			@Schema(example = "34") long ofertaId,
			String ofertaNombre,
			@Schema(description = "Por que no entra", allowableValues = {
					"YA_LIQUIDADA", "SIN_HONORARIO", "SIN_PRESENTES", "SIN_IMPORTE"})
			String motivo,
			@Schema(description = "Con YA_LIQUIDADA, el egreso que la tiene. Null en los demas", example = "5501")
			Long egresoId) {

		static ClaseExcluidaResponse de(ClaseExcluidaView v) {
			return new ClaseExcluidaResponse(v.claseId(), v.inicio(), v.fecha(), v.titulo(), v.ofertaId(),
					v.ofertaNombre(), v.motivo(), v.egresoId());
		}
	}

	@Schema(name = "BorradorDeHonorarios",
			description = "El borrador de honorarios por clase de un colaborador en un periodo. Se calcula al "
					+ "pedirlo y no se guarda")
	public record BorradorDeHonorariosResponse(
			@Schema(example = "7") long consultorioId,
			@Schema(example = "31") long colaboradorMembershipId,
			LocalDate desde,
			LocalDate hasta,
			@Schema(description = "Suma de importeColaborador de las clases", example = "5250.00")
			BigDecimal importeTotal,
			@Schema(description = "Null si no entra ninguna clase", example = "ARS")
			String moneda,
			List<LineaDeHonorarioResponse> clases,
			List<ClaseExcluidaResponse> excluidas,
			@Schema(description = "Los numeros de la tira del borrador (DP-45)")
			ResumenDeHonorariosResponse resumen) {

		public static BorradorDeHonorariosResponse de(BorradorDeHonorariosView v) {
			return new BorradorDeHonorariosResponse(v.consultorioId(), v.colaboradorMembershipId(), v.desde(),
					v.hasta(), v.importeTotal(), v.moneda(),
					v.clases().stream().map(LineaDeHonorarioResponse::de).toList(),
					v.excluidas().stream().map(ClaseExcluidaResponse::de).toList(),
					ResumenDeHonorariosResponse.de(v.resumen()));
		}
	}

	@Schema(name = "ConfirmarLiquidacionDeHonorariosRequest",
			description = "Confirma el borrador que se miro. claseIds e importeTotal son los del borrador: si el "
					+ "recalculo da otra cosa, 409 borrador-de-honorarios-desactualizado")
	public record ConfirmarLiquidacionRequest(
			@Schema(example = "31", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "El colaborador es obligatorio")
			Long colaboradorMembershipId,

			@Schema(example = "2026-10-01", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "desde es obligatorio")
			LocalDate desde,

			@Schema(example = "2026-10-31", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "hasta es obligatorio")
			LocalDate hasta,

			@Schema(description = "Las clases del borrador que se miro", example = "[501, 503]",
					requiredMode = Schema.RequiredMode.REQUIRED)
			@NotEmpty(message = "La liquidacion necesita las clases del borrador")
			List<Long> claseIds,

			@Schema(description = "El total del borrador que se miro", example = "5250.00",
					requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "El total del borrador es obligatorio")
			@DecimalMin(value = "0.01", message = "El total tiene que ser mayor que cero")
			@Digits(integer = 10, fraction = 2, message = "El total admite 2 decimales")
			BigDecimal importeTotal,

			@Schema(description = "Concepto del egreso. Por defecto: Honorarios por clase del <desde> al <hasta>")
			@Size(max = 280, message = "El concepto admite hasta 280 caracteres")
			String concepto,

			@Schema(description = "Comprobante del profesional, si ya lo hay. Confirmar el egreso lo exige")
			@Size(max = 24, message = "El tipo de comprobante admite hasta 24 caracteres")
			String comprobanteTipo,

			@Size(max = 40, message = "El numero de comprobante admite hasta 40 caracteres")
			String comprobanteNumero,

			LocalDate comprobanteFecha,

			@Schema(description = "Clave de idempotencia: un reintento con la misma clave y el mismo pedido "
					+ "devuelve la misma liquidacion")
			@Size(max = 80, message = "La clave de idempotencia admite hasta 80 caracteres")
			String idempotencyKey,

			@Schema(description = "Oferta a liquidar. Una liquidacion es de UNA oferta: su egreso se imputa a "
					+ "ella (DP-39). Opcional si el borrador tiene una sola; si mezcla, 400 y se confirma por "
					+ "oferta", example = "34")
			Long ofertaId) {

		public ConfirmarLiquidacionCommand aDominio() {
			return new ConfirmarLiquidacionCommand(colaboradorMembershipId, desde, hasta, List.copyOf(claseIds),
					importeTotal, concepto, comprobanteTipo, comprobanteNumero, comprobanteFecha, idempotencyKey,
					ofertaId);
		}
	}

	@Schema(name = "LiquidacionDeHonorarios",
			description = "Una liquidacion de honorarios por clase confirmada, con el snapshot por clase. Un "
					+ "cambio posterior de tarifa, nombre o asistencia no la altera (CA-M22-007-06)")
	public record LiquidacionDeHonorariosResponse(
			@Schema(example = "88") long id,
			@Schema(example = "7") long consultorioId,
			@Schema(description = "El egreso de M22 que la confirmacion creo", example = "5501") long egresoId,
			@Schema(description = "Estado actual del egreso", example = "BORRADOR") String egresoEstado,
			@Schema(example = "31") long colaboradorMembershipId,
			@Schema(description = "Nombre congelado en el egreso") String colaboradorNombre,
			@Schema(description = "La oferta a la que se imputo el egreso (DP-39)", example = "34") Long ofertaId,
			LocalDate desde,
			LocalDate hasta,
			@Schema(example = "5250.00") BigDecimal importeTotal,
			@Schema(example = "ARS") String moneda,
			@Schema(description = "LIBERADA cuando se anulo su egreso: sus clases vuelven al borrador",
					allowableValues = {"VIGENTE", "LIBERADA"})
			String estado,
			Instant confirmadaEn,
			@Schema(example = "1204") long confirmadaPorCuentaId,
			Instant liberadaEn,
			List<LineaDeHonorarioResponse> clases,
			@Schema(description = "Los numeros de la tira de la liquidacion (DP-45)")
			ResumenDeHonorariosResponse resumen) {

		public static LiquidacionDeHonorariosResponse de(LiquidacionDeHonorariosView v) {
			return new LiquidacionDeHonorariosResponse(v.id(), v.consultorioId(), v.egresoId(), v.egresoEstado(),
					v.colaboradorMembershipId(), v.colaboradorNombre(), v.ofertaId(), v.desde(), v.hasta(), v.importeTotal(),
					v.moneda(), v.estado(), v.confirmadaEn(), v.confirmadaPorCuentaId(), v.liberadaEn(),
					v.clases().stream().map(LineaDeHonorarioResponse::de).toList(),
					ResumenDeHonorariosResponse.de(v.resumen()));
		}
	}

	@Schema(name = "ResumenDeHonorarios",
			description = "Los numeros de la tira de honorarios (DP-45). Es honorario y su egreso (M22), no "
					+ "caja: lo pagado no dice por que medio salio ni si toco el cajon")
	public record ResumenDeHonorariosResponse(
			@Schema(description = "Clases que entran", example = "12") int cantidadClases,
			@Schema(description = "Clases dictadas que no entran. Solo en el borrador", example = "2")
			Integer cantidadExcluidas,
			@Schema(description = "Suma de presentes de las clases que entran", example = "87") int totalPresentes,
			@Schema(example = "5250.00") BigDecimal importeTotal,
			@Schema(description = "Lo pagado del egreso, hoy. Solo en la liquidacion", example = "3000.00")
			BigDecimal importePagado,
			@Schema(description = "Lo que falta pagar del egreso. Solo en la liquidacion", example = "2250.00")
			BigDecimal saldoPendiente,
			@Schema(description = "Regla de calculo: la tarifa por participante por los presentes, "
					+ "repartida en partes iguales entre los instructores, con el resto del redondeo al "
					+ "principal", allowableValues = {"POR_PARTICIPANTE_PRESENTE"})
			String regla) {

		static ResumenDeHonorariosResponse de(ResumenDeHonorariosView r) {
			return new ResumenDeHonorariosResponse(r.cantidadClases(), r.cantidadExcluidas(), r.totalPresentes(),
					r.importeTotal(), r.importePagado(), r.saldoPendiente(), r.regla());
		}
	}
}
