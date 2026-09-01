package com.akine.billing.infrastructure;

import com.akine.billing.domain.ComprobanteNumerador;
import com.akine.billing.domain.port.ComprobanteNumeradorPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Correlativo de comprobantes por sede.
 *
 * <p>Mismo patron que {@code SesionNumeradorRepository} y por la misma razon: un
 * {@code SELECT MAX + 1} deja una ventana entre leer y escribir, y dos cobros concurrentes se
 * llevan el mismo numero. <b>Un comprobante repetido es un problema fiscal, no un detalle.</b>
 */
public interface ComprobanteNumeradorRepository
		extends JpaRepository<ComprobanteNumerador, Long>, ComprobanteNumeradorPort {

	@Modifying
	@Query(value = """
			INSERT INTO comprobante_numerador (organization_id, consultorio_id, ultimo_numero,
			                                   created_at, updated_at)
			VALUES (:organizationId, :consultorioId, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);

	@Modifying
	@Query(value = """
			UPDATE comprobante_numerador
			   SET ultimo_numero = ultimo_numero + 1
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			""", nativeQuery = true)
	@Override
	void incrementar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);

	@Query(value = """
			SELECT ultimo_numero FROM comprobante_numerador
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			""", nativeQuery = true)
	@Override
	Integer leerUltimo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);
}
