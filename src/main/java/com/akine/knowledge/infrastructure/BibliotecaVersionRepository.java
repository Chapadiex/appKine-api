package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BibliotecaVersionRepository implements BibliotecaVersionPort {

	private final JdbcTemplate jdbc;

	BibliotecaVersionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void avanzar(long orgId) {
		jdbc.update("""
				INSERT INTO biblioteca_version (organization_id, version) VALUES (?, 1)
				ON DUPLICATE KEY UPDATE version = version + 1
				""", orgId);
	}

	@Override
	public long actual(long orgId) {
		Long v = jdbc.query("SELECT version FROM biblioteca_version WHERE organization_id = ?",
				rs -> rs.next() ? rs.getLong(1) : 0L, orgId);
		return v == null ? 0L : v;
	}
}
