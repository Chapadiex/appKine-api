package com.akine.billing.domain;

/**
 * En que anda el lote que se le reclama a un financiador.
 *
 * <p><b>Presentado no es facturado y no es cobrado</b> (RN-M21-001). Los tres son momentos
 * distintos del mismo reclamo y el sistema tiene que poder decir en cual esta cada lote, porque de
 * eso depende que puede hacerse con el.
 */
public enum EstadoPresentacion {

	/** Se esta armando. No tiene numero, nadie lo vio, y todavia se le agregan y quitan items. */
	BORRADOR,

	/**
	 * Se envio. Tiene numero y total congelado (RF-M21-004).
	 *
	 * <p>A partir de aca <b>no se quita un item</b>: se debita, que deja motivo, actor e instante.
	 */
	PRESENTADA,

	/**
	 * Ademas tiene registrado el comprobante externo del centro (RF-M21-005).
	 *
	 * <p><b>No es un paso obligatorio.</b> Muchos centros presentan y cobran sin emitir factura
	 * hasta el cierre del trimestre, y otros facturan al presentar. Obligar el orden haria que la
	 * mitad de los usuarios tuvieran que mentirle al sistema para registrar un pago que ya
	 * recibieron.
	 */
	FACTURADA,

	/**
	 * Cerrada: lo presentado quedo explicado por completo, con saldo cero (RF-M21-008).
	 *
	 * <p>Es el unico momento en que las obligaciones aceptadas se saldan. No admite nada mas.
	 */
	CONCILIADA,

	/**
	 * El borrador se descarto con motivo.
	 *
	 * <p><b>Solo sale de {@link #BORRADOR}, y es deliberado.</b> Una presentacion enviada no se
	 * anula: ya existe del otro lado del mostrador. Si el financiador la rechaza entera, eso son
	 * debitos sobre todos sus items, que es lo que efectivamente paso. Hacer desaparecer el lote
	 * borraria la unica evidencia de que se reclamo.
	 */
	ANULADA;

	/** Si todavia se le pueden agregar y quitar items. */
	public boolean esEditable() {
		return this == BORRADOR;
	}

	/** Si admite debitos, pagos y conciliacion: ya se envio y todavia no se cerro. */
	public boolean estaEnCurso() {
		return this == PRESENTADA || this == FACTURADA;
	}
}
