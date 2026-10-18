package com.akine.activity.infrastructure;

import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ConsumoDeClase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.NetoDeAsistencia;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El ledger de creditos. <b>Sin update ni delete en el puerto</b>: es la garantia de inmutabilidad.
 * {@code registrar} y los agregados delegan con un {@code default} porque Spring Data tomaria un
 * metodo abstracto con esos nombres por consulta derivada y la aplicacion no arrancaria.
 */
public interface MovimientoPaseRepository
		extends JpaRepository<MovimientoPase, Long>, MovimientoPaseRepositoryPort {

	@Override
	default MovimientoPase registrar(MovimientoPase movimiento) {
		return saveAndFlush(movimiento);
	}

	@Override
	@Query("""
			SELECT m FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.paseId = :paseId
			 ORDER BY m.ocurridoEn ASC, m.id ASC
			""")
	List<MovimientoPase> historial(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	@Query(value = """
			SELECT m FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.paseId = :paseId
			 ORDER BY m.ocurridoEn ASC, m.id ASC
			""",
			countQuery = """
			SELECT COUNT(m) FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.paseId = :paseId
			""")
	Page<MovimientoPase> historial(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			Pageable pagina);

	@Override
	default List<NetoDeAsistencia> netoDeAsistencia(long organizationId, long asistenciaId) {
		return filasDeNetoDeAsistencia(organizationId, asistenciaId).stream()
				.map(fila -> new NetoDeAsistencia(
						((Number) fila[0]).longValue(), ((Number) fila[1]).longValue()))
				.toList();
	}

	@Query("""
			SELECT m.paseId, SUM(m.cantidad)
			  FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.asistenciaId = :asistenciaId
			 GROUP BY m.paseId
			HAVING SUM(m.cantidad) <> 0
			""")
	List<Object[]> filasDeNetoDeAsistencia(
			@Param("organizationId") long organizationId,
			@Param("asistenciaId") long asistenciaId);

	@Override
	default int sumaDelLedger(long organizationId, long paseId) {
		return Math.toIntExact(sumaDelLedgerComoLong(organizationId, paseId));
	}

	@Query("""
			SELECT COALESCE(SUM(m.cantidad), 0L) FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.paseId = :paseId
			""")
	long sumaDelLedgerComoLong(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	default int consumoNeto(long organizationId, long paseId) {
		return Math.toIntExact(consumoNetoComoLong(organizationId, paseId));
	}

	@Query("""
			SELECT COALESCE(SUM(m.cantidad), 0L) FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.paseId = :paseId
			   AND m.tipo IN (com.akine.activity.domain.TipoMovimientoPase.CONSUMO,
			                  com.akine.activity.domain.TipoMovimientoPase.DEVOLUCION)
			""")
	long consumoNetoComoLong(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	default Map<Long, Integer> consumoNetoPorPase(long organizationId, Collection<Long> paseIds) {
		if (paseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> neto = new HashMap<>();
		for (Object[] fila : filasDeConsumoNetoPorPase(organizationId, paseIds)) {
			neto.put(((Number) fila[0]).longValue(), Math.toIntExact(((Number) fila[1]).longValue()));
		}
		return neto;
	}

	@Query("""
			SELECT m.paseId, SUM(m.cantidad)
			  FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.paseId IN :paseIds
			   AND m.tipo IN (com.akine.activity.domain.TipoMovimientoPase.CONSUMO,
			                  com.akine.activity.domain.TipoMovimientoPase.DEVOLUCION)
			 GROUP BY m.paseId
			HAVING SUM(m.cantidad) <> 0
			""")
	List<Object[]> filasDeConsumoNetoPorPase(
			@Param("organizationId") long organizationId,
			@Param("paseIds") Collection<Long> paseIds);

	@Override
	@Query("""
			SELECT m FROM MovimientoPase m
			 WHERE m.organizationId = :organizationId
			   AND m.paseId = :paseId
			   AND m.origenTipo = com.akine.activity.domain.OrigenMovimientoPase.AJUSTE_MANUAL
			   AND m.origenId = :versionLeida
			""")
	Optional<MovimientoPase> ajusteSobreVersion(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("versionLeida") long versionLeida);

	@Override
	default List<ConsumoDeClase> consumosDeLaClase(long organizationId, long claseId) {
		return filasDeConsumosDeLaClase(organizationId, claseId).stream()
				.map(fila -> new ConsumoDeClase(
						((Number) fila[0]).longValue(), ((Number) fila[1]).longValue(),
						((Number) fila[2]).longValue()))
				.toList();
	}

	/**
	 * Es una lectura consistente, no actual: por eso {@code ClaseService.cancelar} corre en
	 * {@code READ_COMMITTED} y con la clase bloqueada. La asistencia toma la clase en modo
	 * compartido antes de tocar un pase, asi que cuando la cancelacion tiene el lock exclusivo
	 * ningun consumo de esa clase esta a mitad de camino, y lo commiteado lo ve.
	 */
	@Query("""
			SELECT m.asistenciaId, m.paseId, SUM(m.cantidad)
			  FROM MovimientoPase m, AsistenciaActividad a
			 WHERE a.id = m.asistenciaId
			   AND a.organizationId = :organizationId
			   AND m.organizationId = :organizationId
			   AND a.claseId = :claseId
			 GROUP BY m.asistenciaId, m.paseId
			HAVING SUM(m.cantidad) <> 0
			""")
	List<Object[]> filasDeConsumosDeLaClase(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId);
}
