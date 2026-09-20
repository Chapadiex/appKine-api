package com.akine.person.infrastructure;

import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Autorizaciones de un paciente (M17, AKINE-03.06).
 *
 * <p>{@link #aprobadasDe} es la consulta que importa: es la que se hace <b>bajo el lock</b> para
 * validar el solapamiento, y la que resuelve la elegibilidad. Filtra por {@code APROBADA} en la
 * base porque una PENDIENTE no participa de ninguna de las dos preguntas —todavia no autoriza
 * ninguna cantidad—, y traerlas para descartarlas en memoria seria leer bajo el lock mas filas de
 * las que la regla mira.
 *
 * <p>Deliberadamente <b>no</b> filtra por fecha: quien decide el solapamiento es
 * {@code Autorizacion#seSolapaCon}, y expresar la comparacion de intervalos en JPQL la partiria
 * entre el repositorio y el dominio, que es como se termina con dos definiciones de la misma
 * regla que divergen.
 */
public interface AutorizacionRepository
		extends JpaRepository<Autorizacion, Long>, AutorizacionRepositoryPort {

	@Override
	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.id = :id
			   AND a.organizationId = :organizationId
			   AND a.personaId = :personaId
			""")
	Optional<Autorizacion> findByIdAndOrganizationIdAndPersonaId(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.id = :id
			   AND a.organizationId = :organizationId
			""")
	Optional<Autorizacion> findByIdAndOrganizationId(
			@Param("id") Long id, @Param("organizationId") Long organizationId);

	@Override
	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.organizationId = :organizationId
			   AND a.personaId = :personaId
			 ORDER BY a.vigenciaDesde DESC, a.id DESC
			""")
	List<Autorizacion> historial(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);

	@Override
	default List<Autorizacion> aprobadasDe(
			Long organizationId, Long coberturaId, Long practicaId) {

		return buscarPorEstado(
				organizationId, coberturaId, practicaId, EstadoAutorizacion.APROBADA);
	}

	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.organizationId = :organizationId
			   AND a.coberturaId = :coberturaId
			   AND a.practicaId = :practicaId
			   AND a.active = true
			   AND a.estado = :estado
			 ORDER BY a.vigenciaDesde DESC, a.id DESC
			""")
	List<Autorizacion> buscarPorEstado(
			@Param("organizationId") Long organizationId,
			@Param("coberturaId") Long coberturaId,
			@Param("practicaId") Long practicaId,
			@Param("estado") EstadoAutorizacion estado);

	@Override
	default List<Autorizacion> aprobadasDePersona(Long organizationId, Long personaId) {
		return buscarAprobadasDePersona(
				organizationId, personaId, EstadoAutorizacion.APROBADA);
	}

	/**
	 * Las aprobadas y activas del paciente, sin mirar cobertura ni practica.
	 *
	 * <p>Ordenadas por vencimiento mas proximo <b>primero, y con las que no vencen al final</b>:
	 * es el orden en que hay que gastarlas, porque la que vence antes es la que se pierde antes.
	 * Es el mismo desempate que {@code ElegibilidadAdministrativaService} ya aplicaba, movido a la
	 * consulta para que el observador del cierre no tenga que reordenar en memoria un conjunto que
	 * la base ya sabe ordenar por indice.
	 */
	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.organizationId = :organizationId
			   AND a.personaId = :personaId
			   AND a.active = true
			   AND a.estado = :estado
			 ORDER BY CASE WHEN a.vigenciaHasta IS NULL THEN 1 ELSE 0 END ASC,
			          a.vigenciaHasta ASC,
			          a.id ASC
			""")
	List<Autorizacion> buscarAprobadasDePersona(
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId,
			@Param("estado") EstadoAutorizacion estado);

	/**
	 * El descuento atomico del saldo. Ver el javadoc del puerto: la condicion del WHERE
	 * <b>es</b> la correctitud.
	 *
	 * <p>Nativo y no JPQL por dos motivos. Uno: JPQL no admite {@code UPDATE ... SET x = x + :n}
	 * con una condicion aritmetica sobre otras columnas de forma portable y legible. Dos, y es el
	 * que importa: hacerlo por JPA exigiria cargar la entidad, que es exactamente la lectura previa
	 * que se quiere evitar. Es la misma decision, y el mismo texto, que
	 * {@code CobroRepository#descontarSaldo} en 07.02.
	 *
	 * <p>{@code cantidad_autorizada IS NULL} pasa siempre: una autorizacion sin tope declarado no
	 * se puede agotar. El contador sube igual porque "cuantas se atendieron" sigue teniendo
	 * respuesta.
	 *
	 * <p>Filtra tambien por {@code active} y {@code estado}: una autorizacion dada de baja o no
	 * aprobada tiene saldo irrelevante, y el predicado explicito impide que un cambio futuro en
	 * como se resuelve una autorizacion deje pasar un consumo contra una que ya no habilita.
	 */
	@Modifying
	@Query(value = """
			UPDATE autorizacion
			   SET cantidad_consumida = cantidad_consumida + :cantidad
			 WHERE id = :autorizacionId
			   AND organization_id = :organizationId
			   AND active = 1
			   AND estado = 'APROBADA'
			   AND (cantidad_autorizada IS NULL
			        OR cantidad_autorizada - cantidad_consumida >= :cantidad)
			""", nativeQuery = true)
	@Override
	int descontarSaldo(
			@Param("organizationId") long organizationId,
			@Param("autorizacionId") long autorizacionId,
			@Param("cantidad") int cantidad);

	/**
	 * El {@code UPDATE} inverso de la reversion.
	 *
	 * <p>Condicional por el mismo motivo que el descuento: un consumo negativo es tan imposible
	 * como un saldo negativo. <b>No filtra por {@code estado}</b>, a diferencia del descuento, y
	 * eso es deliberado: devolver saldo a una autorizacion que despues se dio de baja o se
	 * rechazo sigue siendo correcto —la unidad no se gasto—, y negarse dejaria el ledger diciendo
	 * una cosa y la columna otra para siempre.
	 */
	@Modifying
	@Query(value = """
			UPDATE autorizacion
			   SET cantidad_consumida = cantidad_consumida - :cantidad
			 WHERE id = :autorizacionId
			   AND organization_id = :organizationId
			   AND cantidad_consumida >= :cantidad
			""", nativeQuery = true)
	@Override
	int devolverSaldo(
			@Param("organizationId") long organizationId,
			@Param("autorizacionId") long autorizacionId,
			@Param("cantidad") int cantidad);
}
