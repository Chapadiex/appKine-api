package com.akine.clinical.spi;

/**
 * Quien pide una derivacion, visto desde {@code clinical}.
 *
 * <p>Existe porque {@code clinical.application.OperatingActor} es privado de ese modulo y no puede
 * cruzar el borde. Lleva lo mismo mas una cosa: la <b>justificacion de acceso</b> de DP-03, que
 * viaja desde la cabecera {@code X-Justificacion-Acceso} del llamador hasta la politica clinica sin
 * que ningun modulo intermedio tenga que interpretarla.
 *
 * @param justificacion motivo declarado del acceso clinico, o {@code null}. <b>No se valida
 *                      aca</b>: quien decide si hacia falta es la politica clinica, porque con
 *                      relacion asistencial no hace falta ninguna. Si hacia falta y no vino, el
 *                      rechazo es 403 con {@code requiereJustificacion}, no un 400 por campo
 *                      faltante — es la regla que 04.01 fijo y que no se reinterpreta aca
 */
public record ActorDeDerivacion(
		long accountId,
		boolean platformAdmin,
		Long organizationId,
		Long consultorioId,
		String justificacion) {
}
