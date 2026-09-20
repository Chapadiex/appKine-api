package com.akine.person.domain.exception;

/**
 * Ese consumo ya tiene su reversion (409).
 *
 * <p><b>Lo garantiza el unique de {@code V50}</b>
 * —{@code (organization_id, autorizacion_id, tipo, tipo_origen, referencia_origen)}—, no un
 * chequeo previo. El {@code tipo} entra en la clave a proposito: una REVERSION del mismo origen es
 * OTRA fila que el CONSUMO y tiene que poder entrar; una SEGUNDA reversion del mismo origen si es
 * un duplicado y choca. Eso es lo que hace a la reversion idempotente en vez de meramente segura.
 *
 * <p>El servicio consulta antes para poder responder este 409 explicable en vez del error de
 * constraint, pero <b>el unique sigue siendo quien lo hace cumplir</b>: el pre-chequeo achica la
 * ventana, no la cierra.
 *
 * <p>Lleva el movimiento que ya compensa para que la pantalla pueda mostrarlo —con su motivo y su
 * autor— en vez de dejar al operador preguntandose quien lo revirtio.
 */
public class MovimientoYaRevertidoException extends RuntimeException {

	private final long movimientoId;
	private final long reversionExistenteId;

	public MovimientoYaRevertidoException(long movimientoId, long reversionExistenteId) {
		super("El movimiento " + movimientoId + " ya fue revertido");
		this.movimientoId = movimientoId;
		this.reversionExistenteId = reversionExistenteId;
	}

	public long getMovimientoId() {
		return movimientoId;
	}

	public long getReversionExistenteId() {
		return reversionExistenteId;
	}
}
