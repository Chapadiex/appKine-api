package com.akine.contracting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Los tipos de la importacion masiva de aranceles de un convenio (B-7, RF-M16-007), en un solo
 * archivo por ser las dos caras —pedido y resultado— de la misma operacion.
 *
 * <p>Ver {@link ImportacionArancelesService} para el algoritmo y la concurrencia.
 */
public final class ImportacionAranceles {

	private ImportacionAranceles() {
		// Contenedor de tipos.
	}

	/** Mirar sin escribir, o aplicar todo o nada. */
	public enum Modo {
		/** Valida cada fila. No escribe nada ni toma el lock. */
		PREVIEW,
		/** Revalida todo bajo el lock de {@code convenio_lock} e inserta todo, o nada. */
		CONFIRMAR
	}

	/** Lo que la fila seria si el lote se confirmara. */
	public enum EstadoFila {
		/** Alta nueva: no choca con nada y pasa todas las validaciones del alta unitaria. */
		ALTA,
		/** No entraria: el motivo dice por que, con el mismo problem type que daria el alta. */
		RECHAZADA
	}

	/**
	 * Por que una fila no entraria. Cada valor es exactamente el rechazo del alta unitaria
	 * ({@link ArancelService#crear}) para esa misma fila suelta.
	 */
	public enum MotivoRechazo {
		/** Importes que no cuadran o con mas de 2 decimales, vigencia invertida o fuera del convenio, falta un dato. */
		DATOS_INVALIDOS,
		/** La practica no existe o no es visible para el tenant; o el codigo no resuelve a una sola. */
		PRACTICA_NO_ACCESIBLE,
		/** La oferta no existe en esta sede. */
		OFERTA_NO_ACCESIBLE,
		/** La oferta no admite obra social. */
		OFERTA_SIN_OBRA_SOCIAL,
		/** La oferta no declara la practica. */
		PRACTICA_NO_HABILITADA_EN_OFERTA,
		/** Se pisa con un arancel vigente del convenio, o con otra fila del mismo lote. */
		ARANCEL_SOLAPADO
	}

	/**
	 * Una fila de la planilla, ya parseada por el frontend.
	 *
	 * <p>La practica se identifica por {@code practicaId} o por {@code codigoPractica} —el codigo
	 * es lo que trae la planilla del financiador—. Si vienen los dos, manda el id y el codigo tiene
	 * que ser el suyo.
	 *
	 * @param vigenciaHasta ultimo dia INCLUSIVE. {@code null} = sin fin previsto
	 */
	public record Fila(
			Long practicaId,
			String codigoPractica,
			Long ofertaId,
			BigDecimal importeTotal,
			BigDecimal importeFinanciador,
			BigDecimal coseguro,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {
	}

	public record Comando(Modo modo, List<Fila> filas) {
	}

	/**
	 * El desenlace de una fila.
	 *
	 * @param fila               posicion en el lote, desde 1
	 * @param practicaId         la practica resuelta; {@code null} si no resolvio
	 * @param arancelExistenteId con {@code ARANCEL_SOLAPADO} contra un arancel vigente: cual
	 * @param filaEnConflicto    con {@code ARANCEL_SOLAPADO} contra otra fila del lote: cual
	 * @param arancelId          el arancel creado; solo en una confirmacion aplicada
	 */
	public record ResultadoFila(
			int fila,
			EstadoFila estado,
			Long practicaId,
			Long ofertaId,
			MotivoRechazo motivo,
			String detalle,
			Long arancelExistenteId,
			Integer filaEnConflicto,
			Long arancelId) {

		static ResultadoFila alta(int fila, long practicaId, Long ofertaId) {
			return new ResultadoFila(
					fila, EstadoFila.ALTA, practicaId, ofertaId, null, null, null, null, null);
		}

		static ResultadoFila rechazada(
				int fila, Long practicaId, Long ofertaId, MotivoRechazo motivo, String detalle) {

			return new ResultadoFila(
					fila, EstadoFila.RECHAZADA, practicaId, ofertaId, motivo, detalle, null, null,
					null);
		}

		ResultadoFila conArancelExistente(long arancelExistenteId) {
			return new ResultadoFila(fila, estado, practicaId, ofertaId, motivo, detalle,
					arancelExistenteId, null, null);
		}

		ResultadoFila conFilaEnConflicto(int otraFila) {
			return new ResultadoFila(fila, estado, practicaId, ofertaId, motivo, detalle, null,
					otraFila, null);
		}

		ResultadoFila creada(long nuevoArancelId) {
			return new ResultadoFila(fila, estado, practicaId, ofertaId, motivo, detalle, null,
					null, nuevoArancelId);
		}

		public boolean rechazada() {
			return estado == EstadoFila.RECHAZADA;
		}
	}

	/**
	 * El resultado del lote.
	 *
	 * @param aplicada {@code true} solo en una confirmacion que inserto las filas. Un preview nunca
	 *                 aplica, y una confirmacion rechazada no llega aca: es un 409
	 */
	public record Resultado(Modo modo, boolean aplicada, List<ResultadoFila> filas) {

		public long filasConAlta() {
			return filas.stream().filter(f -> !f.rechazada()).count();
		}

		public long filasRechazadas() {
			return filas.stream().filter(ResultadoFila::rechazada).count();
		}
	}
}
