package com.akine.person.infrastructure;

import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Acceso al padron de personas. <b>Toda consulta filtra por {@code organizationId}</b>: una
 * persona de otro tenant no debe resolver nunca, ni siquiera para contarla. Ver la cabecera de
 * {@code PersonRepositoryPorts}.
 *
 * <p>{@code save} y {@code saveAndFlush} los satisface {@link JpaRepository} tal cual.
 */
public interface PersonaRepository extends JpaRepository<Persona, Long>, PersonaRepositoryPort {

	@Override
	Optional<Persona> findByIdAndOrganizationId(Long id, Long organizationId);

	/**
	 * Busqueda paginada del padron (RF-M07-001).
	 *
	 * <p>Nativa por dos motivos que se suman. El primero es el de siempre: los centinelas
	 * {@code -1} se comparan contra columnas {@code TINYINT} y en JPQL un campo {@code boolean}
	 * no se compara contra un entero. El segundo es el {@code EXISTS} contra
	 * {@code perfil_paciente}, que en JPQL exigiria una relacion JPA entre las dos entidades —y
	 * esa relacion es exactamente lo que {@code PerfilPaciente} evita a proposito, para que nadie
	 * navegue de una persona a su perfil sin quererlo.
	 *
	 * <p>Tambien encuentra por numero de afiliado: un {@code EXISTS} (no un JOIN, para que una
	 * persona con varias coberturas que matchean salga una sola vez) contra las coberturas no
	 * dadas de baja de la misma organizacion, de cualquier vigencia. El numero se guarda tal como
	 * se tipeo, asi que se le quitan los separadores en la consulta —todo lo que no sea letra o
	 * digito ASCII, igual que {@code ClaveDeBusqueda.deDocumento}— y se compara contra
	 * {@code patronClave}. Sin migracion: no hay columna de clave materializada.
	 *
	 * <p>El {@code LIMIT/OFFSET} esta en la consulta y no se recorta en memoria: el padron es la
	 * primera tabla del sistema con volumen real (RNF-M07-004).
	 *
	 * <p><b>El orden es determinista a proposito</b> — apellido, nombre, id. Sin el {@code id}
	 * final, dos personas homonimas pueden salir en distinto orden entre la pagina 1 y la 2 y una
	 * de ellas aparece dos veces mientras otra no aparece nunca. Es un bug de paginado clasico y
	 * silencioso.
	 */
	@Override
	@Query(value = """
			SELECT * FROM persona p
			 WHERE p.organization_id = :organizationId
			   AND (:activoFiltro = -1 OR p.active = :activoFiltro)
			   AND (:perfilFiltro = -1
			        OR (:perfilFiltro = 1 AND EXISTS (SELECT 1 FROM perfil_paciente pp
			                                           WHERE pp.persona_id = p.id
			                                             AND pp.organization_id = p.organization_id
			                                             AND pp.active = 1))
			        OR (:perfilFiltro = 0 AND NOT EXISTS (SELECT 1 FROM perfil_paciente pp
			                                               WHERE pp.persona_id = p.id
			                                                 AND pp.organization_id = p.organization_id
			                                                 AND pp.active = 1)))
			   AND (p.documento_clave LIKE :patronClave
			        OR p.apellido_clave LIKE :patronNombre
			        OR p.nombre_clave LIKE :patronNombre
			        OR p.telefono_clave LIKE :patronClave
			        OR EXISTS (SELECT 1 FROM cobertura_paciente cp
			                    WHERE cp.persona_id = p.id
			                      AND cp.organization_id = p.organization_id
			                      AND cp.active = 1
			                      AND REGEXP_REPLACE(UPPER(cp.numero_afiliado), '[^A-Z0-9]', '')
			                          LIKE :patronClave))
			 ORDER BY p.apellido_clave ASC, p.nombre_clave ASC, p.id ASC
			 LIMIT :limite OFFSET :offset
			""", nativeQuery = true)
	List<Persona> buscar(
			@Param("organizationId") Long organizationId,
			@Param("patronNombre") String patronNombre,
			@Param("patronClave") String patronClave,
			@Param("activoFiltro") int activoFiltro,
			@Param("perfilFiltro") int perfilFiltro,
			@Param("offset") int offset,
			@Param("limite") int limite);

	/** El total del mismo filtro. Misma clausula {@code WHERE}, palabra por palabra. */
	@Override
	@Query(value = """
			SELECT COUNT(*) FROM persona p
			 WHERE p.organization_id = :organizationId
			   AND (:activoFiltro = -1 OR p.active = :activoFiltro)
			   AND (:perfilFiltro = -1
			        OR (:perfilFiltro = 1 AND EXISTS (SELECT 1 FROM perfil_paciente pp
			                                           WHERE pp.persona_id = p.id
			                                             AND pp.organization_id = p.organization_id
			                                             AND pp.active = 1))
			        OR (:perfilFiltro = 0 AND NOT EXISTS (SELECT 1 FROM perfil_paciente pp
			                                               WHERE pp.persona_id = p.id
			                                                 AND pp.organization_id = p.organization_id
			                                                 AND pp.active = 1)))
			   AND (p.documento_clave LIKE :patronClave
			        OR p.apellido_clave LIKE :patronNombre
			        OR p.nombre_clave LIKE :patronNombre
			        OR p.telefono_clave LIKE :patronClave
			        OR EXISTS (SELECT 1 FROM cobertura_paciente cp
			                    WHERE cp.persona_id = p.id
			                      AND cp.organization_id = p.organization_id
			                      AND cp.active = 1
			                      AND REGEXP_REPLACE(UPPER(cp.numero_afiliado), '[^A-Z0-9]', '')
			                          LIKE :patronClave))
			""", nativeQuery = true)
	long contar(
			@Param("organizationId") Long organizationId,
			@Param("patronNombre") String patronNombre,
			@Param("patronClave") String patronClave,
			@Param("activoFiltro") int activoFiltro,
			@Param("perfilFiltro") int perfilFiltro);

	/**
	 * La persona vigente que ya tiene ese documento. Es la que se le muestra al operador cuando
	 * el unique rechaza un alta.
	 */
	@Override
	@Query("""
			SELECT p FROM Persona p
			 WHERE p.organizationId = :organizationId
			   AND p.tipoDocumento = :tipoDocumento
			   AND p.documentoClave = :documentoClave
			   AND p.active = true
			""")
	Optional<Persona> buscarVigentePorDocumento(
			@Param("organizationId") Long organizationId,
			@Param("tipoDocumento") TipoDocumento tipoDocumento,
			@Param("documentoClave") String documentoClave);

	/**
	 * Coincidencias exactas por nombre completo o por telefono (RN-M07-001).
	 *
	 * <p>{@code telefonoClave} puede llegar {@code null} y la condicion lo contempla: sin
	 * telefono, esa mitad no aporta ninguna fila en vez de traer a todas las personas sin
	 * telefono de la organizacion, que es lo que haria un {@code = NULL} mal escrito.
	 *
	 * <p>JPQL y no nativa: no hay centinela ni {@code EXISTS} que lo impida, y el parametro nulo
	 * lleva tipo declarado por la firma del metodo.
	 */
	@Override
	@Query("""
			SELECT p FROM Persona p
			 WHERE p.organizationId = :organizationId
			   AND p.active = true
			   AND ((p.apellidoClave = :apellidoClave AND p.nombreClave = :nombreClave)
			        OR (:telefonoClave IS NOT NULL AND p.telefonoClave = :telefonoClave))
			 ORDER BY p.id ASC
			""")
	List<Persona> buscarCoincidencias(
			@Param("organizationId") Long organizationId,
			@Param("apellidoClave") String apellidoClave,
			@Param("nombreClave") String nombreClave,
			@Param("telefonoClave") String telefonoClave);
}
