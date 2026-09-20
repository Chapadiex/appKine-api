package com.akine.billing.infrastructure;

import com.akine.billing.domain.PresentacionItem;
import com.akine.billing.domain.port.PresentacionItemRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PresentacionItemRepository
		extends JpaRepository<PresentacionItem, Long>, PresentacionItemRepositoryPort {

	@Override
	@Query("""
			SELECT i FROM PresentacionItem i
			 WHERE i.presentacionId = :presentacionId
			 ORDER BY i.id ASC
			""")
	List<PresentacionItem> findDeLaPresentacion(@Param("presentacionId") long presentacionId);

	@Override
	@Query("""
			SELECT i FROM PresentacionItem i
			 WHERE i.organizationId = :organizationId
			   AND i.presentacionId = :presentacionId
			   AND i.id = :itemId
			""")
	Optional<PresentacionItem> findByIdEnLaPresentacion(
			@Param("organizationId") long organizationId,
			@Param("presentacionId") long presentacionId,
			@Param("itemId") long itemId);

	/**
	 * Espeja la columna generada {@code ocupa_marca} de V56: INCLUIDO y ACEPTADO ocupan.
	 *
	 * <p>El unique sigue siendo el mecanismo que garantiza RN-M21-003; esto existe para poder
	 * explicarlo con un 409 legible que lleve el lote, en vez de dejar reventar una constraint —que
	 * ademas dejaria la transaccion marcada para rollback—.
	 */
	@Override
	@Query("""
			SELECT i FROM PresentacionItem i
			 WHERE i.organizationId = :organizationId
			   AND i.obligacionId = :obligacionId
			   AND i.estado IN (com.akine.billing.domain.EstadoItemPresentacion.INCLUIDO,
			                    com.akine.billing.domain.EstadoItemPresentacion.ACEPTADO)
			""")
	Optional<PresentacionItem> findVivoDeLaObligacion(
			@Param("organizationId") long organizationId,
			@Param("obligacionId") long obligacionId);

	/** {@code COALESCE} porque un borrador recien creado no tiene items y la suma seria NULL. */
	@Override
	@Query("""
			SELECT COALESCE(SUM(i.importePresentado), 0) FROM PresentacionItem i
			 WHERE i.presentacionId = :presentacionId
			""")
	BigDecimal sumarPresentado(@Param("presentacionId") long presentacionId);

	/** Ver el puerto: unico DELETE fisico de la etapa, solo alcanzable desde un borrador. */
	@Override
	default void borrarDelBorrador(PresentacionItem item) {
		delete(item);
	}
}
