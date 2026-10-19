package com.akine.activity.domain.port;

import com.akine.activity.domain.ClaseInstructor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Los puertos de {@code activity} para los honorarios por clase (AKINE-08.09b, DP-38). Toda
 * consulta lleva {@code organizationId}.
 */
public final class HonorariosRepositoryPorts {

	private HonorariosRepositoryPorts() {
	}

	/** Instructores adicionales de las clases. */
	public interface ClaseInstructorRepositoryPort {

		ClaseInstructor save(ClaseInstructor instructor);

		/** Los vigentes de una clase, por membership ascendente: el orden estable del reparto. */
		List<ClaseInstructor> vigentesDe(long organizationId, long claseId);

		/** Los vigentes de varias clases, por clase y membership ascendente. */
		List<ClaseInstructor> vigentesDeLasClases(long organizationId, Collection<Long> claseIds);
	}

	/**
	 * Lo que el borrador de honorarios lee de las clases y las asistencias. Nativas: ver la
	 * implementacion.
	 */
	public interface BaseDeHonorariosPort {

		/**
		 * Las clases REALIZADA, vivas, de la sede, con inicio en {@code [desde, hasta)}, donde el
		 * colaborador es el principal o un adicional vigente. Filas:
		 * {@code [id, oferta_id, inicio (Instant), titulo, profesional_membership_id]}.
		 */
		List<Object[]> clasesDictadas(
				long organizationId, long consultorioId, long colaboradorMembershipId,
				Instant desde, Instant hasta);

		/**
		 * Lo mismo, tomando las filas {@code FOR UPDATE} en orden de id: el punto de serializacion
		 * de dos confirmaciones simultaneas. Solo dentro de una transaccion.
		 */
		List<Object[]> clasesDictadasParaLiquidar(
				long organizationId, long consultorioId, long colaboradorMembershipId,
				Instant desde, Instant hasta);

		/** Presentes por clase (PRESENTE + PRESENTE_TARDE): {@code [clase_id, cantidad]}. */
		List<Object[]> presentesPorClase(long organizationId, Collection<Long> claseIds);
	}
}
