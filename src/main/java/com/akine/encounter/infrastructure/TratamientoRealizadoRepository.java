package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.TratamientoRealizado;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TratamientoRealizadoRepository
		extends JpaRepository<TratamientoRealizado, Long>, TratamientoRepositoryPort {

	/**
	 * {@inheritDoc}
	 *
	 * <p>La sesion entra en el {@code WHERE} y no se verifica despues: un id de otra sesion
	 * devuelve vacio, y el llamador contesta 404 sin tener que acordarse de descartar nada.
	 *
	 * <p><b>Sin filtro por {@code active}</b>, y es deliberado: dar de baja es idempotente y tiene
	 * que poder encontrar la fila ya dada de baja para no inventarle un 404 al segundo click.
	 */
	@Override
	@Query("""
			SELECT t FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			   AND t.id = :tratamientoId
			""")
	Optional<TratamientoRealizado> findEnSesion(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId,
			@Param("tratamientoId") long tratamientoId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>{@code ORDER BY orden} sale del indice {@code ix_tratamiento_sesion}, que lo lleva como
	 * tercera columna justamente para eso.
	 */
	@Override
	@Query("""
			SELECT t FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			   AND t.active = true
			 ORDER BY t.orden
			""")
	List<TratamientoRealizado> listarVigentes(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>Sin filtro por {@code active}</b>: las bajas cuentan. Ver el javadoc del puerto —el
	 * {@code orden} no se reutiliza, y reutilizarlo dejaria pasar dos vigentes con el mismo numero
	 * apenas alguien diera de baja uno y creara otro.
	 *
	 * <p>{@code COALESCE} y no {@code Optional}: una sesion sin tratamientos tiene que devolver
	 * cero para que el primero sea 1, y desempaquetar un nulo en un {@code int} seria un
	 * {@code NullPointerException} en el caso mas frecuente de todos.
	 */
	@Override
	@Query("""
			SELECT COALESCE(MAX(t.orden), 0) FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			""")
	int ultimoOrden(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);

	/** {@inheritDoc} */
	@Override
	@Query("""
			SELECT DISTINCT t.practicaId FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			   AND t.active = true
			""")
	List<Long> practicasVigentesDe(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);
}
