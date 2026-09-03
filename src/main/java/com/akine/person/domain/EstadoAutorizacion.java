package com.akine.person.domain;

/**
 * Los cuatro estados de una autorizacion que decide una PERSONA (M17).
 *
 * <p><b>VENCIDA y AGOTADA no estan aca a proposito.</b> Son funciones del reloj y de una resta, y
 * se calculan al leer: materializarlas exigiria un job que las mueva, y un job que no corre deja
 * autorizaciones vencidas que el sistema sigue creyendo vigentes. Ademas "un documento vencido no
 * desaparece" es requisito de la etapa, y con el vencimiento calculado la fila no se toca nunca.
 *
 * <p>Es el mismo criterio con que 02.04 dejo la disponibilidad efectiva sin materializar y con
 * que 03.04 separo el ciclo de vida de la vigencia.
 *
 * <pre>
 *   PENDIENTE ──aprobar──▶ APROBADA     (terminal: habilita)
 *       │
 *       ├────observar───▶ OBSERVADA ──aprobar──▶ APROBADA
 *       │                     └────rechazar──▶ RECHAZADA
 *       │
 *       └────rechazar───▶ RECHAZADA    (terminal: no habilita)
 * </pre>
 *
 * <p>APROBADA y RECHAZADA son terminales. Corregir una decision tomada es dar de baja la
 * autorizacion y cargar otra, no reescribir el estado: si una autorizacion aprobada pudiera volver
 * a PENDIENTE, el saldo que ya se conto para atender a alguien desapareceria retroactivamente.
 */
public enum EstadoAutorizacion {

	/** Cargada y esperando respuesta del financiador. No habilita nada. */
	PENDIENTE,

	/** El financiador la otorgo. Es el UNICO estado que habilita, y solo mientras este vigente. */
	APROBADA,

	/** El financiador pidio corregir algo. Sigue viva, no habilita, y exige motivo. */
	OBSERVADA,

	/** El financiador la denego. Terminal, exige motivo, y queda como historico consultable. */
	RECHAZADA;

	/** Si desde este estado se puede llegar a otro. APROBADA y RECHAZADA son terminales. */
	public boolean admiteResolucion() {
		return this == PENDIENTE || this == OBSERVADA;
	}

	/** Si este estado habilita a atender, suponiendo vigencia y saldo. */
	public boolean habilita() {
		return this == APROBADA;
	}
}
