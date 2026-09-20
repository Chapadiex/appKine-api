package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.TratamientoParametro;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoParametroRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TratamientoParametroRepository
		extends JpaRepository<TratamientoParametro, Long>, TratamientoParametroRepositoryPort {

	@Override
	@Query("""
			SELECT p FROM TratamientoParametro p
			 WHERE p.organizationId = :organizationId
			   AND p.tratamientoRealizadoId = :tratamientoId
			 ORDER BY p.orden, p.clave
			""")
	List<TratamientoParametro> listarDe(
			@Param("organizationId") long organizationId,
			@Param("tratamientoId") long tratamientoId);

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>{@code flushAutomatically} y {@code clearAutomatically} son obligatorios acá, no
	 * adorno.</b> El {@code PUT} borra y reinserta en la misma transaccion: sin el flush previo,
	 * las inserciones pendientes en la sesion de JPA se emitirian DESPUES del {@code DELETE} y se
	 * perderian; sin el clear posterior, las entities borradas siguen en el contexto de
	 * persistencia y un {@code INSERT} con la misma clave choca contra
	 * {@code uk_tratamiento_parametro_clave} por una fila que ya no existe en la base.
	 */
	@Override
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			DELETE FROM TratamientoParametro p
			 WHERE p.organizationId = :organizationId
			   AND p.tratamientoRealizadoId = :tratamientoId
			""")
	void borrarDe(
			@Param("organizationId") long organizationId,
			@Param("tratamientoId") long tratamientoId);
}
