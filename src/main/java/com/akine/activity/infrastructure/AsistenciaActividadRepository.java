package com.akine.activity.infrastructure;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaActividadRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de asistencias (M28, AKINE-08.03).
 *
 * <p><b>No hay ningun {@code delete}, y no es un olvido.</b> Una asistencia no se da de baja: se
 * corrige, y la correccion apendea en {@code AsistenciaEventoRepository}. Si existiera un borrado,
 * el unique {@code uk_asistencia_clase_persona} —la referencia economica unica— dejaria de
 * significar "a lo sumo un hecho por participante", que es justo lo que hace que cerrar dos veces
 * no duplique obligaciones.
 *
 * <p><b>Y ninguna consulta de aca decide el cupo.</b> Marcar asistencia va de un estado que consume
 * lugar a otro que tambien lo consume: {@code cupo_ocupado} no se mueve en esta etapa.
 */
public interface AsistenciaActividadRepository
		extends JpaRepository<AsistenciaActividad, Long>, AsistenciaActividadRepositoryPort {

	@Override
	@Query("""
			SELECT a FROM AsistenciaActividad a
			 WHERE a.organizationId = :organizationId
			   AND a.claseId = :claseId
			   AND a.id = :asistenciaId
			""")
	Optional<AsistenciaActividad> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("asistenciaId") long asistenciaId);

	/**
	 * <b>Es el control de idempotencia entero de la etapa.</b> Por eso no hay
	 * {@code Idempotency-Key}: la clave natural del hecho ya vive en un unique, y una clave habria
	 * dejado el agujero de que dos claves distintas produzcan dos asistencias para la misma
	 * persona.
	 */
	@Override
	@Query("""
			SELECT a FROM AsistenciaActividad a
			 WHERE a.organizationId = :organizationId
			   AND a.inscripcionId = :inscripcionId
			""")
	Optional<AsistenciaActividad> findDeInscripcion(
			@Param("organizationId") long organizationId,
			@Param("inscripcionId") long inscripcionId);

	@Override
	@Query("""
			SELECT a FROM AsistenciaActividad a
			 WHERE a.organizationId = :organizationId
			   AND a.claseId = :claseId
			 ORDER BY a.id ASC
			""")
	List<AsistenciaActividad> findDeLaClase(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	/**
	 * {@code PRESENTE} y {@code PRESENTE_TARDE} cuentan los dos: para la cabecera de la clase,
	 * llegar tarde es haber venido. La distincion la conserva la fila, por si 08.07 la necesita.
	 */
	@Override
	default int contarPresentes(long organizationId, long claseId) {
		return contarPorResultados(
				organizationId, claseId,
				List.of(ResultadoAsistencia.PRESENTE, ResultadoAsistencia.PRESENTE_TARDE));
	}

	@Override
	default int contarAusentes(long organizationId, long claseId) {
		return contarPorResultados(organizationId, claseId, List.of(ResultadoAsistencia.AUSENTE));
	}

	@Query("""
			SELECT COUNT(a) FROM AsistenciaActividad a
			 WHERE a.organizationId = :organizationId
			   AND a.claseId = :claseId
			   AND a.resultado IN :resultados
			""")
	int contarPorResultados(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("resultados") List<ResultadoAsistencia> resultados);
}
