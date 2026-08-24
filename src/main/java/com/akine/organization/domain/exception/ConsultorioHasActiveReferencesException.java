package com.akine.organization.domain.exception;

/**
 * Algun modulo declaro que la sede tiene referencias vigentes que bloquean su baja.
 *
 * <p><b>En F1 no la lanza nadie, y eso es correcto.</b> El unico bloqueo previsto son los
 * turnos futuros, y {@code scheduling} llega en F5 (M12). 02.01 deja declarado el puerto
 * {@code organization.spi.ConsultorioDeactivationProbe} y ninguna implementacion: la lista de
 * sondas esta vacia, asi que la baja siempre procede.
 *
 * <p>El codigo se reserva en el contrato desde ya para que aparecer en F5 no sea un cambio de
 * comportamiento sorpresivo para el frontend, que ya puede mostrar el mensaje correcto.
 *
 * <p>Que debe pasar exactamente con los turnos ya reservados es la D-8 del diseno, todavia
 * abierta: RN-M03-003 solo dice que una sede inactiva no recibe turnos NUEVOS, y ADR-0011
 * (DP-04) prohibe una cancelacion en cascada sin confirmacion explicita, motivo y auditoria por
 * turno.
 */
public class ConsultorioHasActiveReferencesException extends RuntimeException {

	private final Long consultorioId;
	private final transient String referenceType;
	private final long referenceCount;

	public ConsultorioHasActiveReferencesException(
			Long consultorioId, String referenceType, long referenceCount) {
		super("La sede tiene referencias vigentes que impiden darla de baja");
		this.consultorioId = consultorioId;
		this.referenceType = referenceType;
		this.referenceCount = referenceCount;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	/** Que clase de referencia bloquea, en el vocabulario del modulo que la declaro. */
	public String getReferenceType() {
		return referenceType;
	}

	public long getReferenceCount() {
		return referenceCount;
	}
}
