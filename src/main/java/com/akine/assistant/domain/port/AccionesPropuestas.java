package com.akine.assistant.domain.port;

import com.akine.assistant.domain.AccionPropuesta;

import java.time.Instant;
import java.util.Optional;

/**
 * Las acciones propuestas por el asistente. Cada cambio de estado es una sentencia condicionada,
 * commiteada sola: la confirmacion llama despues a servicios que exigen no tener transaccion activa.
 */
public interface AccionesPropuestas {

	/** @return el id asignado */
	long guardar(AccionPropuesta nueva);

	/** Solo la de esa cuenta, en esa sede y organizacion: cualquier otra no existe para quien pregunta. */
	Optional<AccionPropuesta> buscar(long id, long organizationId, long consultorioId, long cuentaId);

	/**
	 * {@code PROPUESTA -> EN_CURSO}, solo si no vencio.
	 *
	 * @return {@code true} si esta llamada la tomo; {@code false} si otra se adelanto, vencio o ya
	 *         esta resuelta
	 */
	boolean tomar(long id, Instant ahora);

	/** {@code EN_CURSO -> PROPUESTA}: la ejecucion no grabo nada y se puede corregir y reintentar. */
	void soltar(long id);

	/** {@code EN_CURSO -> CONFIRMADA}, con el resultado como JSON. */
	void confirmar(long id, String resultadoJson, Instant ahora);

	/** {@code PROPUESTA -> DESCARTADA}. @return {@code false} si ya no estaba propuesta */
	boolean descartar(long id, Instant ahora);
}
