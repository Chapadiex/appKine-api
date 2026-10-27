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

	/**
	 * La regla con la que se calcula cada linea (DP-38): la tarifa por participante de la oferta por
	 * los presentes de la clase, repartida en partes iguales entre sus instructores, con el resto del
	 * redondeo al principal. Es la unica regla vigente; viaja como codigo estable para que la pantalla
	 * la explique y para que una regla futura no se confunda con esta.
	 */
	public static final String REGLA_POR_PARTICIPANTE_PRESENTE = "POR_PARTICIPANTE_PRESENTE";

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

		/** Los numeros de la tira del borrador (DP-45), derivados de sus lineas. */
		public ResumenDeHonorariosView resumen() {
			return new ResumenDeHonorariosView(clases.size(), excluidas.size(), presentes(clases),
					importeTotal, null, null, REGLA_POR_PARTICIPANTE_PRESENTE);
		}
	}

	/**
	 * Una liquidacion confirmada, con su snapshot.
	 *
	 * @param egresoSaldoPendiente lo que falta pagar del egreso, hoy; {@code null} si el egreso no se
	 *                             encontro (DP-45)
	 */
	public record LiquidacionDeHonorariosView(
			long id,
			long consultorioId,
			long egresoId,
			String egresoEstado,
			BigDecimal egresoSaldoPendiente,
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

		/**
		 * Los numeros de la tira de la liquidacion (DP-45). Lo pagado sale del egreso que la
		 * confirmacion creo, cuyo importe es el de la liquidacion y no se edita
		 * ({@code egreso-de-liquidacion}): {@code importeTotal - saldoPendiente}.
		 */
		public ResumenDeHonorariosView resumen() {
			BigDecimal pagado = egresoSaldoPendiente == null ? null : importeTotal.subtract(egresoSaldoPendiente);
			return new ResumenDeHonorariosView(clases.size(), null, presentes(clases), importeTotal,
					pagado, egresoSaldoPendiente, REGLA_POR_PARTICIPANTE_PRESENTE);
		}
	}

	/**
	 * Los numeros que la pantalla de honorarios muestra en su tira (DP-45). Es honorario y su egreso
	 * (M22), no caja: lo pagado no dice por que medio ni si toco el cajon.
	 *
	 * @param cantidadExcluidas solo en el borrador
	 * @param importePagado     solo en la liquidacion
	 * @param saldoPendiente    solo en la liquidacion
	 */
	public record ResumenDeHonorariosView(
			int cantidadClases,
			Integer cantidadExcluidas,
			int totalPresentes,
			BigDecimal importeTotal,
			BigDecimal importePagado,
			BigDecimal saldoPendiente,
			String regla) {
	}

	private static int presentes(List<LineaDeHonorarioView> lineas) {
		return lineas.stream().mapToInt(LineaDeHonorarioView::presentes).sum();
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
