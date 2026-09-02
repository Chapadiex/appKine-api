package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@code convenio} (M16).
 *
 * <p><b>Toda consulta lleva {@code organizationId} y {@code consultorioId}.</b> El alcance del
 * convenio es la SEDE (RN-M16-001) y no hay FK compuesta que lo garantice: sin las dos columnas en
 * el {@code WHERE}, un convenio de otra sede resolveria bajo una ruta que no le corresponde.
 *
 * <p><b>Ninguna consulta de aca toma el lock.</b> El lock vive en {@code ConvenioLockRepository} y
 * se toma ANTES de llamar a {@link #findActivosPorAlcance}. Leer primero y bloquear despues es una
 * escalada S a X entre dos transacciones simetricas, o sea un deadlock.
 */
public interface ConvenioRepository extends JpaRepository<Convenio, Long>, ConvenioRepositoryPort {

	@Override
	@Query("""
			SELECT c FROM Convenio c
			 WHERE c.id = :id
			   AND c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			""")
	Optional<Convenio> findByIdAndScope(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);

	@Override
	@Query("""
			SELECT c FROM Convenio c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			 ORDER BY c.nombre ASC, c.id ASC
			""")
	List<Convenio> findAllByScopeOrderByNombreAsc(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);

	/**
	 * El conjunto que compite por el periodo, y tambien el que resuelve RF-M16-006.
	 *
	 * <p>El orden {@code vigenciaDesde DESC, id DESC} es el desempate determinista de la
	 * resolucion: con la regla de no-solapamiento cumplida no puede haber dos candidatas para una
	 * fecha, pero si una escritura hecha a mano contra la base dejara dos, gana la mas reciente en
	 * vez de una al azar. Ver {@code ArancelService#resolver}.
	 */
	@Override
	@Query("""
			SELECT c FROM Convenio c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			   AND c.financiadorId = :financiadorId
			   AND c.planId = :planId
			   AND c.active = true
			 ORDER BY c.vigenciaDesde DESC, c.id DESC
			""")
	List<Convenio> findActivosPorAlcance(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId,
			@Param("financiadorId") Long financiadorId,
			@Param("planId") Long planId);
}
