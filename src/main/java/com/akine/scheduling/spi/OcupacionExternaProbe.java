package com.akine.scheduling.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

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
 *
 * <p>Para el profesional si se devuelve el evento (agenda de clases y avisos, contrato 0.156.0): el
 * rechazo tiene que poder decir "tiene una clase en otra sede de 10 a 11", y la agenda operativa
 * necesita los intervalos para no dibujar libre a quien esta en otra sede. Que clases cuentan lo
 * sigue decidiendo {@code activity}.
 */
public interface OcupacionExternaProbe {

	/**
	 * Las clases vivas que ocupan a alguna de esas memberships y se cruzan con {@code [inicio, fin)},
	 * <b>en toda la organizacion</b>, no solo en una sede.
	 *
	 * <p>La misma persona tiene una membership por sede, asi que quien pregunta pasa <b>todas</b> las
	 * de la cuenta ({@code resource.spi.BloqueoDeAgendaDeProfesional#deLaMismaPersona}). Antes la
	 * pregunta era por una sola membership y una clase en la Sede Centro dejaba libre a la misma
	 * persona en la Sede Norte. Ocupa tanto el profesional principal como cada instructor adicional
	 * vigente: una fila por clase y por membership ocupada.
	 *
	 * <p>Bajo el lock de la persona ({@code agenda_profesional}) es una garantia; fuera, una foto.
	 */
	List<OcupacionExterna> ocupacionDeProfesionales(
			long organizationId, Collection<Long> membershipIds, Instant inicio, Instant fin);

	/** {@code true} si algun evento que no es turno ocupa ese espacio en el intervalo. */
	boolean espacioOcupado(long organizationId, long espacioId, Instant inicio, Instant fin);

	/**
	 * Un evento que no es turno y ocupa a una membership.
	 *
	 * @param tipo                    {@code CLASE} hoy
	 * @param eventoId                id dentro de su agregado (no es unico entre tipos)
	 * @param consultorioId           sede del evento: puede no ser la de quien pregunta
	 * @param profesionalMembershipId la membership ocupada (principal o instructor adicional)
	 */
	record OcupacionExterna(
			String tipo, long eventoId, long consultorioId, long profesionalMembershipId,
			Instant inicio, Instant fin) {

		public boolean seCruzaCon(Instant desde, Instant hasta) {
			return inicio.isBefore(hasta) && desde.isBefore(fin);
		}
	}
}
