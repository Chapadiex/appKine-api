package com.akine.person.spi;

/**
 * Como termino un intento de consumir unidades autorizadas.
 *
 * <h2>Es un RESULTADO y no una excepcion, y esa es la decision central de AKINE-04.05</h2>
 *
 * <p>Un cierre de sesion sin saldo <b>no hace fallar el cierre</b>. La atencion ocurrio: el
 * kinesiologo atendio y el paciente estuvo. Que el financiador no tenga saldo es un problema
 * administrativo que se resuelve despues —con otra autorizacion, o facturandole al paciente— y
 * bloquear el cierre de una historia clinica por eso es exactamente lo que DP-06 prohibe.
 *
 * <p>Por eso {@code SIN_SALDO} y {@code SIN_AUTORIZACION_ELEGIBLE} son <b>desenlaces</b>. Si
 * fueran excepciones, quien llama tendria que atraparlas, y atrapar una excepcion de persistencia
 * dentro de una transaccion ajena es la trampa que este repositorio ya pago cuatro veces.
 *
 * <p><b>Va en la direccion contraria a {@code billing.ObligacionDevengador}</b>, que si hace
 * fallar el cierre cuando no puede devengar, y esta bien que asi sea: una prestacion sin deuda es
 * plata perdida y nadie reclama una factura que nunca existio. Aca es al reves y es deliberado.
 * Ver el javadoc de {@code encounter.infrastructure.ConsumoDeAutorizacionEnCierre}.
 */
public record ResultadoDeConsumo(
		String desenlace,
		Long autorizacionId,
		Long movimientoId,
		Integer saldoRestante) {

	/** Se descontaron unidades: hay movimiento nuevo y el saldo bajo. */
	public static final String CONSUMIDA = "CONSUMIDA";

	/**
	 * Ese mismo hecho ya habia consumido. Se devuelve el movimiento que ya existe.
	 *
	 * <p>Es el reintento del mismo cierre, que RN-M14-005 declara idempotente. No es un error y no
	 * vuelve a descontar.
	 */
	public static final String YA_CONSUMIDA = "YA_CONSUMIDA";

	/**
	 * El paciente no tiene ninguna autorizacion aprobada y vigente ese dia.
	 *
	 * <p>Es el caso <b>mas frecuente</b>, no una anomalia: un paciente particular, o uno cuya obra
	 * social no exige autorizacion previa, cierra todas sus sesiones asi.
	 */
	public static final String SIN_AUTORIZACION_ELEGIBLE = "SIN_AUTORIZACION_ELEGIBLE";

	/**
	 * Habia autorizacion, y la base dijo que no alcanza. Cero filas afectadas.
	 *
	 * <p>Es el caso borde "ultima unidad concurrente" visto desde la transaccion que perdio.
	 */
	public static final String SIN_SALDO = "SIN_SALDO";

	public static ResultadoDeConsumo consumida(
			long autorizacionId, long movimientoId, Integer saldoRestante) {
		return new ResultadoDeConsumo(CONSUMIDA, autorizacionId, movimientoId, saldoRestante);
	}

	public static ResultadoDeConsumo yaConsumida(
			long autorizacionId, long movimientoId, Integer saldoRestante) {
		return new ResultadoDeConsumo(YA_CONSUMIDA, autorizacionId, movimientoId, saldoRestante);
	}

	public static ResultadoDeConsumo sinAutorizacionElegible() {
		return new ResultadoDeConsumo(SIN_AUTORIZACION_ELEGIBLE, null, null, null);
	}

	public static ResultadoDeConsumo sinSaldo(long autorizacionId) {
		return new ResultadoDeConsumo(SIN_SALDO, autorizacionId, null, null);
	}

	/** Hubo descuento efectivo en ESTE intento. Un reintento idempotente devuelve {@code false}. */
	public boolean descontoEfectivo() {
		return CONSUMIDA.equals(desenlace);
	}
}
