package com.akine.activity.infrastructure;

import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.NetoDeAsistencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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
}
