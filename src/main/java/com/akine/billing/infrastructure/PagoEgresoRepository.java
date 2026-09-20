package com.akine.billing.infrastructure;

import com.akine.billing.domain.PagoEgreso;
import com.akine.billing.domain.port.PagoEgresoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Pagos de egreso. <b>Sin delete</b>: anular cambia el estado y conserva la fila (RN-M22-002).
 */
public interface PagoEgresoRepository
		extends JpaRepository<PagoEgreso, Long>, PagoEgresoRepositoryPort {

	/**
	 * Acotado al egreso ademas del tenant.
	 *
	 * <p>Sin el predicado del egreso, pedir el pago de otro egreso por la ruta de este devolveria
	 * un 200 con datos que no corresponden a la URL. Da 404, que es lo correcto.
	 */
	@Override
	@Query("""
			SELECT p FROM PagoEgreso p
			 WHERE p.organizationId = :organizationId
			   AND p.egresoId = :egresoId
			   AND p.id = :pagoId
			""")
	Optional<PagoEgreso> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("egresoId") long egresoId,
			@Param("pagoId") long pagoId);

	@Override
	@Query("""
			SELECT p FROM PagoEgreso p
			 WHERE p.organizationId = :organizationId
			   AND p.idempotencyKey = :idempotencyKey
			""")
	Optional<PagoEgreso> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	/** <b>Incluye los anulados</b>: un pago que se anulo es parte del historial, no un error a ocultar. */
	@Override
	@Query("""
			SELECT p FROM PagoEgreso p
			 WHERE p.organizationId = :organizationId
			   AND p.egresoId = :egresoId
			 ORDER BY p.pagadoEn DESC, p.id DESC
			""")
	List<PagoEgreso> findDelEgreso(
			@Param("organizationId") long organizationId,
			@Param("egresoId") long egresoId);

	/**
	 * Suma de los pagos vigentes. <b>No gobierna: confronta.</b>
	 *
	 * <p>Quien opera usa {@code egreso.saldo_pendiente}; esto existe para poder confrontar las dos
	 * cifras, y si divergen la que miente es la columna.
	 */
	@Override
	@Query("""
			SELECT COALESCE(SUM(p.importe), 0) FROM PagoEgreso p
			 WHERE p.organizationId = :organizationId
			   AND p.egresoId = :egresoId
			   AND p.estado = com.akine.billing.domain.EstadoPagoEgreso.CONFIRMADO
			""")
	BigDecimal totalPagadoVigente(
			@Param("organizationId") long organizationId,
			@Param("egresoId") long egresoId);
}
