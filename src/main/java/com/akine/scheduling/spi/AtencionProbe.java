package com.akine.scheduling.spi;

/**
 * Pregunta si un turno ya tiene una atencion registrada. Es la costura hacia M14.
 *
 * <h2>Por que la interfaz vive aca y no en {@code encounter}</h2>
 *
 * <p>{@code encounter} ya depende de {@code scheduling} —la Sesion arranca desde el Turno y lee su
 * snapshot por {@code TurnoDirectory}—. Si {@code scheduling} importara algo de {@code encounter}
 * para hacer esta pregunta, la dependencia entre los dos modulos dejaria de ser unidireccional y
 * ArchUnit rechazaria el build. La inversion es la salida estandar de este proyecto: el que
 * pregunta declara el contrato, el que sabe lo implementa. Mismo patron que
 * {@link ReservaProbe} y que {@code resource.spi.DisponibilidadImpactProbe}.
 *
 * <h2>Que garantiza y que no</h2>
 *
 * <p><b>No autoriza nada</b> y no dice si la atencion esta abierta o cerrada: dice que existe. Las
 * dos respuestas llevan al mismo desenlace en M12 —el turno ya no se puede deshacer— y distinguir
 * entre ellas obligaria a {@code scheduling} a conocer la maquina de estados de la Sesion, que es
 * exactamente lo que DP-05 separa.
 *
 * <p>Es una lectura, o sea una <b>foto</b>. Una sesion puede iniciarse un instante despues de que
 * esto conteste {@code false}. No es un agujero de correctitud: la Sesion cuelga del turno por su
 * id y una cancelacion posterior al inicio de la atencion es un problema de dos personas
 * trabajando sobre el mismo paciente al mismo tiempo, no de dos escrituras que se pisan.
 */
public interface AtencionProbe {

	/**
	 * {@code true} si existe una Sesion viva —abierta o cerrada— para ese turno.
	 *
	 * @param consultorioId sede del turno. Va en la firma como todas las de este proyecto, aunque
	 *                      el turno pertenezca a una sola sede: sin el predicado, un llamador
	 *                      equivocado obtiene una respuesta que no falla, miente
	 */
	boolean tieneAtencion(long organizationId, long consultorioId, long turnoId);
}
