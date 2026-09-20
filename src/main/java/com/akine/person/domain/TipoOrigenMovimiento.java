package com.akine.person.domain;

/**
 * Que clase de hecho produjo un {@link AutorizacionMovimiento}.
 *
 * <p>Junto con {@code referenciaOrigen} forma la mitad del unique de idempotencia de {@code V50}.
 * Es la respuesta a "que consumio esta unidad", y sin ella el ledger seria una lista de numeros
 * sin causa.
 *
 * <p><b>No se guarda el id pelado sin decir de que tabla es.</b> Un {@code referencia_origen} sin
 * su tipo se vuelve inresoluble en cuanto exista un segundo origen, y el segundo origen ya esta
 * previsto: la reversion administrativa no nace de ninguna sesion.
 */
public enum TipoOrigenMovimiento {

	/**
	 * El cierre de una sesion clinica. {@code referenciaOrigen} es el id de la sesion.
	 *
	 * <p>Es el unico origen que produce {@link TipoMovimientoAutorizacion#CONSUMO} hoy, y tambien
	 * el de la {@link TipoMovimientoAutorizacion#REVERSION} que lo compensa: revertir apunta al
	 * <b>mismo</b> origen que el consumo, y por eso el unique hace que revertir dos veces choque.
	 */
	SESION,

	/**
	 * Un ajuste administrativo que no nace de ninguna atencion.
	 *
	 * <p><b>Declarado y sin escribirse todavia.</b> Existe porque el enum se persiste como texto
	 * y agregar un valor despues obligaria a decidir que dicen las filas viejas. Mismo criterio
	 * que los dos tipos de movimiento que nadie emite.
	 */
	MANUAL
}
