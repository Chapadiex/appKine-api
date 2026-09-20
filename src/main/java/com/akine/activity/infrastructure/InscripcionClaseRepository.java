package com.akine.activity.infrastructure;

import com.akine.activity.domain.EstadoInscripcion;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de inscripciones (M28, AKINE-08.02).
 *
 * <p><b>Ninguna consulta de esta clase decide si hay lugar.</b> Eso lo hace
 * {@code ClaseProgramadaRepository#tomarCupo} y nada mas: un {@code COUNT} usado para decidir —y no
 * para informar— reintroduce la ventana entre leer y escribir que la etapa entera existe para
 * cerrar. {@link #contarQueConsumenCupo} esta para diagnosticar el invariante, no para autorizar
 * una inscripcion.
 */
public interface InscripcionClaseRepository
		extends JpaRepository<InscripcionClase, Long>, InscripcionClaseRepositoryPort {

	@Override
	@Query("""
			SELECT i FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			   AND i.id = :inscripcionId
			""")
	Optional<InscripcionClase> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("inscripcionId") long inscripcionId);

	@Override
	@Query("""
			SELECT i FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.idempotencyKey = :idempotencyKey
			""")
	Optional<InscripcionClase> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	/**
	 * <p>{@code deletedAt IS NULL} y no {@code estado <> CANCELADA}: las dos condiciones dicen lo
	 * mismo hoy, pero la primera es la que el unique {@code uk_inscripcion_clase_persona} usa, y
	 * que la consulta y el unique miren la misma columna es lo que impide que un dia digan cosas
	 * distintas.
	 */
	@Override
	@Query("""
			SELECT i FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			   AND i.personaId = :personaId
			   AND i.deletedAt IS NULL
			""")
	Optional<InscripcionClase> findVivaDePersona(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("personaId") long personaId);

	@Override
	@Query("""
			SELECT i FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			 ORDER BY i.posicionEspera ASC NULLS FIRST, i.id ASC
			""")
	List<InscripcionClase> findDeLaClase(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	@Override
	@Query("""
			SELECT i FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			   AND i.deletedAt IS NULL
			 ORDER BY i.posicionEspera ASC NULLS FIRST, i.id ASC
			""")
	List<InscripcionClase> findVivasDeLaClase(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	/**
	 * <p>{@code ORDER BY posicion_espera, id} y {@code LIMIT 1} en la base, no en memoria: traer la
	 * cola entera para quedarse con la cabeza hace que la consulta crezca con lo popular que sea la
	 * clase, y esta consulta corre <b>bajo el lock de la fila de la clase</b> — cada fila de mas es
	 * contencion de mas.
	 */
	@Override
	default Optional<InscripcionClase> siguienteEnEspera(long organizationId, long claseId) {
		return buscarEnEspera(organizationId, claseId, EstadoInscripcion.LISTA_ESPERA).stream()
				.findFirst();
	}

	@Query("""
			SELECT i FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			   AND i.estado = :estado
			   AND i.deletedAt IS NULL
			 ORDER BY i.posicionEspera ASC, i.id ASC
			 LIMIT 1
			""")
	List<InscripcionClase> buscarEnEspera(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("estado") EstadoInscripcion estado);

	@Override
	default int contarEnEspera(long organizationId, long claseId) {
		return contarPorEstado(organizationId, claseId, EstadoInscripcion.LISTA_ESPERA);
	}

	@Query("""
			SELECT COUNT(i) FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			   AND i.estado = :estado
			   AND i.deletedAt IS NULL
			""")
	int contarPorEstado(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("estado") EstadoInscripcion estado);

	/**
	 * Cuantos recibos hay. <b>Existe para diagnosticar el invariante</b>
	 * —{@code cupo_ocupado == este numero}— y nunca para decidir si alguien entra: usarla para eso
	 * seria volver al {@code SELECT} seguido de {@code INSERT} que el diseno entero evita.
	 */
	@Query("""
			SELECT COUNT(i) FROM InscripcionClase i
			 WHERE i.organizationId = :organizationId
			   AND i.claseId = :claseId
			   AND i.estado IN (
			       com.akine.activity.domain.EstadoInscripcion.RESERVADA,
			       com.akine.activity.domain.EstadoInscripcion.CONFIRMADA,
			       com.akine.activity.domain.EstadoInscripcion.ASISTIO,
			       com.akine.activity.domain.EstadoInscripcion.AUSENTE)
			""")
	int contarQueConsumenCupo(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	/**
	 * La promocion condicional. Ver el javadoc del puerto: <b>cero filas significa "ya la promovio
	 * otro"</b>, y quien recibe cero devuelve el lugar que habia tomado.
	 *
	 * <p>Nativa por lo mismo que el cupo: el valor esta en el {@code WHERE}, no en una lectura
	 * previa que podria quedar vieja entre que se lee y se escribe.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE inscripcion_clase
			   SET estado = 'RESERVADA',
			       promovida_en = :ahora
			 WHERE id = :inscripcionId
			   AND organization_id = :organizationId
			   AND estado = 'LISTA_ESPERA'
			   AND deleted_at IS NULL
			""", nativeQuery = true)
	@Override
	int promover(
			@Param("organizationId") long organizationId,
			@Param("inscripcionId") long inscripcionId,
			@Param("ahora") Instant ahora);

	/**
	 * Cancela todas las vivas de una clase cancelada, en una sentencia.
	 *
	 * <p><b>Cancela tambien a los que estaban en lista de espera</b>: la clase no existe mas, y
	 * dejarlos esperando un lugar que nunca va a liberarse seria peor que decirles que no hay clase.
	 *
	 * <p>No toca {@code ASISTIO} ni {@code AUSENTE} —una clase que ya se dicto no se cancela— ni a
	 * las ya canceladas, que conservan su motivo original. <b>No devuelve creditos ni plata</b>:
	 * eso es 08.07, y la idempotencia de esta operacion es lo que va a permitir colgarselo sin
	 * devolver dos veces.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE inscripcion_clase
			   SET estado = 'CANCELADA',
			       motivo_cancelacion = :motivo,
			       cancelada_en = :ahora,
			       cancelada_por_cuenta_id = :cuentaId,
			       deleted_at = :ahora,
			       version = version + 1
			 WHERE clase_id = :claseId
			   AND organization_id = :organizationId
			   AND deleted_at IS NULL
			   AND estado IN ('RESERVADA', 'CONFIRMADA', 'LISTA_ESPERA')
			""", nativeQuery = true)
	@Override
	int cancelarTodasPorClaseCancelada(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("motivo") String motivo,
			@Param("cuentaId") long cuentaId,
			@Param("ahora") Instant ahora);
}
