package com.akine.activity.domain.port;

import com.akine.activity.domain.ClaseEvento;
import com.akine.activity.domain.ClaseProgramada;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puertos de persistencia de {@code activity}.
 *
 * <p>Viven en {@code domain} y no en {@code infrastructure} porque {@code application} consume
 * puertos, nunca repositorios: es la regla que 01.01 dejo fijada y que ArchUnit verifica.
 *
 * <p><b>Ninguna firma resuelve por id pelado.</b> Todas llevan {@code organizationId} —y la sede
 * donde corresponde— en el {@code WHERE}: sin el predicado, datos de dos ambitos se mezclan bajo un
 * mismo id y el resultado no falla, inventa.
 */
public final class ActivityRepositoryPorts {

	private ActivityRepositoryPorts() {
	}

	public interface ClaseProgramadaRepositoryPort {

		ClaseProgramada save(ClaseProgramada clase);

		/**
		 * Guarda y <b>vacia la sesion en el acto</b>, para que la respuesta lleve la version nueva.
		 *
		 * <p>Con {@code save} a secas, el {@code @Version} lo incrementa Hibernate al vaciar la
		 * sesion, que ocurre al commitear: la vista se arma <b>antes</b> y sale con la version
		 * vieja. La base queda en 1 y el cliente se lleva un 0, manda esa version en la operacion
		 * siguiente y come un 409 que no le echa la culpa a nadie. Ya se pago con los turnos.
		 */
		ClaseProgramada saveAndFlush(ClaseProgramada clase);

		Optional<ClaseProgramada> findByIdInScope(
				long organizationId, long consultorioId, long claseId);

		/**
		 * La clase creada con esa clave de idempotencia, si existe.
		 *
		 * <p>Alcance ORGANIZACION y no sede, igual que el unique de {@code V58} y que el de los
		 * turnos: una clave reusada apuntando a otra sede sigue siendo la misma clave.
		 */
		Optional<ClaseProgramada> findByIdempotencyKey(long organizationId, String idempotencyKey);

		/**
		 * Clases vivas de un profesional que se <b>cruzan</b> con {@code [inicio, fin)}.
		 *
		 * <p>Se cruzan, no coinciden: una clase de 09:00 a 10:00 y un turno de 09:30 a 10:00 no
		 * comparten un solo valor de columna. Ningun unique puede expresarlo y MySQL 8.4 no tiene
		 * exclusion constraints; lo decide esta consulta, <b>bajo el lock de {@code agenda_sede}</b>.
		 */
		List<ClaseProgramada> findVivasDeProfesionalQueCruzan(
				long organizationId, long profesionalMembershipId, Instant inicio, Instant fin);

		/** Lo mismo para un espacio. Ver {@link #findVivasDeProfesionalQueCruzan}. */
		List<ClaseProgramada> findVivasDeEspacioQueCruzan(
				long organizationId, long espacioId, Instant inicio, Instant fin);

		/**
		 * Clases de una sede que empiezan dentro de {@code [desde, hasta)}, de la mas temprana a la
		 * mas tarde.
		 *
		 * <p><b>Trae tambien las canceladas</b>, a diferencia de las consultas de solapamiento: esta
		 * pregunta por que hay en la grilla, y alguien puede presentarse a una clase que se
		 * cancelo. Es el mismo criterio que {@code findDeLaSedeEnVentana} de los turnos.
		 */
		List<ClaseProgramada> findDeLaSedeEnVentana(
				long organizationId, long consultorioId, Instant desde, Instant hasta);

		/**
		 * Clases <b>vivas</b> de una sede en una ventana. Es lo que alimenta la exclusion y la
		 * proyeccion de ocupacion; las canceladas no ocupan nada.
		 */
		List<ClaseProgramada> findVivasDeLaSedeEnVentana(
				long organizationId, long consultorioId, Instant desde, Instant hasta);
	}

	/**
	 * Historial de transiciones (RN-M28-009). <b>Solo escribe e itera: no actualiza ni borra.</b>
	 *
	 * <p>La ausencia de {@code delete} y de {@code update} en este contrato no es un olvido: es la
	 * unica garantia real de que el historial sea inmutable. Misma decision que
	 * {@code TurnoEventoRepositoryPort} y que {@code platform.spi.audit.AuditTrail}.
	 */
	public interface ClaseEventoRepositoryPort {

		ClaseEvento registrar(ClaseEvento evento);

		/** Los eventos de una clase, del mas viejo al mas nuevo. */
		List<ClaseEvento> historial(long organizationId, long claseId);
	}
}
