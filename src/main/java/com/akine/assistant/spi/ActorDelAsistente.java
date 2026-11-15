package com.akine.assistant.spi;

/**
 * Quien pregunta, con lo que cada modulo necesita para armar su propio {@code OperatingActor}.
 *
 * <p>Es el mismo usuario de la pantalla: no hay cuenta de servicio. Cada herramienta llama a su
 * servicio de aplicacion con este actor, y el permiso lo exige el servicio, como siempre.
 *
 * @param consultorioId la sede activa de la cabecera (D-4); toda consulta del MVP es de una sede
 */
public record ActorDelAsistente(
		long accountId,
		boolean platformAdmin,
		Long organizationId,
		Long consultorioId,
		String roleCode) {
}
