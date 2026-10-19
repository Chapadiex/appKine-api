package com.akine.activity.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/** Vistas y comandos de los honorarios por clase (AKINE-08.09b, DP-38). */
public final class HonorariosViews {

	private HonorariosViews() {
	}

	/** Los instructores de una clase, en el orden estable del reparto. */
	public record InstructoresDeClaseView(
			long claseId,
			Long principalMembershipId,
			String principalNombre,
			List<InstructorAdicionalView> adicionales) {
	}

	public record InstructorAdicionalView(long membershipId, String nombre, Instant agregadoEn) {
	}

	/** Una clase que entra en la liquidacion, con todo lo que explica su importe. */
	public record LineaDeHonorarioView(
			long claseId,
			Instant inicio,
			LocalDate fecha,
			String titulo,
			long ofertaId,
			String ofertaNombre,
			int presentes,
			long honorarioId,
			BigDecimal importePorParticipante,
			BigDecimal importeClase,
			int instructores,
			int posicion,
			BigDecimal importeColaborador,
			String moneda) {
	}

	/**
	 * Una clase dictada que NO entra, y por que: {@code YA_LIQUIDADA} (con el egreso que la tiene),
	 * {@code SIN_HONORARIO} (ninguna tarifa vigente ese dia), {@code SIN_PRESENTES} o
	 * {@code SIN_IMPORTE} (la parte del colaborador redondea a cero).
	 */
	public record ClaseExcluidaView(
			long claseId,
			Instant inicio,
			LocalDate fecha,
			String titulo,
			long ofertaId,
			String ofertaNombre,
			String motivo,
			Long egresoId) {
	}

	/** El borrador: calculado al pedirlo, no se guarda. */
	public record BorradorDeHonorariosView(
			long consultorioId,
			long colaboradorMembershipId,
			LocalDate desde,
			LocalDate hasta,
			BigDecimal importeTotal,
			String moneda,
			List<LineaDeHonorarioView> clases,
			List<ClaseExcluidaView> excluidas) {
	}

	/** Una liquidacion confirmada, con su snapshot. */
	public record LiquidacionDeHonorariosView(
			long id,
			long consultorioId,
			long egresoId,
			String egresoEstado,
			long colaboradorMembershipId,
			String colaboradorNombre,
			Long ofertaId,
			LocalDate desde,
			LocalDate hasta,
			BigDecimal importeTotal,
			String moneda,
			String estado,
			Instant confirmadaEn,
			long confirmadaPorCuentaId,
			Instant liberadaEn,
			List<LineaDeHonorarioView> clases) {
	}

	/** La liquidacion y si se creo ahora ({@code false}: reintento con la misma clave). */
	public record ResultadoDeConfirmacion(LiquidacionDeHonorariosView liquidacion, boolean creada) {
	}

	/**
	 * Lo que el administrador confirma. {@code claseIds} e {@code importeTotal} son los del
	 * borrador que miro: si el recalculo da otra cosa, 409. {@code ofertaId} acota a una oferta:
	 * una liquidacion es de una sola, porque su egreso se imputa a ella (DP-39).
	 */
	public record ConfirmarLiquidacionCommand(
			long colaboradorMembershipId,
			LocalDate desde,
			LocalDate hasta,
			List<Long> claseIds,
			BigDecimal importeTotal,
			String concepto,
			String comprobanteTipo,
			String comprobanteNumero,
			LocalDate comprobanteFecha,
			String idempotencyKey,
			Long ofertaId) {

		/** La huella del pedido, para distinguir un reintento de un pedido distinto con la misma clave. */
		public String huella(long consultorioId) {
			String canonico = consultorioId + "|" + colaboradorMembershipId + "|" + desde + "|" + hasta
					+ "|" + new TreeSet<>(claseIds) + "|" + importeTotal.stripTrailingZeros().toPlainString()
					+ "|" + concepto + "|" + comprobanteTipo + "|" + comprobanteNumero + "|" + comprobanteFecha + "|" + ofertaId;
			try {
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
						.digest(canonico.getBytes(StandardCharsets.UTF_8)));
			} catch (NoSuchAlgorithmException imposible) {
				throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
			}
		}
	}
}
