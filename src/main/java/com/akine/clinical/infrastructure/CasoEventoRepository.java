package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.CasoEvento;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoEventoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistencia del historial de estados del Caso.
 *
 * <p><b>Append-only.</b> El puerto que implementa no declara {@code update} ni {@code delete}, y
 * esta interfaz no agrega ninguno: un historial que se puede editar no es un historial. Mismo
 * diseño que el historial de turnos de 05.03.
 *
 * <p>Se lee del evento mas viejo al mas nuevo, al reves que casi todo el resto del modulo: esto no
 * es una bandeja sino una linea de tiempo, y una linea de tiempo se lee en el orden en que
 * ocurrio.
 */
public interface CasoEventoRepository
		extends JpaRepository<CasoEvento, Long>, CasoEventoRepositoryPort {

	@Override
	@Query("""
			SELECT e FROM CasoEvento e
			 WHERE e.organizationId = :organizationId
			   AND e.casoId = :casoId
			 ORDER BY e.ocurrioEn ASC, e.id ASC
			""")
	List<CasoEvento> buscarDeCaso(
			@Param("organizationId") Long organizationId,
			@Param("casoId") Long casoId);
}
