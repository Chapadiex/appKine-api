package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de turnos.
 *
 * <p>Las dos consultas de solapamiento son el corazon de la correctitud de la etapa y estan
 * escritas en JPQL a la vista, no derivadas del nombre del metodo: el predicado
 * {@code inicio < :fin AND :inicio < fin} es la definicion de "dos intervalos se cruzan" y quien
 * lea esta clase tiene que poder verificarlo sin reconstruirlo mentalmente desde un nombre de
 * cuarenta caracteres.
 */
public interface TurnoRepository extends JpaRepository<Turno, Long>, TurnoRepositoryPort {

	@Override
	@Query("""
			SELECT t FROM Turno t
			 WHERE t.organizationId = :organizationId
			   AND t.consultorioId = :consultorioId
			   AND t.id = :turnoId
			""")
	Optional<Turno> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("turnoId") long turnoId);

	@Override
	@Query("""
			SELECT t FROM Turno t
			 WHERE t.organizationId = :organizationId
			   AND t.idempotencyKey = :idempotencyKey
			""")
	Optional<Turno> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	/**
	 * <p>{@code deletedAt IS NULL} y no un filtro posterior en memoria: un turno cancelado libera
	 * su lugar, y traerlo para descartarlo despues haria que la consulta crezca con la historia de
	 * la sede en vez de con su ocupacion.
	 *
	 * <p>Los dos extremos superiores son EXCLUSIVOS, en las dos puntas de la comparacion. Un turno
	 * que termina exactamente cuando empieza el otro <b>no</b> se cruza con el: es el caso normal
	 * de dos turnos consecutivos, y tratarlo como conflicto dejaria media agenda sin reservar.
	 */
	@Override
	@Query("""
			SELECT t FROM Turno t
			 WHERE t.organizationId = :organizationId
			   AND t.profesionalMembershipId = :profesionalMembershipId
			   AND t.deletedAt IS NULL
			   AND t.inicio < :fin
			   AND :inicio < t.fin
			""")
	List<Turno> findVivosDeProfesionalQueCruzan(
			@Param("organizationId") long organizationId,
			@Param("profesionalMembershipId") long profesionalMembershipId,
			@Param("inicio") Instant inicio,
			@Param("fin") Instant fin);

	@Override
	@Query("""
			SELECT t FROM Turno t
			 WHERE t.organizationId = :organizationId
			   AND t.espacioId = :espacioId
			   AND t.deletedAt IS NULL
			   AND t.inicio < :fin
			   AND :inicio < t.fin
			""")
	List<Turno> findVivosDeEspacioQueCruzan(
			@Param("organizationId") long organizationId,
			@Param("espacioId") long espacioId,
			@Param("inicio") Instant inicio,
			@Param("fin") Instant fin);

	/**
	 * <p>Compara {@code inicio} por IGUALDAD, y aca si corresponde: el cupo es de un slot concreto
	 * de una oferta concreta, y dos turnos de la misma oferta que empiezan a la misma hora son por
	 * definicion el mismo slot. El solapamiento —que es otra pregunta— lo cubren las dos consultas
	 * de arriba.
	 */
	@Override
	@Query("""
			SELECT COUNT(t) FROM Turno t
			 WHERE t.organizationId = :organizationId
			   AND t.ofertaId = :ofertaId
			   AND t.inicio = :inicio
			   AND t.deletedAt IS NULL
			""")
	long contarVivosEnSlot(
			@Param("organizationId") long organizationId,
			@Param("ofertaId") long ofertaId,
			@Param("inicio") Instant inicio);
}
