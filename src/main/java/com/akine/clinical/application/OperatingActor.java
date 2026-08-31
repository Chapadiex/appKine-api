package com.akine.clinical.application;

/**
 * Quien pide la operacion y en que contexto, ya resuelto por la capa que lo autentico.
 *
 * <p>Es un record propio de {@code clinical} y no el de {@code person}: son de modulos distintos y
 * ArchUnit rechaza importarlo. Cuatro campos duplicados cuestan menos que una clase compartida en
 * {@code platform}, que seria la "capa global" que AGENT.md seccion 4 regla 3 prohibe.
 *
 * <p>{@code contextOrganizationId} y {@code consultorioId} son {@code Long} y no {@code long}
 * porque <b>pueden faltar</b>: una cuenta autenticada sin contexto de trabajo seleccionado es un
 * estado real del producto. Ninguna operacion clinica se puede resolver sin los dos, y quien lo
 * hace cumplir es {@link AutorizacionClinica}, en un solo lugar.
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long consultorioId) {
}
