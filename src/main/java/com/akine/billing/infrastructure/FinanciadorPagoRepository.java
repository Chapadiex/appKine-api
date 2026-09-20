package com.akine.billing.infrastructure;

import com.akine.billing.domain.FinanciadorPago;
import com.akine.billing.domain.port.FinanciadorPagoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
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

	// =================================================================================
	// M23 — agregaciones de reporte (AKINE-07.06)
	// =================================================================================

	/**
	 * Lo que los financiadores efectivamente pagaron en el periodo (RF-M23-005).
	 *
	 * <p>Corta por {@code fecha_pago} —cuando pago el financiador— y no por {@code registrado_en}
	 * —cuando el administrativo lo cargo—. Los dos instantes se separan legitimamente por dias, y
	 * usar el segundo le atribuiria a septiembre un pago de agosto que se cargo tarde.
	 *
	 * <p><b>No se suma con el {@code cobrado} del reporte economico.</b> Un pago de financiador es
	 * dinero del financiador; lo que el paciente paga en el mostrador es otra cosa y sale de M19.
	 * Son dos de los cinco conceptos, y este reporte no publica ningun total que los junte.
	 */
	@Query("""
			SELECT SUM(p.importe) FROM FinanciadorPago p
			 WHERE p.organizationId = :organizationId
			   AND p.consultorioId = :consultorioId
			   AND p.fechaPago >= :desde
			   AND p.fechaPago <= :hasta
			""")
	BigDecimal sumarPagadoEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta);
}
