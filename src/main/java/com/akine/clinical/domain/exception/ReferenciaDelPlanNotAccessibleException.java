package com.akine.clinical.domain.exception;

/**
 * Algo que el pedido nombro y que el plan no puede alcanzar (404). AKINE-04.05.
 *
 * <p>Cubre el item que no pertenece a la version vigente del plan y la autorizacion que no existe,
 * es de otro tenant, es de <b>otro paciente</b> o esta dada de baja. <b>Los cinco casos colapsan en
 * la misma respuesta</b>, con el mismo criterio de siempre: distinguirlos confirmaria que ese id
 * existe en algun lado y bastaria probar ids consecutivos (ADR-0018).
 *
 * <p>El caso "es de otro paciente" merece mencion aparte porque es el que mas importa: dejarlo
 * pasar publicaria en la ficha clinica de un paciente el numero de autorizacion de otro.
 *
 * <p>{@code que} viaja para el mensaje —"item del plan", "autorizacion"— y <b>no</b> para que el
 * cliente ramifique: las dos llevan a lo mismo, que es volver a elegir.
 */
public class ReferenciaDelPlanNotAccessibleException extends RuntimeException {

	private final String que;
	private final long id;

	public ReferenciaDelPlanNotAccessibleException(String que, long id) {
		super("El " + que + " no existe o no es accesible");
		this.que = que;
		this.id = id;
	}

	public String getQue() {
		return que;
	}

	public long getId() {
		return id;
	}
}
