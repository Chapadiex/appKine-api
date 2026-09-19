package com.akine.clinical.domain.exception;

/**
 * El adjunto clinico ya estaba dado de baja y la operacion exige uno vigente (409).
 *
 * <p>Se sigue leyendo y se sigue DESCARGANDO: una baja logica dice "esto ya no corresponde para
 * operar", no "esto nunca existio", y negar la descarga convertiria la baja en un borrado con
 * otro nombre — que es lo que la regla maestra 10 prohibe. Lo que no admite un adjunto de baja es
 * reclasificarse.
 *
 * <p>Repetir la baja tampoco pasa por aca: es el mismo pedido y se responde con el adjunto tal
 * como quedo, con su motivo original intacto. Pisarlo con el nuevo perderia el que explica la
 * baja. Mismo criterio que {@code EntradaClinicaService.darDeBaja}.
 */
public class AdjuntoClinicoInactivoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long adjuntoId;

	public AdjuntoClinicoInactivoException(long adjuntoId) {
		super("El adjunto clinico ya estaba dado de baja: " + adjuntoId);
		this.adjuntoId = adjuntoId;
	}

	public long getAdjuntoId() {
		return adjuntoId;
	}
}
