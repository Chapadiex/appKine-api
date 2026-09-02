package com.akine.person.api.dto;

import com.akine.person.application.ResumenDePersonaView;
import com.akine.person.spi.AporteDeResumen;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
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
			long referencia) {
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
								hito.referencia()))
						.toList());
	}
}
