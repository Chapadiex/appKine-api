package com.akine.clinical.domain.exception;

/**
 * Se intento enmendar una entrada clinica sin declarar por que.
 *
 * <p><b>Es 400 y no 409</b>, y la distincion importa. No hay conflicto de estado: la entrada esta
 * vigente y el actor tiene permiso. Lo que falta es un dato del pedido. Un 409 le diria al
 * profesional que reintente, y reintentar sin motivo vuelve a fallar exactamente igual.
 *
 * <p>El motivo no es burocracia: sin el, una enmienda es indistinguible de una correccion de
 * tipeo y el historial de versiones deja de servir para lo unico que sirve, que es entender por
 * que cambio el texto (RF-M09-006, RN-M09-004).
 *
 * <p>Vive en {@code domain} y no en {@code application} porque quien lo hace cumplir es la propia
 * {@code EntradaClinicaVersion}: ningun camino de escritura —ni uno futuro que no pase por el
 * servicio— puede construir una enmienda sin motivo.
 */
public class EnmiendaSinMotivoException extends RuntimeException {

	private final long entradaClinicaId;

	public EnmiendaSinMotivoException(long entradaClinicaId) {
		super("La enmienda de la entrada clinica " + entradaClinicaId
				+ " exige un motivo declarado");
		this.entradaClinicaId = entradaClinicaId;
	}

	public long getEntradaClinicaId() {
		return entradaClinicaId;
	}
}
