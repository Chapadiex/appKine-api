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
 */
public class BloqueSolapadoException extends RuntimeException {

	private final long bloqueEnConflictoId;
	private final int diaSemana;
	private final LocalTime horaDesde;
	private final LocalTime horaHasta;

	public BloqueSolapadoException(
			long bloqueEnConflictoId, int diaSemana, LocalTime horaDesde, LocalTime horaHasta) {

		super("El bloque pedido se solapa con el bloque " + bloqueEnConflictoId
				+ " del dia " + diaSemana);
		this.bloqueEnConflictoId = bloqueEnConflictoId;
		this.diaSemana = diaSemana;
		this.horaDesde = horaDesde;
		this.horaHasta = horaHasta;
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
