package com.akine.billing.infrastructure;

import com.akine.billing.domain.FinanciadorPago;
import com.akine.billing.domain.port.FinanciadorPagoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface FinanciadorPagoRepository
		extends JpaRepository<FinanciadorPago, Long>, FinanciadorPagoRepositoryPort {

	@Override
	@Query("""
			SELECT p FROM FinanciadorPago p
			 WHERE p.organizationId = :organizationId
			   AND p.idempotencyKey = :idempotencyKey
			""")
	Optional<FinanciadorPago> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	@Override
	@Query("""
			SELECT p FROM FinanciadorPago p
			 WHERE p.presentacionId = :presentacionId
			 ORDER BY p.id ASC
			""")
	List<FinanciadorPago> findDeLaPresentacion(@Param("presentacionId") long presentacionId);

	/** Cruza sedes a proposito: la relacion comercial es de la organizacion. Ver el puerto. */
	@Override
	@Query("""
			SELECT COALESCE(SUM(p.importe), 0) FROM FinanciadorPago p
			 WHERE p.organizationId = :organizationId
			   AND p.financiadorId = :financiadorId
			""")
	BigDecimal totalPagadoPorFinanciador(
			@Param("organizationId") long organizationId,
			@Param("financiadorId") long financiadorId);
}
