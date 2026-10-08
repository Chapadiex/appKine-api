package com.akine.person.infrastructure;

import com.akine.person.domain.AutorizacionEvento;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionEventoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Historial append-only de una autorizacion (DP-23). El puerto no expone ni modificacion ni borrado. */
public interface AutorizacionEventoRepository
		extends JpaRepository<AutorizacionEvento, Long>, AutorizacionEventoRepositoryPort {

	@Override
	default AutorizacionEvento registrar(AutorizacionEvento evento) {
		return save(evento);
	}

	@Override
	@Query(value = """
			SELECT * FROM autorizacion_evento e
			 WHERE e.organization_id = :organizationId
			   AND e.autorizacion_id = :autorizacionId
			 ORDER BY e.ocurrido_en ASC, e.id ASC
			 LIMIT :limite OFFSET :offset
			""", nativeQuery = true)
	List<AutorizacionEvento> pagina(
			@Param("organizationId") long organizationId,
			@Param("autorizacionId") long autorizacionId,
			@Param("offset") int offset,
			@Param("limite") int limite);

	@Override
	@Query(value = """
			SELECT COUNT(*) FROM autorizacion_evento e
			 WHERE e.organization_id = :organizationId
			   AND e.autorizacion_id = :autorizacionId
			""", nativeQuery = true)
	long contar(
			@Param("organizationId") long organizationId,
			@Param("autorizacionId") long autorizacionId);
}
