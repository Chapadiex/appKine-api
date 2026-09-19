package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.CasoProfesional;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoProfesionalRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistencia del equipo tratante.
 *
 * <p><b>No expone ningun borrado</b>, ni siquiera el {@code delete} heredado se usa: la salida del
 * equipo es una fecha en {@code hasta}. Un profesional desvinculado sigue figurando en el caso que
 * trato, porque lo trato (RF-M10-005, regla maestra 10).
 *
 * <p>Ordena por vigencia primero y despues por antiguedad: la pantalla del caso muestra el equipo
 * de hoy arriba y el historial abajo, que es como se lee.
 */
public interface CasoProfesionalRepository
		extends JpaRepository<CasoProfesional, Long>, CasoProfesionalRepositoryPort {

	@Override
	@Query("""
			SELECT p FROM CasoProfesional p
			 WHERE p.organizationId = :organizationId
			   AND p.casoId = :casoId
			   AND (:soloVigentes = false OR p.hasta IS NULL)
			 ORDER BY CASE WHEN p.hasta IS NULL THEN 0 ELSE 1 END, p.desde ASC, p.id ASC
			""")
	List<CasoProfesional> buscarDeCaso(
			@Param("organizationId") Long organizationId,
			@Param("casoId") Long casoId,
			@Param("soloVigentes") boolean soloVigentes);
}
