package com.akine.person.spi;

/**
 * Lo que {@code person} ofrece para que otro modulo levante una alerta sobre un consumo de
 * autorizacion (DP-13, RN-M17-003, AKINE C-4).
 *
 * <h2>Por que un spi y no un evento</h2>
 *
 * <p>Lo llama {@code billing} al anular una obligacion, <b>dentro de su transaccion</b>: la alerta
 * existe si y solo si la anulacion commitea, y la auditoria va con ella. La arista
 * {@code billing -> person.spi} ya existia ({@code PacienteDirectory}) y no cierra ningun ciclo
 * porque nadie depende de {@code billing}.
 *
 * <h2>No toca el saldo</h2>
 *
 * <p>Anular la deuda no prueba que la prestacion no ocurrio —puede ser una cortesia o un error de
 * precio—. Devolver la unidad seria deshacer un hecho clinico por un motivo economico. La
 * reversion sigue siendo manual (RF-M17-005), y es la que resuelve la alerta.
 */
public interface AlertasDeConsumo {

	/**
	 * Alerta "consumo a revisar" sobre cada autorizacion que esa sesion consumio y que no se
	 * revirtio todavia. Idempotente: una alerta por consumo, aunque se anulen varias obligaciones
	 * de la misma sesion. Una sesion que no consumio no produce nada.
	 *
	 * @return cuantos consumos quedaron alcanzados
	 */
	int consumoARevisar(ConsumoARevisar hecho);
}
