package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.CasoSesionNumerador;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoSesionNumeradorPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Asignacion atomica del correlativo de sesion <b>dentro del Caso</b> (regla maestra 3).
 *
 * <p>Mismo mecanismo que {@link CasoNumeradorRepository} y misma razon. Lo que cambia es el
 * consumidor: esto lo pide {@code encounter} a traves de {@code clinical.spi.CasoDirectory},
 * dentro de su transaccion de cierre, y <b>despues</b> de haber tomado el numerador de la
 * historia. El orden entre los dos numeradores es fijo: ver {@code SesionService#cerrar}.
 *
 * <p><b>Reabrir el caso no reinicia el contador</b>, y no hay ninguna operacion que lo haga: la
 * sesion siguiente a una reapertura es la 9 y no la 1. Renumerar seria reescribir historia clinica
 * (ADR-0011).
 */
public interface CasoSesionNumeradorRepository
		extends JpaRepository<CasoSesionNumerador, Long>, CasoSesionNumeradorPort {

	/** Crea la fila si no existe. <b>Sin lanzar nunca.</b> Ver {@link CasoNumeradorRepository}. */
	@Modifying
	@Query(value = """
			INSERT INTO caso_sesion_numerador (organization_id, caso_id, ultimo_numero,
			                                   created_at, updated_at)
			VALUES (:organizationId, :casoId, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId,
			@Param("casoId") long casoId);

	/** Incrementa tomando el lock de fila. Serializa los cierres de sesiones de ESE caso. */
	@Modifying
	@Query(value = """
			UPDATE caso_sesion_numerador
			   SET ultimo_numero = ultimo_numero + 1
			 WHERE organization_id = :organizationId
			   AND caso_id = :casoId
			""", nativeQuery = true)
	@Override
	void incrementar(
			@Param("organizationId") long organizationId,
			@Param("casoId") long casoId);

	/** Lee el numero recien asignado, en la misma transaccion que lo incremento. */
	@Query(value = """
			SELECT ultimo_numero FROM caso_sesion_numerador
			 WHERE organization_id = :organizationId
			   AND caso_id = :casoId
			""", nativeQuery = true)
	@Override
	Integer leerUltimo(
			@Param("organizationId") long organizationId,
			@Param("casoId") long casoId);
}
