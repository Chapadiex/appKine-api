package com.akine.resource.domain.exception;

/**
 * La membership existe en el tenant pero no habilita en ESA sede (409).
 *
 * <h2>Por que 409 y no 404</h2>
 *
 * <p>Una membership de OTRO tenant no llega aca: no resuelve, y el llamador responde 404 como
 * cualquier otro id inexistente. Lo que esta excepcion describe es un vinculo del PROPIO tenant
 * que el actor ya puede ver —lo lista con {@code colaborador:read}— y sobre el que puede
 * decidir. Ahi el 404 mentiria: el colaborador existe y el administrador lo tiene delante.
 * Lo que no admite la operacion es el ESTADO del vinculo.
 *
 * <h2>Los tres estados que caen aca, y por que van juntos</h2>
 *
 * <ul>
 *   <li><b>Alcance</b>: el vinculo es de otra sede. RN-M05-001 ata la disponibilidad a
 *       Profesional + Consultorio: un profesional que atiende en la sede A no tiene horario en
 *       la B por el solo hecho de pertenecer a la organizacion. Un {@code consultorioId} nulo
 *       en la membership significa <b>alcance organizacion</b> y si cubre cualquier sede — es
 *       la misma convencion de {@code membership} (V10), no un dato faltante.</li>
 *   <li><b>Vigencia</b>: el vinculo todavia no empezo o ya vencio.</li>
 *   <li><b>Habilitacion</b>: SUSPENDIDA o REVOCADA. Un profesional suspendido que siguiera
 *       recibiendo horario nuevo es exactamente lo que la suspension existe para impedir.</li>
 * </ul>
 *
 * <p>Los tres se responden igual porque el remedio del administrador es el mismo —revisar el
 * vinculo del colaborador— y separarlos convertiria este endpoint en un lector del estado
 * interno de {@code organization} por la puerta de atras.
 *
 * <p><b>Esto NO es la desvinculacion.</b> RN-M05-003: desvincular a un profesional no borra sus
 * bloques ni su autoria. Los bloques que ya existen se conservan y dejan de computar porque la
 * membership dejo de estar vigente, no porque se hayan dado de baja. Lo que esta excepcion
 * impide es cargar horario NUEVO sobre un vinculo que no habilita.
 */
public class ProfesionalNoVinculadoException extends RuntimeException {

	private final long membershipId;
	private final long consultorioId;

	public ProfesionalNoVinculadoException(long membershipId, long consultorioId) {
		super("La membership " + membershipId + " no habilita en la sede " + consultorioId);
		this.membershipId = membershipId;
		this.consultorioId = consultorioId;
	}

	public long getMembershipId() {
		return membershipId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
