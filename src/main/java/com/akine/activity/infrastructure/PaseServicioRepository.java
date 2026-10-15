package com.akine.activity.infrastructure;

import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Pases vendidos (AKINE-08.06/08.07).
 *
 * <h2>Los dos UPDATE del saldo</h2>
 *
 * <p>Son el unico camino por el que se mueven {@code creditos_disponibles} y {@code estado}, y los
 * dos <b>suben {@code version}</b> (la leccion de #33 y #39: un UPDATE nativo que no la mueve deja
 * pasar una escritura vieja). La condicion vive en el {@code WHERE} y la evalua InnoDB con lectura
 * actual bajo el lock exclusivo de la fila: dos consumos del ultimo credito no pueden pasar los dos.
 *
 * <p><b>El orden de las asignaciones importa.</b> MySQL evalua el {@code SET} de izquierda a
 * derecha con los valores ya asignados, asi que {@code estado} se calcula ANTES de tocar el saldo.
 *
 * <p>{@code clearAutomatically = false} a proposito: limpiar el contexto desacoplaria la asistencia
 * y la inscripcion que el llamador tiene cargadas a mitad de transaccion (el defecto de #57). La
 * entidad nunca se vuelve a guardar despues de un consumo y el saldo se relee con
 * {@link #saldoActual}.
 */
public interface PaseServicioRepository
		extends JpaRepository<PaseServicio, Long>, PaseServicioRepositoryPort {

	@Override
	@Query("""
			SELECT p FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.consultorioId = :consultorioId
			   AND p.id = :paseId
			""")
	Optional<PaseServicio> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("paseId") long paseId);

	@Override
	@Query("""
			SELECT p FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.idempotencyKey = :idempotencyKey
			""")
	Optional<PaseServicio> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	@Override
	@Query("""
			SELECT p FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.personaId = :personaId
			 ORDER BY p.vigenciaHasta DESC, p.id DESC
			""")
	List<PaseServicio> findDePersona(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId);

	@Override
	@Query(value = """
			SELECT id FROM pase_servicio
			 WHERE organization_id = :organizationId
			   AND persona_id = :personaId
			   AND snapshot_oferta_id = :ofertaId
			   AND estado = 'ACTIVO'
			   AND creditos_disponibles > 0
			   AND vigencia_desde <= :fecha
			   AND vigencia_hasta >= :fecha
			 ORDER BY vigencia_hasta ASC, id ASC
			 FOR UPDATE
			""", nativeQuery = true)
	List<Long> lockElegibles(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId,
			@Param("ofertaId") long ofertaId,
			@Param("fecha") LocalDate fecha);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET estado = CASE WHEN creditos_disponibles = 1 THEN 'AGOTADO' ELSE estado END,
			       creditos_disponibles = creditos_disponibles - 1,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND estado = 'ACTIVO'
			   AND creditos_disponibles >= 1
			   AND vigencia_desde <= :fecha
			   AND vigencia_hasta >= :fecha
			""", nativeQuery = true)
	int consumirUno(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("fecha") LocalDate fecha);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET estado = CASE WHEN estado = 'AGOTADO' THEN 'ACTIVO' ELSE estado END,
			       creditos_disponibles = creditos_disponibles + 1,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND estado IN ('ACTIVO', 'AGOTADO', 'VENCIDO')
			   AND creditos_disponibles < creditos_iniciales
			""", nativeQuery = true)
	int devolverUno(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	@Query(value = """
			SELECT creditos_disponibles FROM pase_servicio
			 WHERE id = :paseId AND organization_id = :organizationId
			""", nativeQuery = true)
	int saldoActual(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);
}
