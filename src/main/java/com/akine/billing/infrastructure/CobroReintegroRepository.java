package com.akine.billing.infrastructure;

import com.akine.billing.domain.CobroReintegro;
import com.akine.billing.domain.port.CobroReintegroRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Reintegros de saldo a favor. Solo lecturas y un {@code save}: append-only. */
public interface CobroReintegroRepository
		extends JpaRepository<CobroReintegro, Long>, CobroReintegroRepositoryPort {

	@Override
	@Query("""
			SELECT r FROM CobroReintegro r
			 WHERE r.organizationId = :organizationId
			   AND r.idempotencyKey = :idempotencyKey
			""")
	Optional<CobroReintegro> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	@Override
	@Query("""
			SELECT COUNT(r) > 0 FROM CobroReintegro r
			 WHERE r.organizationId = :organizationId
			   AND r.cobroId = :cobroId
			""")
	boolean existenDelCobro(
			@Param("organizationId") long organizationId,
			@Param("cobroId") long cobroId);
}
