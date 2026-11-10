package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.LibroFragmento;
import com.akine.knowledge.domain.port.LibroFragmentoRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Adaptador de fragmentos. La insercion va por JDBC en lote: con claves IDENTITY Hibernate no
 * agrupa los INSERT y un libro puede tener miles de fragmentos.
 */
@Repository
class LibroFragmentoRepository implements LibroFragmentoRepositoryPort {

	private static final int LOTE = 500;

	private final LibroFragmentoJpa jpa;
	private final JdbcTemplate jdbc;

	LibroFragmentoRepository(LibroFragmentoJpa jpa, JdbcTemplate jdbc) {
		this.jpa = jpa;
		this.jdbc = jdbc;
	}

	@Override
	public void reemplazarDeLibro(long libroId, List<LibroFragmento> fragmentos) {
		jpa.borrarDeLibro(libroId);
		jdbc.batchUpdate("""
				INSERT INTO libro_fragmento
				  (organization_id, libro_id, orden, pagina_desde, pagina_hasta, texto,
				   vector, modelo_embeddings, sospechoso)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", fragmentos, LOTE, (ps, f) -> {
			ps.setLong(1, f.getOrganizationId());
			ps.setLong(2, libroId);
			ps.setInt(3, f.getOrden());
			ps.setInt(4, f.getPaginaDesde());
			ps.setInt(5, f.getPaginaHasta());
			ps.setString(6, f.getTexto());
			ps.setBytes(7, f.getVector());
			ps.setString(8, f.getVector() == null ? null : f.getModeloEmbeddings());
			ps.setBoolean(9, f.isSospechoso());
		});
	}

	@Override
	public List<Long> reclamarSinVector(String modeloVigente, int lote) {
		return jpa.reclamarSinVector(modeloVigente, lote);
	}

	@Override
	public void actualizarVector(long id, byte[] vector, String modelo) {
		jpa.actualizarVector(id, vector, modelo);
	}

	@Override
	public long contarPorLibro(long libroId) {
		return jpa.countByLibroId(libroId);
	}

	@Override
	public List<LibroFragmento> sospechososDe(long libroId) {
		return jpa.findByLibroIdAndSospechosoTrueOrderByOrden(libroId);
	}
}
