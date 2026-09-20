package com.akine.encounter.domain.exception;

/**
 * Se intento enmendar una sesion cerrada sin declarar por que. <b>400</b>.
 *
 * <p><b>Es 400 y no 409</b>, y la distincion importa. No hay conflicto de estado: la sesion esta
 * cerrada, el actor es su dueño y la version que mando es la vigente. Lo que falta es un dato del
 * pedido. Un 409 le diria al profesional que reintente, y reintentar sin motivo vuelve a fallar
 * exactamente igual.
 *
 * <p>El motivo no es burocracia: RN-M14-006 no prohibe corregir una sesion cerrada, prohibe
 * corregirla <b>silenciosamente</b>. Sin motivo, una enmienda es indistinguible de una correccion
 * de tipeo y el historial de versiones deja de servir para lo unico que sirve, que es entender por
 * que cambio el registro (RF-M14-010).
 *
 * <p>Vive en {@code domain} y no en {@code application} porque quien lo hace cumplir es la propia
 * {@code SesionVersion}: ningun camino de escritura —ni uno futuro que no pase por el servicio—
 * puede construir una enmienda sin motivo.
 *
 * <p><b>Hay una excepcion homonima en {@code clinical} y no se comparte.</b> Cada modulo mapea sus
 * excepciones en su propio advice (regla de 01.01): una clase comun obligaria a uno de los dos a
 * importar el {@code domain} del otro. Lo que si se comparte es el {@code type} del Problem
 * Detail, {@code enmienda-sin-motivo}, que sale del catalogo unico de {@code platform.spi.problem}
 * — el cliente maneja una sola respuesta para la misma situacion, la emita quien la emita.
 */
public class EnmiendaSinMotivoException extends RuntimeException {

	private final Long sesionId;

	public EnmiendaSinMotivoException(Long sesionId) {
		super("La enmienda de la sesion " + sesionId + " exige un motivo declarado");
		this.sesionId = sesionId;
	}

	public Long getSesionId() {
		return sesionId;
	}
}
