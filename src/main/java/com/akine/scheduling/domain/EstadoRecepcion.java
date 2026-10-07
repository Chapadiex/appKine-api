package com.akine.scheduling.domain;

/**
 * Estados de la RECEPCION de un turno (M13, DP-16).
 *
 * <p>DP-05 separa tres maquinas: Turno es la reserva, Recepcion es llegada, validacion
 * administrativa, espera y llamado, y Sesion es la atencion. <b>Ningun estado de aca prueba que
 * la prestacion ocurrio</b>: {@code LLAMADA} no es "atendido", y la Sesion se puede abrir con la
 * recepcion en cualquier estado, o sin recepcion.
 *
 * <pre>
 *   (nada)    --llegada-->      LLEGO
 *   LLEGO     --validar-->      VALIDADA | OBSERVADA
 *   OBSERVADA --validar-->      VALIDADA | OBSERVADA   (revalidar: trajo la orden)
 *   LLEGO     --particular-->   VALIDADA               (modalidad PARTICULAR, con motivo)
 *   OBSERVADA --particular-->   VALIDADA
 *   VALIDADA  --espera-->       EN_ESPERA
 *   OBSERVADA --espera-->       EN_ESPERA              (la observacion advierte, no bloquea)
 *   EN_ESPERA --llamar-->       LLAMADA
 *   abierta   --anular-->       ANULADA                (check-in por error: la llegada no vale)
 *   abierta   --cancelacion del turno--> CERRADA       (la llegada SI vale)
 * </pre>
 */
public enum EstadoRecepcion {

	/** La persona llego. Todavia no se resolvio como se atiende. */
	LLEGO,

	/** Se sabe como se atiende: con cobertura elegible o como Particular. */
	VALIDADA,

	/** La validacion encontro algo: falta orden, no hay cobertura aplicable, etc. No bloquea. */
	OBSERVADA,

	/** En la sala, esperando que la llamen. */
	EN_ESPERA,

	/** La llamaron. Es el ultimo paso de la recepcion; lo que sigue es la Sesion. */
	LLAMADA,

	/** El check-in fue un error y se anulo. Terminal; la fila queda como historia. */
	ANULADA,

	/** El turno se cancelo con la persona presente. Terminal; la llegada consta. */
	CERRADA;

	/** {@code true} mientras la recepcion no termino: todo salvo ANULADA y CERRADA. */
	public boolean estaAbierta() {
		return this != ANULADA && this != CERRADA;
	}
}
