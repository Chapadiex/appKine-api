package com.akine.billing.domain.port;

import com.akine.billing.domain.MovimientoCaja;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia del ledger de caja.
 *
 * <p><b>No declara {@code update} ni {@code delete}, y esa ausencia es el diseno.</b> Un historial
 * que se puede editar no es un historial (regla maestra 10). La correccion es compensacion: se
 * asienta otro movimiento de tipo {@code REVERSION_DE_*} con motivo y puntero al original. Mismo
 * contrato que los puertos de {@code turno_evento}, {@code caso_evento} y
 * {@code autorizacion_movimiento}.
 */
public interface MovimientoCajaRepositoryPort {

	MovimientoCaja save(MovimientoCaja movimiento);

	Optional<MovimientoCaja> findByIdInScope(
			long organizationId, long consultorioId, long movimientoId);

	Optional<MovimientoCaja> findByIdempotencyKey(long organizationId, String idempotencyKey);

	/** Si ese movimiento ya fue compensado. Lo respalda el unique; esto existe para explicarlo. */
	boolean existeReversionDe(long organizationId, long movimientoOrigenId);

	/**
	 * La operatoria, filtrada (RF-M20-004).
	 *
	 * <p>Por jornada, o por fecha de negocio de la sede — que es lo que permite ver tambien los
	 * movimientos <b>sin jornada</b>, los que no son en efectivo.
	 *
	 * @param jornadaCajaId {@code null} no filtra por jornada
	 * @param fechaNegocio  {@code null} no filtra por dia
	 * @param tipo          {@code null} no filtra por tipo
	 */
	List<MovimientoCaja> buscar(
			long organizationId, long consultorioId, Long jornadaCajaId,
			LocalDate fechaNegocio, String tipo, int limite, int desplazamiento);

	/**
	 * Totales por medio de pago de una jornada, para la vista operativa.
	 *
	 * <p>Con signo aplicado segun el tipo, porque una reversion resta. Es lo que hace visible que
	 * <b>la caja tuvo 120.000 de los cuales 60.000 por tarjeta</b> — el dato que se perderia si los
	 * medios que no afectan el arqueo no se registraran.
	 *
	 * @return pares {@code (medio, total)}
	 */
	List<Object[]> totalesPorMedio(long organizationId, long jornadaCajaId);

	/**
	 * Suma del ledger arqueable de una jornada, con signo.
	 *
	 * <p><b>No se usa para operar:</b> el saldo que gobierna es la columna materializada. Existe
	 * para el criterio de aceptacion de la etapa —"el saldo se reconstruye desde los
	 * movimientos"— y para que un test pueda confrontar las dos cifras. Si divergen, la que miente
	 * es la columna.
	 */
	BigDecimal reconstruirSaldoArqueable(long organizationId, long jornadaCajaId);
}
