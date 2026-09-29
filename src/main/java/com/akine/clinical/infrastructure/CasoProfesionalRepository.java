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

	/**
	 * Puente entre el {@code saveAll(List)} del puerto y el {@code saveAll(Iterable)} de Spring
	 * Data. <b>Sin este metodo la aplicacion no arranca.</b>
	 *
	 * <p>El puerto declara {@code List} y {@code JpaRepository} declara {@code Iterable}: para
	 * Java no son el mismo metodo, asi que el del puerto queda sin implementacion y Spring Data lo
	 * toma por un metodo de consulta derivado. El resultado es
	 * {@code No property 'saveAll' found for type CasoProfesional} al crear el bean —no un error de
	 * compilacion—, y por eso no se vio hasta la primera corrida con base real.
	 */
	@Override
	default List<CasoProfesional> saveAll(List<CasoProfesional> participaciones) {
		return saveAll((Iterable<CasoProfesional>) participaciones);
	}


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
