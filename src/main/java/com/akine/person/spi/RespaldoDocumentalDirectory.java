package com.akine.person.spi;

import java.time.LocalDate;

/**
 * Si una prestacion YA OCURRIDA tiene la documentacion que el convenio exigia (RF-M21-003,
 * AKINE-07.04b).
 *
 * <p>Lo consume la presentacion a financiadores de {@code billing}: el devengado de F-4 congelo en
 * la obligacion <b>que exigia</b> el convenio el dia de la prestacion, y este puerto responde si el
 * paciente <b>tiene</b> la orden y la autorizacion que la respaldan. La arista
 * {@code billing -> person.spi} ya existia y nadie depende de {@code billing}: no cierra ciclo.
 *
 * <h2>No es la elegibilidad de la recepcion</h2>
 *
 * <p>{@link ElegibilidadAdministrativaDirectory} responde "¿se puede atender HOY?" con el convenio
 * vivo, y exige saldo en la autorizacion. Aca la prestacion ya ocurrio: los requisitos los trae el
 * llamador, congelados, y <b>no se mira el saldo</b> — la sesion que se presenta pudo haber gastado
 * la ultima unidad, y exigir saldo hoy marcaria como faltante justo la autorizacion que se uso.
 *
 * <p>Solo lectura. Una persona o cobertura que no es de la organizacion responde todo en
 * {@code false}: una costura entre modulos no es el lugar para un 404, y quien llama ya resolvio
 * pertenencia por su lado.
 */
public interface RespaldoDocumentalDirectory {

	/**
	 * @param coberturaId la cobertura del paciente que se aplico al devengar
	 * @param practicaId  la practica del arancel congelado; {@code null} acepta cualquiera
	 * @param fecha       el dia LOCAL de la sede en que ocurrio la prestacion
	 */
	RespaldoDocumental respaldo(
			long organizationId, long personaId, long coberturaId, Long practicaId, LocalDate fecha);

	/**
	 * @param orden         hay una orden medica activa del paciente, vigente ese dia, sin cobertura
	 *                      declarada o de esa cobertura. La misma regla que la recepcion
	 * @param autorizacion  hay una autorizacion activa y APROBADA de esa cobertura y esa practica
	 *                      cuya vigencia cubre ese dia, sin mirar el saldo
	 * @param numeroAfiliado la cobertura tiene numero de afiliado cargado
	 */
	record RespaldoDocumental(boolean orden, boolean autorizacion, boolean numeroAfiliado) {
	}
}
