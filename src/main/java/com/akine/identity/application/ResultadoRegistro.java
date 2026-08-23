package com.akine.identity.application;

/**
 * Que hizo realmente un alta self-service.
 *
 * <p><b>ADVERTENCIA PARA LA CAPA HTTP: nada de esto puede salir en la respuesta.</b> El
 * endpoint de registro responde {@code 202 Accepted} con un cuerpo identico exista o no la
 * cuenta, y por eso este record tampoco puede filtrarse en un {@code Location}, en un header
 * ni en el codigo de estado. Si la respuesta variara, el registro —el endpoint mas facil de
 * automatizar del sistema— se convertiria en un verificador de direcciones de correo, y para
 * eso da lo mismo que el login sea prudente.
 *
 * <p>Existe para el log estructurado, la auditoria y los tests, que son los tres lugares
 * donde la diferencia importa y ninguno de los tres se lo devuelve a quien pregunta.
 *
 * @param cuentaCreada    {@code true} si esta invocacion creo la cuenta y el tenant;
 *                        {@code false} si el email ya existia o si fue un reintento
 * @param cuentaId        {@code null} cuando no se creo nada
 * @param organizationId  {@code null} cuando no se creo nada
 * @param consultorioId   {@code null} cuando no se creo nada
 */
public record ResultadoRegistro(
		boolean cuentaCreada,
		Long cuentaId,
		Long organizationId,
		Long consultorioId) {

	/** Desenlace de un alta que no creo nada: email ya registrado o reintento duplicado. */
	static ResultadoRegistro sinAlta() {
		return new ResultadoRegistro(false, null, null, null);
	}
}
