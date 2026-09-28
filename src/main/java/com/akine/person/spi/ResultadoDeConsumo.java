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

	/**
	 * Habia autorizaciones vigentes, y <b>ninguna es de una practica que realmente se aplico</b>
	 * (AKINE-06.04).
	 *
	 * <h2>Es el desenlace que corrige el defecto declarado de 04.05</h2>
	 *
	 * <p>Antes de 06.04 este caso <b>no existia</b>: el consumo elegia "la que vence antes" sin
	 * mirar la practica y descontaba igual. Eso significaba gastar, por ejemplo, una unidad de
	 * <b>fonoaudiologia</b> para pagar una sesion de <b>kinesiologia</b>.
	 *
	 * <p><b>Por que no consumir es mejor que consumir la equivocada</b>, que es la objecion
	 * obvia:
	 *
	 * <ul>
	 *   <li>Le <b>come al paciente</b> unidades que si iba a necesitar, y deja intacta la
	 *       autorizacion que correspondia.</li>
	 *   <li>Frente al financiador es una <b>declaracion falsa</b>: el centro presenta a cobro una
	 *       practica que no presto.</li>
	 *   <li>Y es silencioso. Hoy nadie lo detecta porque no hay con que compararlo.</li>
	 * </ul>
	 *
	 * <p><b>No consumir ya era un desenlace benigno y previsto</b>: el observador no lanza, el
	 * cierre clinico no se bloquea (DP-06) y el circuito administrativo lo resuelve despues con
	 * otra autorizacion o facturandole al paciente. Este desenlace no cambia ninguna de esas
	 * garantias — cambia "consumio mal y nadie se entera" por "no consumio y queda dicho".
	 */
	public static final String SIN_AUTORIZACION_PARA_LA_PRACTICA =
			"SIN_AUTORIZACION_PARA_LA_PRACTICA";

	public static ResultadoDeConsumo sinAutorizacionElegible() {
		return new ResultadoDeConsumo(SIN_AUTORIZACION_ELEGIBLE, null, null, null);
	}

	/** Ver {@link #SIN_AUTORIZACION_PARA_LA_PRACTICA}. */
	public static ResultadoDeConsumo sinAutorizacionParaLaPractica() {
		return new ResultadoDeConsumo(SIN_AUTORIZACION_PARA_LA_PRACTICA, null, null, null);
	}

	public static ResultadoDeConsumo sinSaldo(long autorizacionId) {
		return new ResultadoDeConsumo(SIN_SALDO, autorizacionId, null, null);
	}

	/** Hubo descuento efectivo en ESTE intento. Un reintento idempotente devuelve {@code false}. */
	public boolean descontoEfectivo() {
		return CONSUMIDA.equals(desenlace);
	}
}
