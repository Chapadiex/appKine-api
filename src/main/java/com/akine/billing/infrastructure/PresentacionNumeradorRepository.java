package com.akine.billing.infrastructure;

import com.akine.billing.domain.PresentacionNumerador;
import com.akine.billing.domain.port.PresentacionNumeradorPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Correlativo de lotes por sede y financiador.
 *
 * <p>Mismo patron que {@code ComprobanteNumeradorRepository} y {@code SesionNumeradorRepository}, y
 * por la misma razon: un {@code SELECT MAX + 1} deja una ventana entre leer y escribir, y dos
 * confirmaciones concurrentes del mismo financiador se llevan el mismo numero. <b>Un lote repetido
 * hace que el financiador y el centro estén hablando de cosas distintas con el mismo nombre.</b>
 */
public interface PresentacionNumeradorRepository
		extends JpaRepository<PresentacionNumerador, Long>, PresentacionNumeradorPort {

	@Modifying
	@Query(value = """
			INSERT INTO presentacion_numerador (organization_id, consultorio_id, financiador_id,
			                                    ultimo_numero, created_at, updated_at)
			VALUES (:organizationId, :consultorioId, :financiadorId, 0,
			        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("financiadorId") long financiadorId);

	@Modifying
	@Query(value = """
			UPDATE presentacion_numerador
			   SET ultimo_numero = ultimo_numero + 1
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			   AND financiador_id = :financiadorId
			""", nativeQuery = true)
	@Override
	void incrementar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("financiadorId") long financiadorId);

	@Query(value = """
			SELECT ultimo_numero FROM presentacion_numerador
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			   AND financiador_id = :financiadorId
			""", nativeQuery = true)
	@Override
	Integer leerUltimo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("financiadorId") long financiadorId);
}
