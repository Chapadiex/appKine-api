package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.SesionVersion;
import com.akine.encounter.domain.port.SesionVersionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SesionVersionRepository
		extends JpaRepository<SesionVersion, Long>, SesionVersionRepositoryPort {

	/**
	 * {@inheritDoc}
	 *
	 * <p>JPQL y no nativa: Hibernate la valida contra el modelo al arrancar la aplicacion, asi que
	 * un nombre de campo mal escrito rompe el contexto en vez de aparecer en runtime. Importa mas
	 * que de costumbre porque <b>esta etapa no puede correr tests de integracion</b> —Docker no
	 * esta disponible— y el arranque del contexto es la unica verificacion de esquema que queda.
	 *
	 * <p>{@code ORDER BY numeroVersion} ascendente: el historial se lee de la 1 hacia adelante,
	 * que es el orden en que se cuenta lo que paso. No hace falta desempate porque
	 * {@code uk_sesion_version_numero} garantiza que el numero es unico dentro de la sesion.
	 */
	@Override
	@Query("""
			SELECT v FROM SesionVersion v
			 WHERE v.organizationId = :organizationId
			   AND v.sesionId = :sesionId
			 ORDER BY v.numeroVersion ASC
			""")
	List<SesionVersion> buscarPorSesion(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);
}
