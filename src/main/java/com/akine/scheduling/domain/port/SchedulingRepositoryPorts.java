package com.akine.scheduling.domain.port;

import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.Turno;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puertos de persistencia de {@code scheduling}.
 *
 * <p>Viven en {@code domain} y no en {@code infrastructure} porque {@code application} consume
 * puertos, nunca repositorios: es la regla que 01.01 dejo fijada y que ArchUnit verifica.
 *
 * <p><b>Ninguna firma resuelve por id pelado.</b> Todas llevan {@code organizationId} —y la sede
 * donde corresponde— en el {@code WHERE}. Es la leccion que M05 aprendio con las memberships de
 * alcance organizacion: sin el predicado, datos de dos ambitos se mezclan bajo un mismo id y el
 * resultado no falla, inventa.
 */
public final class SchedulingRepositoryPorts {

	private SchedulingRepositoryPorts() {
	}

	public interface AgendaSedeRepositoryPort {

		/**
		 * Lock exclusivo sobre la fila de agenda de la sede.
		 *
		 * <p><b>Se toma ANTES de leer un solo turno.</b> Leer primero y bloquear despues es una
		 * escalada S -&gt; X sobre las mismas filas y produce un deadlock entre dos reservas
		 * concurrentes. Esta escrito en el diseno de 02.04 y vale aca sin cambios.
		 */
		Optional<AgendaSede> lockByScope(long organizationId, long consultorioId);

		/**
		 * Crea la fila si no existe. <b>Sin lanzar nunca.</b>
		 *
		 * <p>Leer y despues insertar tiene dos defectos que solo aparecen con concurrencia real:
		 * las N primeras reservas de una sede leen "no existe" y las N intentan el mismo INSERT
		 * —lo que InnoDB resuelve con un DEADLOCK y no con una violacion limpia—, y envolverlo en
		 * un try/catch no alcanza porque atrapar una excepcion de persistencia no des-marca la
		 * transaccion. Ver la implementacion.
		 */
		void crearSiFalta(long organizationId, long consultorioId);
	}

	public interface TurnoRepositoryPort {

		Turno save(Turno turno);

		Optional<Turno> findByIdInScope(long organizationId, long consultorioId, long turnoId);

		/**
		 * El turno creado con esa clave de idempotencia, si existe.
		 *
		 * <p>El alcance es la ORGANIZACION y no la sede, igual que el unique de V30: una clave
		 * reusada apuntando a otra sede sigue siendo la misma clave, y devolver dos turnos
		 * distintos para ella romperia la promesa de idempotencia en el unico caso en que
		 * importa —el reintento de un cliente confundido—.
		 */
		Optional<Turno> findByIdempotencyKey(long organizationId, String idempotencyKey);

		/**
		 * Turnos vivos de un profesional que se cruzan con {@code [inicio, fin)}.
		 *
		 * <p><b>Se cruzan, no coinciden.</b> Dos ofertas de duraciones distintas producen slots
		 * que no caen en la misma grilla, asi que comparar igualdad de {@code inicio} dejaria
		 * pasar un turno de 09:30 sobre uno de 09:00 a 10:00. Ningun unique de la base puede
		 * expresar esto; lo decide esta consulta, bajo el lock de la sede.
		 */
		List<Turno> findVivosDeProfesionalQueCruzan(
				long organizationId, long profesionalMembershipId, Instant inicio, Instant fin);

		/** Lo mismo para un espacio. Ver {@link #findVivosDeProfesionalQueCruzan}. */
		List<Turno> findVivosDeEspacioQueCruzan(
				long organizationId, long espacioId, Instant inicio, Instant fin);

		/** Cuantos turnos vivos ocupan ese slot. Es el cupo consumido de una oferta grupal. */
		long contarVivosEnSlot(long organizationId, long ofertaId, Instant inicio);
	}
}
