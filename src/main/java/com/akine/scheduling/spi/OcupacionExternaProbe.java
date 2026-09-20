package com.akine.scheduling.spi;

import java.time.Instant;

/**
 * Pregunta si algo que <b>no es un turno</b> ocupa un recurso en un intervalo. Es la costura hacia
 * M28 en el camino de escritura.
 *
 * <h2>Por que la interfaz vive aca y no en {@code activity}</h2>
 *
 * <p>{@code activity} ya depende de {@code scheduling} —la clase se disputa su lock por
 * {@link AgendaDeSede}—. Si {@code scheduling} importara algo de {@code activity} para hacer esta
 * pregunta, la dependencia entre los dos modulos dejaria de ser unidireccional y ArchUnit
 * rechazaria el build. La inversion es la salida estandar de este proyecto: <b>el que pregunta
 * declara el contrato, el que sabe lo implementa</b>. Mismo patron que {@link AtencionProbe} y que
 * {@code resource.spi.DisponibilidadImpactProbe}.
 *
 * <h2>Y por que preguntar bien no alcanza</h2>
 *
 * <p>Esta lectura solo es una garantia <b>si corre bajo el lock de {@code agenda_sede}</b>, dentro
 * de la transaccion que escribe. Fuera de ahi es una foto: entre que contesta {@code false} y que
 * el turno se inserta, una clase puede haber tomado el box. {@code RevalidadorDeSlot} la llama en
 * el lugar correcto; cualquier otro llamador tiene que leer esto antes.
 *
 * <h2>Por que devuelve un booleano y no la lista</h2>
 *
 * <p>Porque el desenlace es el mismo cualquiera sea el evento que estorba: {@code recurso-ocupado}.
 * Devolver el detalle invitaria a M12 a razonar sobre las reglas de M28 —que clases "cuentan"— y
 * esa decision es del modulo que las posee.
 */
public interface OcupacionExternaProbe {

	/** {@code true} si algun evento que no es turno ocupa a ese profesional en el intervalo. */
	boolean profesionalOcupado(
			long organizationId, long profesionalMembershipId, Instant inicio, Instant fin);

	/** {@code true} si algun evento que no es turno ocupa ese espacio en el intervalo. */
	boolean espacioOcupado(long organizationId, long espacioId, Instant inicio, Instant fin);
}
