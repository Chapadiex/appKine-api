package com.akine.contracting.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Los dos comandos de arancel, en un solo archivo por ser dos caras de la misma escritura.
 *
 * <p>Los importes son {@code BigDecimal} y jamas {@code double} (§37, AGENT.md §5). No hay
 * porcentaje de cobertura: ver {@code ConvenioArancel} para por que.
 */
public final class ArancelCommands {

	private ArancelCommands() {
		// Contenedor de comandos.
	}

	/**
	 * Alta de un arancel bajo un convenio (RF-M16-004).
	 *
	 * <p>La moneda no viaja: la hereda del convenio. Un arancel en otra moneda que su convenio no
	 * seria un dato distinto, seria un dato roto — y dejar que el cliente la mande abriria
	 * exactamente esa puerta.
	 *
	 * @param vigenciaHasta ultimo dia INCLUSIVE. {@code null} = sin fin previsto
	 */
	public record ArancelAltaCommand(
			long practicaId,
			BigDecimal importeTotal,
			BigDecimal importeFinanciador,
			BigDecimal coseguro,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			Long ofertaId) {

		/** Arancel general de la practica, sin oferta: la forma anterior a B-3. */
		public ArancelAltaCommand(
				long practicaId,
				BigDecimal importeTotal,
				BigDecimal importeFinanciador,
				BigDecimal coseguro,
				LocalDate vigenciaDesde,
				LocalDate vigenciaHasta) {

			this(practicaId, importeTotal, importeFinanciador, coseguro, vigenciaDesde,
					vigenciaHasta, null);
		}
	}

	/**
	 * Edicion parcial de un arancel.
	 *
	 * <p>Lo que llega en {@code null} no se toca, pero los importes se validan siempre <b>como
	 * terna</b>: subir el total sin tocar las partes rompe la invariante de que sumen.
	 *
	 * <p>Ni la practica ni el convenio estan aca: cambiarlos no seria editar este arancel, seria
	 * inventar otro.
	 *
	 * @param expectedVersion version leida. Una version vieja produce 409
	 */
	public record ArancelEdicionCommand(
			BigDecimal importeTotal,
			BigDecimal importeFinanciador,
			BigDecimal coseguro,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			long expectedVersion) {
	}
}
