package com.akine.billing.infrastructure;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.port.CobroRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
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
			   AND organization_id = :organizationId
			   AND saldo >= :importe
			   AND estado IN ('PENDIENTE', 'PARCIAL')
			""", nativeQuery = true)
	@Override
	int descontarSaldo(
			@Param("organizationId") long organizationId,
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
			   AND organization_id = :organizationId
			   AND estado IN ('PENDIENTE', 'PARCIAL')
			""", nativeQuery = true)
	@Override
	void actualizarEstadoPorSaldo(
			@Param("organizationId") long organizationId,
			@Param("obligacionId") long obligacionId);

	// =================================================================================
	// M23 — agregaciones de reporte (AKINE-07.06)
	// =================================================================================
	//
	// Las dos suman COBRO (M19), que no es la deuda (M18) ni la caja (M20). El reporte
	// las muestra con su fuente declarada y no publica ningun total que las junte con las
	// otras: sumar lo cobrado con los ingresos de caja cuenta dos veces cada cobro en
	// efectivo, porque el movimiento de caja de origen COBRO ES ese cobro visto desde el
	// cajon.

	/** Lo cobrado en el periodo, por cualquier medio. */
	@Query("""
			SELECT SUM(c.total) FROM Cobro c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			   AND c.deletedAt IS NULL
			   AND c.cobradoEn >= :desde
			   AND c.cobradoEn < :hasta
			""")
	BigDecimal sumarCobradoEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Lo cobrado por un medio concreto. Es la <b>mitad izquierda de la reconciliacion</b>.
	 *
	 * <p>Con {@code EFECTIVO} da lo que, segun M19, deberia haber entrado a algun cajon. La otra
	 * mitad la da {@code MovimientoCajaRepository.sumarEfectivoDeCobrosEnElReporte} desde M20, y el
	 * reporte muestra <b>la resta de las dos, que deberia ser cero</b>. No es una suma: es una
	 * comparacion entre dos fuentes que describen el mismo hecho desde dos lados.
	 *
	 * <p>Nativa porque {@code cobro_medio} es una coleccion mapeada con {@code JoinColumn} y no
	 * tiene entidad propia navegable desde JPQL para agregarla.
	 */
	@Query(value = """
			SELECT SUM(m.importe)
			  FROM cobro_medio m
			  JOIN cobro c ON c.id = m.cobro_id
			 WHERE c.organization_id = :organizationId
			   AND c.consultorio_id = :consultorioId
			   AND c.deleted_at IS NULL
			   AND c.cobrado_en >= :desde
			   AND c.cobrado_en < :hasta
			   AND m.medio = :medio
			""", nativeQuery = true)
	BigDecimal sumarCobradoPorMedioEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("medio") String medio);
}
