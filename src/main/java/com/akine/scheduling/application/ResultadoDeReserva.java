package com.akine.scheduling.application;

/**
 * El turno reservado, y si se creo ahora o ya existia.
 *
 * <p>Existe para que la capa web pueda distinguir <b>201 de 200</b>. Sin este dato el controller
 * responde 201 siempre, y un reintento idempotente afirma que creo algo que no creo — el contrato
 * promete 200 para ese caso y el codigo lo contradecia en silencio. Lo destapo el QA manual contra
 * el stack real: dos POST con la misma clave, dos 201, y una sola fila en la base.
 *
 * <p>No se resuelve mirando {@code reservadoEn} contra el reloj: eso seria una heuristica que falla
 * el dia que dos peticiones caen en el mismo milisegundo, y ademas no se puede leer.
 *
 * @param creado {@code false} cuando la clave de idempotencia ya tenia un turno
 */
public record ResultadoDeReserva(TurnoView turno, boolean creado) {
}
