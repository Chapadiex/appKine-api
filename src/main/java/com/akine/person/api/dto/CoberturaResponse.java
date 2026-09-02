package com.akine.person.api.dto;

import com.akine.person.application.CoberturaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una cobertura del paciente (M08).
 *
 * <p><b>Todo el texto del financiador y del plan que sale aca es la COPIA congelada al firmar</b>,
 * no una lectura del catalogo. Si el plan se renombro despues, esta respuesta sigue diciendo como
 * se llamaba el dia en que la cobertura se cargo, y eso es el requisito y no un defecto: una
 * cobertura firmada ayer no cambia porque hoy editen el plan.
 *
 * <p>{@code estado} y {@code vigente} son dos cosas distintas y viajan las dos. Una cobertura
 * ACTIVA con {@code vigente = false} es el caso normal de un paciente que cambio de obra social:
 * sigue explicando el pasado y no aplica hoy. {@code credencialVencida} es una <b>alerta</b> y no
 * invalida nada.
 */
@Schema(description = "Cobertura de un paciente, con la referencia al plan congelada al firmar")
public record CoberturaResponse(

		@Schema(description = "Identificador de la cobertura", example = "412")
		long id,

		@Schema(description = "Paciente al que pertenece", example = "1204")
		long personaId,

		@Schema(description = "PARTICULAR o FINANCIADA", example = "FINANCIADA")
		String tipo,

		@Schema(description = "Financiador congelado. Null en PARTICULAR", example = "31")
		Long financiadorId,

		@Schema(description = "COPIA del codigo del financiador al firmar", example = "OSDE")
		String financiadorCodigo,

		@Schema(
				description = "COPIA del nombre al firmar. Puede diferir del nombre actual del "
						+ "financiador, y eso es correcto",
				example = "OSDE Binario")
		String financiadorNombre,

		@Schema(description = "COPIA de la clasificacion al firmar", example = "PREPAGA")
		String financiadorTipo,

		@Schema(description = "Plan congelado. Null en PARTICULAR", example = "88")
		Long planId,

		@Schema(description = "COPIA del codigo del plan al firmar", example = "210")
		String planCodigo,

		@Schema(description = "COPIA del nombre del plan al firmar", example = "Plan 210")
		String planNombre,

		@Schema(description = "Si el plan exigia autorizacion previa ESE DIA. Lo interpreta M17")
		Boolean requeriaAutorizacion,

		@Schema(description = "Si el plan exigia credencial ESE DIA")
		Boolean requeriaCredencial,

		@Schema(description = "COPIA del copago de referencia al firmar", example = "1500.00")
		BigDecimal copago,

		@Schema(description = "ISO 4217 del copago congelado", example = "ARS")
		String moneda,

		@Schema(
				description = "Instante UTC en que se congelo la referencia. Es lo que explica por "
						+ "que esta copia puede decir algo distinto del plan de hoy")
		Instant referenciaCapturadaEl,

		@Schema(description = "Credencial del paciente ante el financiador", example = "62000123456")
		String numeroAfiliado,

		@Schema(description = "Ultimo dia de validez de la credencial, INCLUSIVE")
		LocalDate credencialVigenciaHasta,

		@Schema(
				description = "La credencial esta vencida a la fecha consultada. ALERTA, no "
						+ "invalidez: la cobertura sigue aplicando")
		boolean credencialVencida,

		@Schema(description = "Primer dia en que la cobertura aplica", example = "2026-09-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Ultimo dia en que aplica, INCLUSIVE. Null = sin fin previsto")
		LocalDate vigenciaHasta,

		@Schema(
				description = "Si la fecha consultada cae dentro de la vigencia. DISTINTO de "
						+ "estado: una cobertura ACTIVA con la vigencia cerrada devuelve false")
		boolean vigente,

		@Schema(description = "Cobertura preferida del paciente")
		boolean principal,

		@Schema(description = "Notas administrativas")
		String observaciones,

		@Schema(description = "Ciclo de vida: ACTIVA o INACTIVA", example = "ACTIVA")
		String estado,

		@Schema(description = "Instante de la baja logica. Null mientras este activa")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja")
		String deactivationReason,

		@Schema(description = "Version para el control optimista", example = "0")
		long version) {

	public static CoberturaResponse de(CoberturaView vista) {
		return new CoberturaResponse(
				vista.id(),
				vista.personaId(),
				vista.tipo(),
				vista.financiadorId(),
				vista.financiadorCodigo(),
				vista.financiadorNombre(),
				vista.financiadorTipo(),
				vista.planId(),
				vista.planCodigo(),
				vista.planNombre(),
				vista.requeriaAutorizacion(),
				vista.requeriaCredencial(),
				vista.copago(),
				vista.moneda(),
				vista.referenciaCapturadaEl(),
				vista.numeroAfiliado(),
				vista.credencialVigenciaHasta(),
				vista.credencialVencida(),
				vista.vigenciaDesde(),
				vista.vigenciaHasta(),
				vista.vigente(),
				vista.principal(),
				vista.observaciones(),
				vista.estado(),
				vista.deletedAt(),
				vista.deactivationReason(),
				vista.version());
	}
}
