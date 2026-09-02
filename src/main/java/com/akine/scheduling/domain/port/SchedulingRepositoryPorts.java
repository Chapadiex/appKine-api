package com.akine.scheduling.domain.port;

import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;

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

		/**
		 * Guarda y <b>vacia la sesion en el acto</b>, para que la respuesta lleve la version nueva.
		 *
		 * <p>Con {@code save} a secas, el {@code @Version} lo incrementa Hibernate al vaciar la
		 * sesion, que ocurre al commitear la transaccion: la vista se arma <b>antes</b> y sale con
		 * la version vieja. La base queda en 1 y el cliente se lleva un 0.
		 *
		 * <p>Se comprobo contra MySQL real y no es cosmetico. Reprogramar un turno devolvia 200 con
		 * la version vieja; reprogramar de nuevo usando <b>la version que la propia API acababa de
		 * devolver</b> daba 409 "el recurso fue modificado por otra operacion" sin que nadie lo
		 * hubiera tocado. En los hechos, un turno se podia mover una sola vez sin recargar, y el
		 * mensaje culpaba a un operador inexistente.
		 */
		Turno saveAndFlush(Turno turno);

		Optional<Turno> findByIdInScope(long organizationId, long consultorioId, long turnoId);

		/**
		 * Los turnos de una sede que empiezan dentro de {@code [desde, hasta)}, del mas temprano
		 * al mas tarde. Es la agenda del dia de la recepcion (M13).
		 *
		 * <p><b>Trae tambien los cancelados.</b> A diferencia de las consultas de solapamiento,
		 * que filtran por {@code deletedAt IS NULL} porque preguntan por ocupacion, esta pregunta
		 * por <b>que paso hoy</b>: alguien puede presentarse al mostrador con un turno que se
		 * cancelo, y esconderlo deja a la recepcionista sin nada que decirle.
		 *
		 * <p>Se filtra por {@code inicio} y no por solapamiento porque la recepcion piensa en
		 * "los turnos de hoy", que son los que empiezan hoy. Un turno que arranco ayer 23:30 y
		 * termina hoy 00:30 pertenece a la agenda de ayer, que es donde su paciente lo busca.
		 */
		List<Turno> findDeLaSedeEnVentana(
				long organizationId, long consultorioId, Instant desde, Instant hasta);

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

		/**
		 * Los turnos vivos de una oferta dentro de una ventana.
		 *
		 * <p>Es lo que el motor de slots usa para descontar el cupo ya vendido. Sin esta lectura la
		 * agenda ofrece huecos que ya tienen turno: no corrompe nada —la reserva revalida bajo el
		 * lock— pero convierte un caso normal en un 409 que el usuario no se merece.
		 */
		List<Turno> findVivosDeLaOfertaEnVentana(
				long organizationId, long ofertaId, Instant desde, Instant hasta);

		/**
		 * Los turnos de una persona en la organizacion, mas nuevos primero (AKINE-03.02).
		 *
		 * <p>Lo consume el Paciente 360 a traves de {@code TurnosEnElResumenDePersona}.
		 *
		 * <p><b>Alcance ORGANIZACION y no sede.</b> La Persona pertenece a la organizacion, y el
		 * mostrador que abre su ficha necesita saber que tiene turno el jueves en la otra sede del
		 * mismo centro. Filtrar por la sede del contexto haria que la misma ficha se viera distinta
		 * segun donde este parado el operador, que es la clase de inconsistencia que produce
		 * sobreturnos.
		 *
		 * <p><b>Incluye los cancelados.</b> Un turno cancelado es informacion de la ficha —"vino
		 * tres veces y cancelo dos"— y esconderlo dejaria al 360 contando una historia incompleta.
		 * Separarlos por estado es trabajo del contribuyente, no de la consulta.
		 */
		List<Turno> findDeLaPersona(long organizationId, long personaId, int limite);
	}

	/**
	 * Historial de transiciones (RF-M12-008). <b>Solo escribe e itera: no actualiza ni borra.</b>
	 *
	 * <p>La ausencia de {@code delete} y de {@code update} en este contrato no es un olvido: es la
	 * unica garantia real de que el historial sea inmutable. La misma decision que
	 * {@code platform.spi.audit.AuditTrail} tomo para la auditoria.
	 */
	public interface TurnoEventoRepositoryPort {

		TurnoEvento registrar(TurnoEvento evento);

		/** Los eventos de un turno, del mas viejo al mas nuevo. */
		List<TurnoEvento> historial(long organizationId, long turnoId);
	}
}
