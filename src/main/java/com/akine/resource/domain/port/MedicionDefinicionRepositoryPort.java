package com.akine.resource.domain.port;

import com.akine.resource.domain.MedicionDefinicion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia del catalogo de definiciones de medicion (M06, AKINE-06.03).
 *
 * <p>Vive en {@code domain} porque {@code application} consume puertos y nunca repositorios de
 * {@code infrastructure}: regla que 01.01 dejo fijada y que ArchUnit verifica.
 *
 * <h2>{@code owners}, y por que no {@code organizationId}</h2>
 *
 * <p>Mismo protocolo de aislamiento que {@code CatalogoRepositoryPorts}, y por el mismo motivo:
 * una definicion global no tiene tenant y tiene que ser visible para todos, asi que filtrar por
 * {@code organization_id = ?} dejaria fuera medio catalogo. Toda consulta recibe la coleccion de
 * <b>duenios visibles</b> sobre la columna generada {@code owner_key} —el id del tenant, o el
 * centinela {@code 0} para lo global—:
 *
 * <pre>
 *   solo global          -&gt; [0]
 *   lo que ve un tenant  -&gt; [0, organizationId]
 * </pre>
 *
 * <p>La lista la arma el servicio <b>despues</b> de validar el contexto, nunca el cliente: un id
 * de otro tenant no aparece en ella y por lo tanto no resuelve nunca.
 *
 * <p>Las consultas son <b>nativas</b> porque {@code owner_key} es una columna generada: no existe
 * en la entidad —mapearla invitaria a escribirla— y por lo tanto no se puede nombrar en JPQL.
 */
public interface MedicionDefinicionRepositoryPort {

	MedicionDefinicion save(MedicionDefinicion definicion);

	/**
	 * Guarda y FUERZA el flush.
	 *
	 * <p>La violacion de {@code uk_medicion_definicion_codigo_vigente} tiene que manifestarse
	 * dentro del bloque que sabe traducirla a un 409, y no al cerrar la transaccion, donde ya no
	 * hay a quien avisarle y el advice generico responde 500.
	 *
	 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA.</b> Ni una lectura, ni
	 * un {@code save}, ni la auditoria: la especificacion lo prohibe y lo que sale de ahi es un
	 * {@code AssertionFailure} que convierte un 409 legitimo en un 500.
	 */
	MedicionDefinicion saveAndFlush(MedicionDefinicion definicion);

	/** Una definicion por id, acotada a los duenios visibles. Activa o no: ver el spi. */
	Optional<MedicionDefinicion> findVisible(Long id, Collection<Long> owners);

	/**
	 * Busqueda ordenada por nombre.
	 *
	 * <p>Los filtros opcionales viajan como <b>centinelas</b> y no como {@code null}:
	 * {@code activoFiltro = -1} significa "no filtres por estado" y {@code patron = "%"} trae
	 * todo. La alternativa —{@code (:param IS NULL OR col = :param)}— obliga a Hibernate a
	 * inferir el tipo de un parametro nulo en una consulta nativa, que es el caso en el que falla
	 * con un "could not determine type".
	 */
	List<MedicionDefinicion> buscar(
			Collection<Long> owners, String patron, int activoFiltro);
}
