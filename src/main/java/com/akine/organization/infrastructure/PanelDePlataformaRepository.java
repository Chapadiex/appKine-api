package com.akine.organization.infrastructure;

import com.akine.organization.domain.PanelDePlataforma;
import com.akine.organization.domain.port.PanelDePlataformaPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Consultas del panel de la plataforma. Lee solo centros vigentes ({@code organization.deleted_at
 * IS NULL}) con su suscripcion vigente.
 */
@Repository
class PanelDePlataformaRepository implements PanelDePlataformaPort {

	private static final String CENTROS_VIGENTES =
			" FROM organization o"
					+ " JOIN subscription s ON s.organization_id = o.id AND s.deleted_at IS NULL"
					+ " WHERE o.deleted_at IS NULL";

	private final JdbcTemplate jdbc;

	PanelDePlataformaRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public PanelDePlataforma leer(Instant ahora, Instant porVencer, Instant horizonte, int cuantos) {
		Timestamp hoy = Timestamp.from(ahora);
		PanelDePlataforma.Centros centros = jdbc.queryForObject(
				"SELECT COUNT(*),"
						+ " COUNT(*) FILTER (WHERE s.status = 'ACTIVA'),"
						+ " COUNT(*) FILTER (WHERE s.status = 'PRUEBA' AND s.prueba_hasta > ?),"
						+ " COUNT(*) FILTER (WHERE s.status = 'PRUEBA' AND s.prueba_hasta > ? AND s.prueba_hasta <= ?),"
						+ " COUNT(*) FILTER (WHERE s.status = 'PRUEBA' AND s.prueba_hasta <= ?),"
						+ " COUNT(*) FILTER (WHERE s.status = 'SUSPENDIDA'),"
						+ " COUNT(*) FILTER (WHERE s.status = 'CANCELADA')"
						+ CENTROS_VIGENTES,
				(rs, i) -> new PanelDePlataforma.Centros(rs.getLong(1), rs.getLong(2), rs.getLong(3),
						rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)),
				hoy, hoy, Timestamp.from(porVencer), hoy);

		Long pedidos = jdbc.queryForObject(
				"SELECT COUNT(*) FROM solicitud_de_prueba WHERE estado = 'PENDIENTE'", Long.class);

		Long cambiosDePlan = jdbc.queryForObject(
				"SELECT COUNT(*) FROM pedido_de_cambio_de_plan WHERE estado = 'PENDIENTE'", Long.class);

		List<PanelDePlataforma.PruebaQueTermina> pruebas = jdbc.query(
				"SELECT o.id, o.name, s.prueba_hasta" + CENTROS_VIGENTES
						+ " AND s.status = 'PRUEBA' AND s.prueba_hasta <= ?"
						+ " ORDER BY s.prueba_hasta, o.id LIMIT ?",
				(rs, i) -> new PanelDePlataforma.PruebaQueTermina(
						rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
				Timestamp.from(horizonte), cuantos);

		List<PanelDePlataforma.CentroReciente> recientes = jdbc.query(
				"SELECT o.id, o.name, s.status, o.created_at" + CENTROS_VIGENTES
						+ " ORDER BY o.created_at DESC, o.id DESC LIMIT ?",
				(rs, i) -> new PanelDePlataforma.CentroReciente(
						rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
				cuantos);

		List<PanelDePlataforma.PedidoReciente> ultimosPedidos = jdbc.query(
				"SELECT id, nombre_centro, nombre || ' ' || apellido, estado, created_at"
						+ " FROM solicitud_de_prueba ORDER BY created_at DESC, id DESC LIMIT ?",
				(rs, i) -> new PanelDePlataforma.PedidoReciente(rs.getLong(1), rs.getString(2),
						rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()),
				cuantos);

		List<PanelDePlataforma.CambioDeSuscripcion> cambios = jdbc.query(
				"SELECT t.organization_id, o.name, t.from_status, t.to_status, t.occurred_at"
						+ " FROM subscription_transition t JOIN organization o ON o.id = t.organization_id"
						+ " ORDER BY t.occurred_at DESC, t.id DESC LIMIT ?",
				(rs, i) -> new PanelDePlataforma.CambioDeSuscripcion(rs.getLong(1), rs.getString(2),
						rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()),
				cuantos);

		return new PanelDePlataforma(centros, pedidos == null ? 0 : pedidos, pruebas, recientes,
				ultimosPedidos, cambios, cambiosDePlan == null ? 0 : cambiosDePlan);
	}
}
