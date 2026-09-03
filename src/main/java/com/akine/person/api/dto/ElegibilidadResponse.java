package com.akine.person.api.dto;

import com.akine.person.application.ElegibilidadAdministrativa;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * El veredicto administrativo preliminar de atender una practica a un paciente (RF-M17-007).
 *
 * <p><b>La lista vacia con {@code elegible = true} es el caso NORMAL</b>, no un error: pasa con
 * una cobertura particular, sin convenio vigente, sin arancel de esa practica, o con un convenio
 * que no exige nada. RN-M17-005 y RN-M17-006: los requisitos se aplican solo cuando el convenio
 * los exige, y una actividad no cubierta no debe pedir orden ni autorizacion artificialmente.
 *
 * <p><b>Esto no persiste nada y no consume nada.</b> Preguntar si se puede atender y descontar una
 * sesion son cosas distintas (RN-M17-001); el consumo llega con la integracion clinica.
 *
 * <p><b>Y no afirma que la prestacion sea facturable</b> (RN-M08-004): dice si la documentacion
 * administrativa que el convenio exige esta presente, que es otra cosa.
 */
@Schema(description = "Requisitos administrativos que el convenio exige y su veredicto")
public record ElegibilidadResponse(

		@Schema(description = "Dia contra el que se evaluo todo", example = "2026-09-03")
		LocalDate fecha,

		@Schema(description = "Ningun requisito exigido quedo sin cumplir. Con la lista vacia es "
				+ "true, y eso es correcto: no hay nada que exigir")
		boolean elegible,

		@Schema(
				description = "Por que no hay requisitos que evaluar, o null si los hay. "
						+ "COBERTURA_PARTICULAR = no hay financiador. SIN_CONVENIO_VIGENTE = la "
						+ "sede no tiene convenio con ese plan; el paciente se atiende como "
						+ "particular. SIN_ARANCEL_VIGENTE = hay convenio pero la practica no "
						+ "tiene arancel cargado, que es un hueco de configuracion del centro y no "
						+ "un motivo para negarle la atencion al paciente",
				example = "SIN_CONVENIO_VIGENTE",
				allowableValues = {"COBERTURA_PARTICULAR", "SIN_CONVENIO_VIGENTE",
						"SIN_ARANCEL_VIGENTE"})
		String motivo,

		@Schema(description = "Lo que el convenio exige, uno por uno, con su veredicto")
		List<RequisitoResponse> requisitos,

		@Schema(description = "Convenio VIVO que se aplico. Null si no hay ninguno", example = "12")
		Long convenioId,

		@Schema(description = "Nombre del convenio aplicado")
		String convenioNombre,

		@Schema(
				description = "Tope mensual de sesiones pactado. INFORMATIVO y sin veredicto: "
						+ "verificarlo exige contar sesiones ya atendidas, o sea el consumo que "
						+ "esta version no cablea",
				example = "20")
		Integer limiteSesionesMensual) {

	/**
	 * Un requisito y su veredicto.
	 *
	 * <p>Un requisito insatisfecho <b>no es un error</b>: se responde 200 con
	 * {@code cumplido = false} y el detalle de que falta. Un 409 obligaria a la pantalla a tratar
	 * el caso mas frecuente —al paciente le falta la orden— como una excepcion.
	 */
	@Schema(description = "Un requisito del convenio y su veredicto")
	public record RequisitoResponse(

			@Schema(description = "Que exige el convenio. Son exactamente las tres banderas que "
					+ "declara desde M16", example = "ORDEN",
					allowableValues = {"ORDEN", "AUTORIZACION", "CREDENCIAL"})
			String tipo,

			@Schema(description = "Si hoy esta satisfecho")
			boolean cumplido,

			@Schema(description = "Orden o autorizacion que lo satisface, o la cobertura para la "
					+ "credencial. Null si falta", example = "51")
			Long referenciaId,

			@Schema(description = "Por que esta o no cumplido, en lenguaje del mostrador")
			String detalle,

			@Schema(description = "Sesiones restantes cuando el requisito es AUTORIZACION y hay "
					+ "tope declarado", example = "6")
			Integer saldo) {
	}

	public static ElegibilidadResponse de(ElegibilidadAdministrativa veredicto) {
		return new ElegibilidadResponse(
				veredicto.fecha(),
				veredicto.elegible(),
				veredicto.motivo(),
				veredicto.requisitos().stream()
						.map(requisito -> new RequisitoResponse(
								requisito.tipo().name(),
								requisito.cumplido(),
								requisito.referenciaId(),
								requisito.detalle(),
								requisito.saldo()))
						.toList(),
				veredicto.convenioId(),
				veredicto.convenioNombre(),
				veredicto.limiteSesionesMensual());
	}
}
