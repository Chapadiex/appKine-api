package com.akine.scheduling.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Todo lo que dibuja la agenda de una sede en una ventana (TURNO - Tecnico §14 y §55): quien atiende y
 * cuando, en que boxes, y que hay encima.
 */
public record AgendaOperativaView(
		LocalDate desde,
		LocalDate hasta,
		String timezone,
		List<ProfesionalDeAgendaView> profesionales,
		List<EspacioDeAgendaView> espacios,
		List<TurnoDeCalendarioView> turnos,
		List<ClaseEnAgendaView> clases,
		List<BloqueoEnAgendaView> bloqueos,
		List<OcupacionEnOtraSedeView> ocupacionesEnOtrasSedes,
		List<ConflictoDeAgendaView> conflictos) {

	/** Sin las dos listas de 0.156.0: para quien arma la vista sin consultar otras sedes. */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public AgendaOperativaView(
			LocalDate desde, LocalDate hasta, String timezone, List<ProfesionalDeAgendaView> profesionales,
			List<EspacioDeAgendaView> espacios, List<TurnoDeCalendarioView> turnos, List<ClaseEnAgendaView> clases,
			List<BloqueoEnAgendaView> bloqueos) {
		this(desde, hasta, timezone, profesionales, espacios, turnos, clases, bloqueos, List.of(), List.of());
	}

	/**
	 * La persona de una columna esta ocupada en OTRA sede (agenda de clases y avisos, 0.156.0): la celda
	 * no esta libre aunque en esta sede no haya nada. Sin paciente ni id: es de otra sede.
	 *
	 * @param membershipId la columna (membership de esta sede)
	 * @param tipo         {@code TURNO} o {@code CLASE}
	 */
	public record OcupacionEnOtraSedeView(
			Long membershipId, String tipo, long consultorioId, Instant inicio, Instant fin) {
	}

	/**
	 * Un turno y una clase de la misma persona que se pisan, ya guardados (datos anteriores a 0.156.0).
	 * Derivado al leer, nada se modifica. El id viaja solo del lado que es de esta sede.
	 *
	 * @param inicio comienzo del tramo en que se pisan
	 * @param fin    fin del tramo en que se pisan
	 */
	public record ConflictoDeAgendaView(
			Long membershipId, Long turnoId, Long turnoConsultorioId, Long claseId, Long claseConsultorioId,
			Instant inicio, Instant fin, String tipo, Long excepcionId, String causa, Long otraSedeConsultorioId) {

		/** Un turno y una clase de la misma persona que se pisan (0.156.0). */
		public static final String TURNO_CLASE = "TURNO_CLASE";
		/**
		 * Una clase de esta sede cuyo instructor (principal o adicional, la columna) tiene un cierre que la
		 * alcanza: un bloqueo o una ausencia suyos, o el cierre de la sede (disponibilidad central, 0.158.0).
		 */
		public static final String CLASE_EN_CIERRE = "CLASE_EN_CIERRE";
		/** La columna atiende en esta sede y en otra a la vez: doble oferta (disponibilidad central, 0.158.0). */
		public static final String DISPONIBILIDAD_DOBLE = "DISPONIBILIDAD_DOBLE";

		public ConflictoDeAgendaView(
				Long membershipId, Long turnoId, long turnoConsultorioId, Long claseId, long claseConsultorioId,
				Instant inicio, Instant fin) {
			this(membershipId, turnoId, turnoConsultorioId, claseId, claseConsultorioId, inicio, fin, TURNO_CLASE,
					null, null, null);
		}
	}

	/** @param franjas cuando atiende: lo que no esta aca no se puede reservar */
	public record ProfesionalDeAgendaView(long membershipId, String nombre, List<FranjaDeAgendaView> franjas) {
	}

	public record FranjaDeAgendaView(Instant desde, Instant hasta) {
	}

	public record EspacioDeAgendaView(long id, String nombre, String tipo) {
	}

	public record ClaseEnAgendaView(
			long claseId, Instant inicio, Instant fin, String estado, String titulo, String ofertaNombre,
			Long profesionalId, Long espacioId, int capacidad, int ocupados) {
	}

	/**
	 * Un cierre, un tramo por dia.
	 *
	 * @param excepcionId el cierre: se da de baja una sola vez aunque abarque varios dias
	 * @param membershipId {@code null} = sede entera
	 */
	public record BloqueoEnAgendaView(
			long excepcionId, Long membershipId, String motivo, String notas, Instant desde, Instant hasta) {
	}
}
