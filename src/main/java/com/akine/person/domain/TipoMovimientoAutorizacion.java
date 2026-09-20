package com.akine.person.domain;

/**
 * Que clase de hecho mueve el saldo de una autorizacion (RF-M17-004, RF-M17-005).
 *
 * <h2>El signo vive aca, no en el numero</h2>
 *
 * <p>{@code AutorizacionMovimiento#cantidad} es <b>siempre positiva</b> y es este enum el que dice
 * en que direccion mueve. La alternativa —guardar negativos— obliga a todo lector a conocer el
 * signo de cada tipo para sumar, y el primero que se olvide produce un saldo que nadie entiende.
 *
 * <h2>Dos de los cuatro no los emite ningun camino, y es deliberado</h2>
 *
 * <p>{@link #RESERVA} y {@link #LIBERACION_DE_RESERVA} existen en el modelo y en el CHECK de
 * {@code V50}, y <b>nadie las escribe</b>. Estan para no tener que migrar el esquema el dia que
 * exista la pre-reserva de unidades al agendar. Se corta el <b>alcance</b>, no el <b>modelo</b>:
 * mismo criterio con el que 04.04 dejo {@code OrigenCantidadAutorizada.AUTORIZACION} declarado y
 * sin escribirse.
 *
 * <p>Y hay una razon dura para que la reserva no exista hoy: <b>DP-05</b>. No se consume al
 * reservar un turno, porque ninguna transicion administrativa prueba que una prestacion ocurrio —
 * un turno que despues se cancela habria comido una unidad que el paciente nunca uso.
 */
public enum TipoMovimientoAutorizacion {

	/** La sesion se cerro con el paciente presente: el financiador gasto una unidad. */
	CONSUMO(true, false),

	/**
	 * Compensa un {@link #CONSUMO} anterior y devuelve la unidad (RF-M17-005).
	 *
	 * <p><b>No borra nada.</b> El consumo original queda donde estaba, diciendo que ocurrio; esta
	 * fila dice que se deshizo, con motivo obligatorio. Regla maestra 10.
	 */
	REVERSION(false, true),

	/** Unidades apartadas sin consumir todavia. <b>Ningun camino la emite.</b> */
	RESERVA(true, false),

	/** Devuelve lo apartado por una {@link #RESERVA}. <b>Ningun camino la emite.</b> */
	LIBERACION_DE_RESERVA(false, true);

	private final boolean descuenta;
	private final boolean devuelve;

	TipoMovimientoAutorizacion(boolean descuenta, boolean devuelve) {
		this.descuenta = descuenta;
		this.devuelve = devuelve;
	}

	/** Resta saldo disponible: el {@code UPDATE} suma a {@code cantidad_consumida}. */
	public boolean descuentaSaldo() {
		return descuenta;
	}

	/** Devuelve saldo: el {@code UPDATE} resta de {@code cantidad_consumida}. */
	public boolean devuelveSaldo() {
		return devuelve;
	}

	/**
	 * Exige motivo declarado.
	 *
	 * <p>Solo la reversion. Un consumo no lo necesita: su motivo <b>es</b> la sesion que lo
	 * produjo, y esa ya viaja en {@code tipoOrigen} + {@code referenciaOrigen}. Pedirle un texto
	 * al observador seria pedirle una frase inventada a un proceso automatico.
	 */
	public boolean exigeMotivo() {
		return this == REVERSION;
	}
}
