package com.akine.resource.domain.exception;

import java.time.LocalTime;

/**
 * El bloque pedido se pisa con otro bloque ACTIVO del mismo profesional en la misma sede (409).
 *
 * <h2>Por que la garantia es de aplicacion y no del esquema</h2>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints ni indices unicos parciales —son de PostgreSQL—,
 * asi que no hay ninguna constraint capaz de expresar "dos rangos horarios del mismo dia no se
 * pueden pisar". La comprobacion vive en {@code DisponibilidadService} y por eso necesita
 * serializacion real: el {@code FOR UPDATE} sobre la fila de {@code consultorio_calendario} de
 * la sede. Sin ese lock, dos altas concurrentes insertan dos bloques que se pisan y ninguna de
 * las dos ve a la otra.
 *
 * <h2>Lo que esta excepcion NO cubre, y conviene tener claro</h2>
 *
 * <p><b>Dos bloques contiguos no se solapan.</b> 09:00-12:00 y 12:00-15:00 son la manana y la
 * tarde del mismo profesional, el horario mas comun que existe, y los dos son legitimos: el
 * extremo superior es EXCLUSIVO ({@code IntervaloLocal}). Un chequeo que los rechace rompe el
 * caso normal, no un borde.
 *
 * <p><b>Un alta que coincide EXACTO tampoco llega aca.</b> Misma membership, mismo dia, mismas
 * horas y misma vigencia devuelve el bloque que ya existe (CA-M05-003-05): es el reintento de
 * red, y responder 409 obligaria al cliente a releer el listado para saber si su primer intento
 * habia entrado.
 *
 * <p>409 y no 400: lo que rechaza la operacion no es la forma del pedido sino el ESTADO del
 * sistema. El mismo pedido, con el bloque conflictivo dado de baja, procede.
 *
 * <h2>Disponibilidad central (0.158.0)</h2>
 *
 * <p>Ademas del bloque de la misma sede, el horario de la MISMA PERSONA en otra sede —un bloque
 * habitual o una apertura— tambien es un solapamiento: no se ofrece disponibilidad simultanea en dos
 * sedes. La excepcion lleva la sede del conflicto (id, nombre, si es la misma) y el horario que choca,
 * y el {@code detail} lo dice: antes decia "en esta sede" aunque el choque fuera en otra.
 */
public class BloqueSolapadoException extends RuntimeException {

	/** Que clase de horario es el que choca. */
	public enum Origen {
		BLOQUE, APERTURA
	}

	private final long bloqueEnConflictoId;
	private final int diaSemana;
	private final LocalTime horaDesde;
	private final LocalTime horaHasta;
	private final Origen origen;
	private final Long conflictoConsultorioId;
	private final String conflictoConsultorioNombre;
	private final boolean conflictoMismaSede;
	private final LocalTime conflictoHoraDesde;
	private final LocalTime conflictoHoraHasta;
	private final java.time.LocalDate conflictoFecha;

	public BloqueSolapadoException(
			long bloqueEnConflictoId, int diaSemana, LocalTime horaDesde, LocalTime horaHasta) {
		this(bloqueEnConflictoId, diaSemana, horaDesde, horaHasta, Origen.BLOQUE, null, null, true, null, null, null);
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public BloqueSolapadoException(
			long bloqueEnConflictoId, int diaSemana, LocalTime horaDesde, LocalTime horaHasta, Origen origen,
			Long conflictoConsultorioId, String conflictoConsultorioNombre, boolean conflictoMismaSede,
			LocalTime conflictoHoraDesde, LocalTime conflictoHoraHasta, java.time.LocalDate conflictoFecha) {

		super("El bloque pedido se solapa con " + (origen == Origen.APERTURA ? "la apertura " : "el bloque ")
				+ bloqueEnConflictoId + " del dia " + diaSemana);
		this.bloqueEnConflictoId = bloqueEnConflictoId;
		this.diaSemana = diaSemana;
		this.horaDesde = horaDesde;
		this.horaHasta = horaHasta;
		this.origen = origen;
		this.conflictoConsultorioId = conflictoConsultorioId;
		this.conflictoConsultorioNombre = conflictoConsultorioNombre;
		this.conflictoMismaSede = conflictoMismaSede;
		this.conflictoHoraDesde = conflictoHoraDesde;
		this.conflictoHoraHasta = conflictoHoraHasta;
		this.conflictoFecha = conflictoFecha;
	}

	/** El {@code detail} en castellano, con la sede real del choque. */
	public String detalle() {
		String donde = conflictoMismaSede
				? "en esta sede"
				: conflictoConsultorioNombre == null ? "en otra sede" : "en la sede " + conflictoConsultorioNombre;
		String cuando = conflictoHoraDesde == null
				? ""
				: (conflictoFecha != null ? " el " + conflictoFecha.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
						: " los " + DIAS[Math.floorMod(diaSemana - 1, 7)])
						+ " de " + hora(conflictoHoraDesde, false) + " a " + hora(conflictoHoraHasta, true);
		return "El profesional ya tiene " + (origen == Origen.APERTURA ? "una apertura de agenda" : "horario de atencion")
				+ cuando + " " + donde + ". No es posible ofrecer disponibilidad simultanea en dos horarios.";
	}

	private static final String[] DIAS = {"lunes", "martes", "miercoles", "jueves", "viernes", "sabados", "domingos"};

	private static String hora(LocalTime hora, boolean esFin) {
		return esFin && (hora.equals(LocalTime.MAX) || hora.equals(LocalTime.MIDNIGHT)) ? "24:00"
				: hora.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
	}

	public Origen getOrigen() {
		return origen;
	}

	public Long getConflictoConsultorioId() {
		return conflictoConsultorioId;
	}

	public String getConflictoConsultorioNombre() {
		return conflictoConsultorioNombre;
	}

	public boolean isConflictoMismaSede() {
		return conflictoMismaSede;
	}

	public LocalTime getConflictoHoraDesde() {
		return conflictoHoraDesde;
	}

	public LocalTime getConflictoHoraHasta() {
		return conflictoHoraHasta;
	}

	public java.time.LocalDate getConflictoFecha() {
		return conflictoFecha;
	}

	public long getBloqueEnConflictoId() {
		return bloqueEnConflictoId;
	}

	public int getDiaSemana() {
		return diaSemana;
	}

	public LocalTime getHoraDesde() {
		return horaDesde;
	}

	public LocalTime getHoraHasta() {
		return horaHasta;
	}
}
