package com.akine.person.domain;

/**
 * Lo que puede pasarle a una autorizacion y queda en su historial (DP-23, M17).
 *
 * <p><b>VENCIMIENTO no esta a proposito.</b> Vencer es funcion del reloj, no un acto: no hay job
 * que lo detecte ni lectura que escriba, asi que no puede tener fila. El historial lo informa
 * calculado en la respuesta, igual que {@link EstadoAutorizacion} calcula VENCIDA al leer.
 *
 * <p>Tampoco esta la alerta "consumo a revisar" de DP-13: no mueve ni el estado ni el saldo, y
 * tiene su propio recurso ({@code GET /autorizaciones/{id}/alertas}).
 */
public enum TipoEventoAutorizacion {

	/** Se registro. El unico evento sin estado anterior. */
	ALTA,

	/** El financiador la otorgo (APROBAR). */
	APROBACION,

	/** El financiador pidio corregir algo (OBSERVAR). */
	OBSERVACION,

	/** El financiador la denego (RECHAZAR). */
	RECHAZO,

	/** Se corrigieron datos: numero, orden, cantidad, vigencia u observaciones. */
	MODIFICACION,

	/** Se vinculo o desvinculo el comprobante. */
	DOCUMENTO,

	/** Una sesion cerrada desconto unidades (RF-M17-004). */
	CONSUMO,

	/** Se devolvieron las unidades de un consumo (RF-M17-005, DP-13). */
	REVERSION_DE_CONSUMO,

	/** Baja logica: "nunca debio cargarse". No es rechazar. */
	ANULACION;

	/** El evento que deja cada respuesta del financiador. */
	public static TipoEventoAutorizacion de(AccionSobreAutorizacion accion) {
		return switch (accion) {
			case APROBAR -> APROBACION;
			case OBSERVAR -> OBSERVACION;
			case RECHAZAR -> RECHAZO;
		};
	}
}
