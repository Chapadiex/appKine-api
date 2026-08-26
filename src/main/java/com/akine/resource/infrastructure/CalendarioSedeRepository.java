package com.akine.resource.infrastructure;

import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Acceso a la politica de calendario de una sede. Toda consulta filtra por
 * {@code organizationId} y {@code consultorioId}: un id de otro tenant no debe resolver nunca
 * (ADR-0004).
 */
public interface CalendarioSedeRepository
		extends JpaRepository<CalendarioSede, Long>, CalendarioSedeRepositoryPort {

	/**
	 * La fila de politica de la sede, sin bloqueo. Para mostrarla o para decidir si hace falta
	 * crearla a demanda (V23, diseno §2.2): la fila puede no existir todavia.
	 */
	@Query("""
			SELECT c FROM CalendarioSede c
			 WHERE c.organizationId = :organizationId
			   AND c.consultorioId = :consultorioId
			""")
	@Override
	Optional<CalendarioSede> findByScope(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);

	/**
	 * Lock exclusivo sobre la fila de calendario de la sede.
	 *
	 * <p>Es el lock que serializa TODOS los writes de disponibilidad de esa sede. No bloquea
	 * los bloques ({@code profesional_disponibilidad}) ni las excepciones
	 * ({@code disponibilidad_excepcion}): MySQL 8.4 no tiene exclusion constraints —son de
	 * PostgreSQL— asi que el solapamiento se valida en aplicacion, y bloquear filas que ya
	 * existen no impide que otra transaccion INSERTE una fila nueva en el hueco, que es
	 * justamente el caso que hay que evitar. La fila de {@code consultorio_calendario} en
	 * cambio siempre existe —se crea a demanda antes de tomar el lock— y por eso es ella, y no
	 * los bloques, la que se usa para serializar el acceso. Ver el diseno de etapa §5.
	 *
	 * <p>Consulta NATIVA a proposito, igual que {@code findByIdForUpdate} en
	 * {@code EspacioRepository} y por el mismo motivo: la clausula que decide la correctitud
	 * tiene que estar a la vista de quien lee la consulta, y no escondida detras de un
	 * {@code @Lock(PESSIMISTIC_WRITE)} sobre un metodo derivado.
	 */
	@Query(value = """
			SELECT * FROM consultorio_calendario
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			 FOR UPDATE
			""", nativeQuery = true)
	@Override
	Optional<CalendarioSede> lockByScope(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);
}
