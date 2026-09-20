package com.akine.billing.infrastructure;

import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * El ledger de caja. <b>Solo lecturas y un {@code save}</b>: no hay update ni delete, y no los va a
 * haber.
 *
 * <p>Un historial que se puede editar no es un historial. La correccion es compensacion, con otra
 * fila. Mismo contrato que los repositorios de {@code turno_evento} y {@code autorizacion_movimiento}.
 */
public interface MovimientoCajaRepository
		extends JpaRepository<MovimientoCaja, Long>, MovimientoCajaRepositoryPort {

	@Override
	@Query("""
			SELECT m FROM MovimientoCaja m
			 WHERE m.organizationId = :organizationId
			   AND m.consultorioId = :consultorioId
			   AND m.id = :movimientoId
			""")
	Optional<MovimientoCaja> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("movimientoId") long movimientoId);

	@Override
	@Query("""
			SELECT m FROM MovimientoCaja m
			 WHERE m.organizationId = :organizationId
			   AND m.idempotencyKey = :idempotencyKey
			""")
	Optional<MovimientoCaja> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	/**
	 * El unique lo impide igualmente; esto existe para poder <b>explicarlo</b> con un 409 legible en
	 * vez de dejar que reviente una constraint.
	 */
	@Override
	@Query("""
			SELECT COUNT(m) > 0 FROM MovimientoCaja m
			 WHERE m.organizationId = :organizationId
			   AND m.movimientoOrigenId = :movimientoOrigenId
			""")
	boolean existeReversionDe(
			@Param("organizationId") long organizationId,
			@Param("movimientoOrigenId") long movimientoOrigenId);

	/**
	 * RF-M20-004. Nativo por los filtros opcionales y el {@code LIMIT/OFFSET}.
	 *
	 * <p>El filtro por {@code fecha_negocio} y no por la jornada es lo que hace visibles los
	 * movimientos <b>sin jornada</b> —los que no son en efectivo—, que de otro modo no aparecerian
	 * en ninguna vista y la operatoria del dia quedaria incompleta.
	 */
	@Override
	@Query(value = """
			SELECT * FROM movimiento_caja
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			   AND (:jornadaCajaId IS NULL OR jornada_caja_id = :jornadaCajaId)
			   AND (:fechaNegocio IS NULL OR fecha_negocio = :fechaNegocio)
			   AND (:tipo IS NULL OR tipo = :tipo)
			 ORDER BY registrado_en DESC, id DESC
			 LIMIT :limite OFFSET :desplazamiento
			""", nativeQuery = true)
	List<MovimientoCaja> buscar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("jornadaCajaId") Long jornadaCajaId,
			@Param("fechaNegocio") LocalDate fechaNegocio,
			@Param("tipo") String tipo,
			@Param("limite") int limite,
			@Param("desplazamiento") int desplazamiento);

	/**
	 * Totales por medio, con el signo que le corresponde a cada tipo.
	 *
	 * <p>Incluye los medios que no afectan el arqueo: es el dato que le dice al administrativo que
	 * de los 120.000 del dia, 60.000 entraron por tarjeta y no estan en el cajon.
	 */
	@Override
	@Query(value = """
			SELECT medio,
			       SUM(CASE WHEN tipo IN ('INGRESO', 'REVERSION_DE_EGRESO')
			                THEN importe ELSE -importe END) AS total
			  FROM movimiento_caja
			 WHERE organization_id = :organizationId
			   AND jornada_caja_id = :jornadaCajaId
			 GROUP BY medio
			 ORDER BY medio
			""", nativeQuery = true)
	List<Object[]> totalesPorMedio(
			@Param("organizationId") long organizationId,
			@Param("jornadaCajaId") long jornadaCajaId);

	/**
	 * La reconstruccion del saldo desde el ledger. <b>No gobierna: confronta.</b>
	 *
	 * <p>El criterio de aceptacion de la etapa pide que el saldo se pueda reconstruir desde los
	 * movimientos. Quien opera usa la columna materializada; esto existe para poder confrontar las
	 * dos cifras, y si divergen la que miente es la columna.
	 *
	 * <p>No incluye el saldo inicial: eso lo suma el llamador, que es quien tiene la jornada.
	 */
	@Override
	@Query(value = """
			SELECT COALESCE(SUM(CASE WHEN tipo IN ('INGRESO', 'REVERSION_DE_EGRESO')
			                         THEN importe ELSE -importe END), 0)
			  FROM movimiento_caja
			 WHERE organization_id = :organizationId
			   AND jornada_caja_id = :jornadaCajaId
			   AND afecta_arqueo = 1
			""", nativeQuery = true)
	BigDecimal reconstruirSaldoArqueable(
			@Param("organizationId") long organizationId,
			@Param("jornadaCajaId") long jornadaCajaId);
}
