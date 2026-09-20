package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de clases programadas.
 *
 * <p>Las dos consultas de solapamiento son el corazon de la correctitud de la etapa y estan
 * escritas en JPQL a la vista, no derivadas del nombre del metodo: el predicado
 * {@code inicio < :fin AND :inicio < fin} es la definicion de "dos intervalos se cruzan" y quien
 * lea esta clase tiene que poder verificarlo sin reconstruirlo desde un nombre de cuarenta
 * caracteres. Es la misma decision que tomo {@code TurnoRepository}, y la simetria es deliberada:
 * las dos contestan la misma pregunta sobre la misma grilla.
 */
public interface ClaseProgramadaRepository
		extends JpaRepository<ClaseProgramada, Long>, ClaseProgramadaRepositoryPort {

	@Override
	@Query("""
			SELECT c FROM ClaseProgramada c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			   AND c.id = :claseId
			""")
	Optional<ClaseProgramada> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("claseId") long claseId);

	@Override
	@Query("""
			SELECT c FROM ClaseProgramada c
			 WHERE c.organizationId = :organizationId
			   AND c.idempotencyKey = :idempotencyKey
			""")
	Optional<ClaseProgramada> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	/**
	 * <p>{@code deletedAt IS NULL} y no un filtro posterior en memoria: una clase cancelada libera
	 * su horario en el acto, y traerla para descartarla despues haria que la consulta crezca con la
	 * historia de la sede en vez de con su ocupacion.
	 *
	 * <p>Los dos extremos superiores son EXCLUSIVOS, en las dos puntas de la comparacion. Una clase
	 * que termina exactamente cuando empieza el turno siguiente <b>no</b> se cruza con el: es el
	 * caso normal de dos eventos consecutivos, y tratarlo como conflicto dejaria media grilla sin
	 * poder usarse.
	 */
	@Override
	@Query("""
			SELECT c FROM ClaseProgramada c
			 WHERE c.organizationId = :organizationId
			   AND c.profesionalMembershipId = :profesionalMembershipId
			   AND c.deletedAt IS NULL
			   AND c.inicio < :fin
			   AND :inicio < c.fin
			""")
	List<ClaseProgramada> findVivasDeProfesionalQueCruzan(
			@Param("organizationId") long organizationId,
			@Param("profesionalMembershipId") long profesionalMembershipId,
			@Param("inicio") Instant inicio,
			@Param("fin") Instant fin);

	/** Ver {@link #findVivasDeProfesionalQueCruzan}. */
	@Override
	@Query("""
			SELECT c FROM ClaseProgramada c
			 WHERE c.organizationId = :organizationId
			   AND c.espacioId = :espacioId
			   AND c.deletedAt IS NULL
			   AND c.inicio < :fin
			   AND :inicio < c.fin
			""")
	List<ClaseProgramada> findVivasDeEspacioQueCruzan(
			@Param("organizationId") long organizationId,
			@Param("espacioId") long espacioId,
			@Param("inicio") Instant inicio,
			@Param("fin") Instant fin);

	/**
	 * <p>Sin filtro de baja logica, y a proposito: las clases canceladas del dia forman parte de lo
	 * que la grilla necesita mostrar. Es la unica consulta de esta clase que las incluye.
	 */
	@Override
	@Query("""
			SELECT c FROM ClaseProgramada c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			   AND c.inicio >= :desde
			   AND c.inicio < :hasta
			 ORDER BY c.inicio ASC, c.id ASC
			""")
	List<ClaseProgramada> findDeLaSedeEnVentana(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	@Override
	@Query("""
			SELECT c FROM ClaseProgramada c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			   AND c.deletedAt IS NULL
			   AND c.inicio >= :desde
			   AND c.inicio < :hasta
			 ORDER BY c.inicio ASC, c.id ASC
			""")
	List<ClaseProgramada> findVivasDeLaSedeEnVentana(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);
}
