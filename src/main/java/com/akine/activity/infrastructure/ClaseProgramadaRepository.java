package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	// =================================================================================
	// El cupo (AKINE-08.02). Ver el punto 1 de la cabecera de V60.
	// =================================================================================

	/**
	 * Consulta NATIVA a proposito, igual que {@code AgendaSedeRepository#lockByScope} y por el mismo
	 * motivo: la clausula que decide la correctitud —{@code FOR UPDATE}— tiene que estar a la vista
	 * de quien lee, no escondida detras de un {@code @Lock} sobre un metodo derivado del nombre.
	 */
	@Override
	@Query(value = """
			SELECT * FROM clase_programada
			 WHERE id = :claseId
			   AND organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			 FOR UPDATE
			""", nativeQuery = true)
	Optional<ClaseProgramada> lockByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("claseId") long claseId);

	/**
	 * <b>La operacion que hace cumplir el cupo.</b> Ver el javadoc del puerto: la condicion del
	 * {@code WHERE} <b>es</b> la correctitud, y cero filas afectadas es "no hay lugar".
	 *
	 * <p>Nativa y no JPQL por los mismos dos motivos que {@code CobroRepository#descontarSaldo} y
	 * {@code AutorizacionRepository#descontarSaldo}. Uno: JPQL no expresa
	 * {@code SET x = x + 1} con una condicion aritmetica sobre otras columnas de forma legible. Dos,
	 * y es el que importa: hacerlo por JPA exigiria cargar la entidad, que es exactamente la lectura
	 * previa que se quiere evitar.
	 *
	 * <p>{@code LEAST(capacidad, :capacidadEfectiva)} lleva los dos terminos y ninguno sobra. La
	 * columna {@code capacidad} es el techo que <b>ningun llamador puede saltearse</b>, ni siquiera
	 * uno con un bug que infle el parametro; {@code :capacidadEfectiva} agrega el limite de la
	 * oferta y del box, que viven en <b>otros modulos</b> y que ninguna base puede comprobar sola.
	 *
	 * <p>{@code estado = 'PROGRAMADA'} y {@code deleted_at IS NULL} explicitos: nadie se anota a una
	 * clase cancelada, y el predicado escrito impide que un cambio futuro en como se resuelve una
	 * clase deje pasar una inscripcion contra una que ya no existe.
	 *
	 * <p><b>{@code clearAutomatically}</b> porque despues de esto el {@code cupo_ocupado} de
	 * cualquier {@code ClaseProgramada} cargada quedo viejo, y la respuesta tiene que llevar el
	 * numero real.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE clase_programada
			   SET cupo_ocupado = cupo_ocupado + 1
			 WHERE id = :claseId
			   AND organization_id = :organizationId
			   AND deleted_at IS NULL
			   AND estado = 'PROGRAMADA'
			   AND cupo_ocupado < LEAST(capacidad, :capacidadEfectiva)
			""", nativeQuery = true)
	@Override
	int tomarCupo(
			@Param("organizationId") long organizationId,
			@Param("claseId") long claseId,
			@Param("capacidadEfectiva") int capacidadEfectiva);

	/**
	 * El inverso. <b>No filtra por {@code estado} ni por {@code deleted_at}</b>, a diferencia de
	 * {@link #tomarCupo}, y es deliberado: liberar el lugar de alguien que se da de baja de una
	 * clase que despues se cancelo sigue siendo correcto, y negarse dejaria el contador diciendo una
	 * cosa y los recibos otra para siempre. Es el mismo criterio con el que
	 * {@code AutorizacionRepository#devolverSaldo} no filtra por estado.
	 *
	 * <p><b>Este es el {@code UPDATE} que toma el lock que serializa la lista de espera.</b> Quien
	 * cancela lo ejecuta ANTES de leer la cola.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE clase_programada
			   SET cupo_ocupado = cupo_ocupado - 1
			 WHERE id = :claseId
			   AND organization_id = :organizationId
			   AND cupo_ocupado > 0
			""", nativeQuery = true)
	@Override
	int liberarCupo(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	/** Solo para la cancelacion de la clase entera. Ver el puerto. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE clase_programada
			   SET cupo_ocupado = 0
			 WHERE id = :claseId
			   AND organization_id = :organizationId
			""", nativeQuery = true)
	@Override
	int vaciarCupo(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	/**
	 * {@code UPDATE ... + 1} y despues leer, nunca {@code MAX + 1}.
	 *
	 * <p>Las dos sentencias son seguras <b>porque el {@code UPDATE} ya tomo el lock exclusivo de la
	 * fila</b> y lo retiene hasta el commit: la lectura que sigue no puede ver el incremento de
	 * otra transaccion. El orden importa —incrementar y despues leer, nunca al reves— y es el mismo
	 * de {@code SesionNumeradorRepository} y {@code CasoNumeradorRepository}.
	 */
	@Override
	default int siguientePosicionDeEspera(long organizationId, long claseId) {
		incrementarPosicionDeEspera(organizationId, claseId);
		return leerUltimaPosicionDeEspera(organizationId, claseId);
	}

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE clase_programada
			   SET ultima_posicion_espera = ultima_posicion_espera + 1
			 WHERE id = :claseId
			   AND organization_id = :organizationId
			""", nativeQuery = true)
	void incrementarPosicionDeEspera(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	@Query("""
			SELECT c.ultimaPosicionEspera FROM ClaseProgramada c
			 WHERE c.id = :claseId
			   AND c.organizationId = :organizationId
			""")
	int leerUltimaPosicionDeEspera(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);
}
