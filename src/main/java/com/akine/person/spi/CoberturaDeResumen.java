package com.akine.person.spi;

import java.time.LocalDate;

/**
 * Los datos de una cobertura del Paciente 360 como campos, no como texto (A-11).
 *
 * <p>Viaja <b>solo</b> en los hitos de la seccion {@code coberturas}; en los de cualquier otra
 * seccion el hito lo lleva {@code null}. Existe porque la pantalla del 360 separaba financiador,
 * plan, afiliado y vigencia <b>parseando el {@code titulo}</b>, y un titulo es texto para leer: el
 * dia que alguien cambie el separador o el orden, la ficha se rompe sin que ningun test lo note.
 *
 * <p>Las fechas son {@link LocalDate} y no instantes: la vigencia de una cobertura es un dia del
 * calendario, y convertirla a medianoche UTC —que es lo que el hito generico tiene que hacer con
 * su unico campo temporal— la corre un dia en cualquier huso al oeste de Greenwich.
 *
 * @param tipo                 {@code PARTICULAR} o {@code FINANCIADA}
 * @param financiadorId        {@code null} en una cobertura particular
 * @param financiadorNombre    congelado al firmarse; {@code null} en una particular
 * @param planId               {@code null} si la cobertura no tiene plan
 * @param planNombre           congelado al firmarse; {@code null} si no tiene plan
 * @param afiliadoEnmascarado  ultimos cuatro caracteres a la vista; {@code null} si no hay numero
 * @param vigenciaDesde        primer dia de la cobertura
 * @param vigenciaHasta        ultimo dia de la cobertura, {@code null} si no vence
 * @param principal            si es la cobertura principal del paciente
 * @param estadoCredencial     {@code VIGENTE}, {@code VENCIDA} o {@code SIN_VENCIMIENTO}
 * @param credencialVigenciaHasta vencimiento de la credencial, {@code null} si no lo tiene
 */
public record CoberturaDeResumen(
		String tipo,
		Long financiadorId,
		String financiadorNombre,
		Long planId,
		String planNombre,
		String afiliadoEnmascarado,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean principal,
		String estadoCredencial,
		LocalDate credencialVigenciaHasta) {
}
