package com.akine.billing.infrastructure;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.port.CobroRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CobroRepository extends JpaRepository<Cobro, Long>, CobroRepositoryPort {

	@Override
	@Query("""
			SELECT c FROM Cobro c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			   AND c.id = :cobroId
			""")
	Optional<Cobro> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("cobroId") long cobroId);

	@Override
	@Query("""
			SELECT c FROM Cobro c
			 WHERE c.organizationId = :organizationId
			   AND c.idempotencyKey = :idempotencyKey
			""")
	Optional<Cobro> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	@Override
	@Query("""
			SELECT c FROM Cobro c
			 WHERE c.organizationId = :organizationId
			   AND c.personaId = :personaId
			   AND c.deletedAt IS NULL
			 ORDER BY c.cobradoEn DESC
			""")
	List<Cobro> findDeLaPersona(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId);

	/**
	 * El descuento atomico. Ver el javadoc del puerto.
	 *
	 * <p>Nativo y no JPQL: es una escritura sobre {@code obligacion}, que pertenece a otro agregado,
	 * y hacerla por JPA exigiria cargar la entidad — que es exactamente la lectura previa que se
	 * quiere evitar. La condicion {@code saldo >= :importe} en el WHERE es lo que decide la
	 * correctitud y tiene que estar a la vista de quien lee.
	 *
	 * <p>Filtra tambien por estado. Una obligacion anulada tiene saldo cero por definicion, pero el
	 * predicado explicito impide que un cambio futuro en como se anula deje pasar un cobro contra
	 * una deuda que ya no existe.
	 */
	@Modifying
	@Query(value = """
			UPDATE obligacion
			   SET saldo = saldo - :importe
			 WHERE id = :obligacionId
			   AND saldo >= :importe
			   AND estado IN ('PENDIENTE', 'PARCIAL')
			""", nativeQuery = true)
	@Override
	int descontarSaldo(
			@Param("obligacionId") long obligacionId,
			@Param("importe") BigDecimal importe);

	/**
	 * Deriva el estado del saldo, en una sentencia.
	 *
	 * <p>Se hace con SQL y no leyendo la entidad para cambiarle el estado en Java: leerla despues
	 * del UPDATE de arriba traeria la version que la sesion de JPA tiene cacheada, que ya no
	 * refleja el saldo real. Es una trampa que este proyecto ya pago antes.
	 */
	@Modifying
	@Query(value = """
			UPDATE obligacion
			   SET estado = CASE WHEN saldo = 0 THEN 'PAGADA' ELSE 'PARCIAL' END
			 WHERE id = :obligacionId
			   AND estado IN ('PENDIENTE', 'PARCIAL')
			""", nativeQuery = true)
	@Override
	void actualizarEstadoPorSaldo(@Param("obligacionId") long obligacionId);
}
