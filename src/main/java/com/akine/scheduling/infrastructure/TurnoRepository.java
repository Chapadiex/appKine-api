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

	/**
	 * <p>Sin filtro de baja logica, y a proposito: los turnos cancelados del dia forman parte de
	 * lo que la recepcion necesita ver. Es la unica consulta de esta clase que los incluye.
	 */
	@Override
	@Query("""
			SELECT t FROM Turno t
			 WHERE t.organizationId = :organizationId
			   AND t.consultorioId = :consultorioId
			   AND t.inicio >= :desde
			   AND t.inicio < :hasta
			 ORDER BY t.inicio ASC, t.id ASC
			""")
	List<Turno> findDeLaSedeEnVentana(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

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

	/**
	 * <p>Se filtra por {@code inicio} dentro de la ventana y no por solapamiento: lo que el motor
	 * necesita es el cupo consumido de cada SLOT, y un slot se identifica por su instante de inicio.
	 * Un turno que empieza antes de la ventana y se mete adentro no ocupa ningun slot de esta oferta
	 * — ocupa al profesional, y de eso se encarga la consulta de solapamiento.
	 */
	@Override
	@Query("""
			SELECT t FROM Turno t
			 WHERE t.organizationId = :organizationId
			   AND t.ofertaId = :ofertaId
			   AND t.deletedAt IS NULL
			   AND t.inicio >= :desde
			   AND t.inicio < :hasta
			""")
	List<Turno> findVivosDeLaOfertaEnVentana(
			@Param("organizationId") long organizationId,
			@Param("ofertaId") long ofertaId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Nativa y no JPQL por el {@code LIMIT}: JPQL no lo tiene y la alternativa —{@code Pageable}—
	 * obligaria a que el puerto del dominio conociera un tipo de Spring Data.
	 */
	@Override
	@Query(value = """
			SELECT * FROM turno t
			 WHERE t.organization_id = :organizationId
			   AND t.persona_id = :personaId
			 ORDER BY t.inicio DESC, t.id DESC
			 LIMIT :limite
			""", nativeQuery = true)
	List<Turno> findDeLaPersona(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId,
			@Param("limite") int limite);
}
