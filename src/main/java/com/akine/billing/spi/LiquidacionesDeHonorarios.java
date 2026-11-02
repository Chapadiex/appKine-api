package com.akine.billing.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Las liquidaciones de honorarios por clase (AKINE-08.09b, DP-38, RF-M22-006/007).
 *
 * <p>La consume {@code activity}, que calcula la base —conoce clases, instructores y
 * asistencias— y la entrega aca ya resuelta. {@code billing} crea el egreso de M22 y congela el
 * detalle (CA-M22-007-06). Misma direccion que {@link DevengoDeClases}: {@code activity -> billing},
 * nunca al reves. Por eso liberar las clases al anular el egreso es una escritura de
 * {@code billing} sobre su propia tabla, sin avisarle a nadie: el borrador siguiente pregunta.
 *
 * <p>Nada de esta interfaz autoriza: el llamador ya exigio {@code caja:operate}.
 */
public interface LiquidacionesDeHonorarios {

	/**
	 * Cuales de esas clases el colaborador ya tiene liquidadas en una liquidacion vigente, con el
	 * egreso que las tiene ({@code claseId -> egresoId}).
	 */
	Map<Long, Long> clasesLiquidadas(long organizationId, long colaboradorMembershipId, Collection<Long> claseIds);

	/** Si la clase tiene honorarios liquidados vigentes, de cualquier instructor. */
	boolean claseLiquidada(long organizationId, long claseId);

	/** La liquidacion creada con esa clave, si existe. Alcance organizacion. */
	Optional<Liquidacion> porIdempotencyKey(long organizationId, String idempotencyKey);

	/** Una liquidacion de la sede, con su detalle. */
	Optional<Liquidacion> ver(long organizationId, long consultorioId, long liquidacionId);

	/**
	 * Crea el egreso {@code BORRADOR} y la liquidacion con su detalle, <b>en la transaccion del
	 * llamador</b> ({@code MANDATORY}). El llamador ya verifico que ninguna clase este liquidada
	 * bajo el lock de las clases; el unique del detalle es el cinturon.
	 *
	 * @throws RuntimeException las del egreso manual: beneficiario no vinculado (409), comprobante
	 *                          duplicado (409)
	 */
	Liquidacion confirmar(LiquidacionAConfirmar pedido);

	/**
	 * Lo que el administrador confirma, ya calculado. <b>De una sola oferta</b>: el egreso queda
	 * imputado a ella (DP-39) y los honorarios entran solos en la rentabilidad por servicio.
	 */
	record LiquidacionAConfirmar(
			long organizationId,
			long consultorioId,
			long colaboradorMembershipId,
			long ofertaId,
			LocalDate periodoDesde,
			LocalDate periodoHasta,
			String concepto,
			BigDecimal importeTotal,
			String moneda,
			String comprobanteTipo,
			String comprobanteNumero,
			LocalDate comprobanteFecha,
			long actorCuentaId,
			String idempotencyKey,
			String requestHash,
			List<ClaseLiquidada> clases) {
	}

	/** Una clase de la liquidacion: el snapshot de lo que explica su importe. */
	record ClaseLiquidada(
			long claseId,
			Instant claseInicio,
			LocalDate claseFecha,
			String claseTitulo,
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

	/** Una liquidacion confirmada, tal como se lee. */
	record Liquidacion(
			long id,
			long consultorioId,
			long egresoId,
			String egresoEstado,
			BigDecimal egresoSaldoPendiente,
			long colaboradorMembershipId,
			String colaboradorNombre,
			Long ofertaId,
			LocalDate periodoDesde,
			LocalDate periodoHasta,
			BigDecimal importeTotal,
			String moneda,
			String estado,
			Instant confirmadaEn,
			long confirmadaPorCuentaId,
			Instant liberadaEn,
			String requestHash,
			List<ClaseLiquidada> clases) {
	}
}
