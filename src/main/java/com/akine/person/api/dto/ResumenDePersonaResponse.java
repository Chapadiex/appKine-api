package com.akine.person.api.dto;

import com.akine.person.application.ResumenDePersonaView;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.CoberturaDeResumen;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * El Paciente 360 tal como sale por la API (RF-M07-004).
 *
 * <h2>Por que las secciones son una lista y no campos fijos</h2>
 *
 * <p>Un DTO con {@code turnos}, {@code economia}, {@code coberturas} y {@code casos} como campos
 * obligaria a <b>cambiar el contrato cada vez que una etapa agrega una fuente</b>, y a que
 * {@code person} conociera de antemano a modulos que todavia no existen. Con una lista de
 * secciones, 03.03 agrega su contribuyente y la ficha crece sin tocar este archivo ni la version
 * mayor del contrato.
 *
 * <p>El costo esta asumido: el cliente tipado recibe una lista y ubica su bloque por
 * {@code seccion}, en vez de acceder a un campo. A cambio, una seccion que el actor no puede ver
 * simplemente no viene, que es justo lo que "segun permisos" significa.
 *
 * <h2>{@code seccionesOmitidas} no es decoracion</h2>
 *
 * <p>Es la diferencia entre que la pantalla diga "no tiene turnos" y que diga "no podes ver los
 * turnos". Sin este campo, un recorte por permiso se leeria como un vacio de datos, y alguien
 * tomaria una decision sobre eso.
 */
@Schema(description = "Ficha administrativa consolidada de una persona, recortada por permisos")
public record ResumenDePersonaResponse(

		@Schema(description = "La ficha administrativa completa")
		PersonaResponse persona,

		@Schema(description = "Cantidad de adjuntos VIGENTES", example = "3")
		long adjuntosTotal,

		@Schema(description = "Adjuntos vigentes por categoria. Las categorias sin adjuntos no "
				+ "aparecen")
		Map<String, Long> adjuntosPorCategoria,

		@Schema(description = "Lo que aportaron los demas modulos, segun los permisos del actor")
		List<SeccionResponse> secciones,

		@Schema(description = "Secciones que NO se devolvieron por falta de permiso. Viajan "
				+ "explicitas para que la pantalla no las confunda con ausencia de datos")
		List<SeccionOmitidaResponse> seccionesOmitidas) {

	/** Un bloque del 360 aportado por un modulo. */
	@Schema(description = "Bloque del resumen aportado por un modulo")
	public record SeccionResponse(

			@Schema(description = "Nombre estable de la seccion", example = "turnos")
			String seccion,

			@Schema(description = "Los numeros de la seccion")
			List<IndicadorResponse> indicadores,

			@Schema(description = "Hechos datados, mas nuevos primero. Es un indice, no un visor: "
					+ "el detalle se pide al modulo que lo posee, con su propio permiso")
			List<HitoResponse> hitos) {
	}

	/** Un numero del 360. */
	@Schema(description = "Un indicador del resumen")
	public record IndicadorResponse(

			@Schema(description = "Identificador estable", example = "turnos-futuros")
			String clave,

			@Schema(description = "Texto legible", example = "Turnos futuros")
			String etiqueta,

			@Schema(description = "Cantidad, cuando el indicador cuenta cosas", example = "2")
			Long cantidad,

			@Schema(description = "Importe, cuando el indicador suma plata. Decimal exacto",
					example = "4500.00")
			BigDecimal importe,

			@Schema(description = "Moneda ISO 4217, presente solo cuando hay importe",
					example = "ARS")
			String moneda) {
	}

	/** Un hecho datado del 360. */
	@Schema(description = "Un hecho datado del resumen")
	public record HitoResponse(

			@Schema(description = "Modulo que produjo el hecho", example = "turnos")
			String origen,

			@Schema(description = "Tipo de hecho", example = "TURNO")
			String tipo,

			@Schema(description = "Instante UTC en que ocurrio")
			Instant ocurrioEn,

			@Schema(description = "Titulo corto", example = "Turno")
			String titulo,

			@Schema(description = "Estado en que quedo", example = "RESERVADO")
			String estado,

			@Schema(description = "Id de la entidad de origen, para pedir el detalle", example = "9")
			long referencia,

			@Schema(description = "Datos estructurados de la cobertura. Solo en los hitos de la "
					+ "seccion coberturas; en los demas viene nulo. Son los mismos datos del "
					+ "titulo, como campos: el titulo es para leer, no para partir",
					nullable = true)
			CoberturaDelHitoResponse cobertura) {
	}

	/** Los datos de una cobertura del 360, como campos (A-11). */
	@Schema(name = "CoberturaDelHitoResponse",
			description = "Cobertura vigente del paciente tal como la muestra el 360")
	public record CoberturaDelHitoResponse(

			@Schema(description = "PARTICULAR o FINANCIADA", example = "FINANCIADA")
			String tipo,

			@Schema(description = "Financiador, nulo en una cobertura particular", example = "4",
					nullable = true)
			Long financiadorId,

			@Schema(description = "Nombre del financiador congelado al firmar la cobertura",
					example = "OSDE", nullable = true)
			String financiadorNombre,

			@Schema(description = "Plan, nulo si la cobertura no tiene plan", example = "7",
					nullable = true)
			Long planId,

			@Schema(description = "Nombre del plan congelado al firmar la cobertura",
					example = "210", nullable = true)
			String planNombre,

			@Schema(description = "Numero de afiliado con solo los ultimos cuatro caracteres a "
					+ "la vista. El numero completo esta en la lista de coberturas",
					example = "···4321", nullable = true)
			String afiliadoEnmascarado,

			@Schema(description = "Primer dia de la cobertura (fecha sin hora)",
					example = "2026-01-01")
			LocalDate vigenciaDesde,

			@Schema(description = "Ultimo dia de la cobertura (fecha sin hora). Nulo si no vence",
					example = "2026-12-31", nullable = true)
			LocalDate vigenciaHasta,

			@Schema(description = "Si es la cobertura principal del paciente", example = "true")
			boolean principal,

			@Schema(description = "Estado de la credencial hoy",
					allowableValues = {"VIGENTE", "VENCIDA", "SIN_VENCIMIENTO"},
					example = "VIGENTE")
			String estadoCredencial,

			@Schema(description = "Vencimiento de la credencial (fecha sin hora), nulo si no lo "
					+ "tiene", example = "2026-06-30", nullable = true)
			LocalDate credencialVigenciaHasta) {

		static CoberturaDelHitoResponse from(CoberturaDeResumen cobertura) {
			if (cobertura == null) {
				return null;
			}
			return new CoberturaDelHitoResponse(
					cobertura.tipo(),
					cobertura.financiadorId(),
					cobertura.financiadorNombre(),
					cobertura.planId(),
					cobertura.planNombre(),
					cobertura.afiliadoEnmascarado(),
					cobertura.vigenciaDesde(),
					cobertura.vigenciaHasta(),
					cobertura.principal(),
					cobertura.estadoCredencial(),
					cobertura.credencialVigenciaHasta());
		}
	}

	/** Una seccion recortada por permiso. */
	@Schema(description = "Seccion no devuelta por falta de permiso")
	public record SeccionOmitidaResponse(

			@Schema(description = "Nombre de la seccion omitida", example = "economia")
			String seccion,

			@Schema(description = "Codigo de permiso que falta", example = "cobro:register")
			String permisoRequerido) {
	}

	public static ResumenDePersonaResponse from(ResumenDePersonaView view) {
		return new ResumenDePersonaResponse(
				PersonaResponse.from(view.persona()),
				view.adjuntosTotal(),
				view.adjuntosPorCategoria(),
				view.secciones().stream().map(ResumenDePersonaResponse::seccionDe).toList(),
				view.seccionesOmitidas().stream()
						.map(omitida -> new SeccionOmitidaResponse(
								omitida.seccion(), omitida.permisoRequerido()))
						.toList());
	}

	private static SeccionResponse seccionDe(AporteDeResumen aporte) {
		return new SeccionResponse(
				aporte.seccion(),
				aporte.indicadores().stream()
						.map(indicador -> new IndicadorResponse(
								indicador.clave(),
								indicador.etiqueta(),
								indicador.cantidad(),
								indicador.importe(),
								indicador.moneda()))
						.toList(),
				aporte.hitos().stream()
						.map(hito -> new HitoResponse(
								hito.origen(),
								hito.tipo(),
								hito.ocurrioEn(),
								hito.titulo(),
								hito.estado(),
								hito.referencia(),
								CoberturaDelHitoResponse.from(hito.cobertura())))
						.toList());
	}
}
