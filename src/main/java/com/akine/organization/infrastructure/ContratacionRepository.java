package com.akine.organization.infrastructure;

import com.akine.organization.domain.port.ContratacionRepositoryPort;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.akine.organization.spi.PruebasGratis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adaptador de {@link ContratacionRepositoryPort} por SQL plano: las filas no tienen
 * comportamiento ni version, son el conjunto de lo contratado.
 *
 * <p><b>Sin marca, todo.</b> Mientras {@code subscription.contratacion_definida} sea false —o la
 * organizacion no tenga suscripcion, como las que arman los ITs por SQL— el centro tiene los dos
 * servicios y la IA, como antes del 18/11/2026. {@link #reemplazar} prende la marca.
 *
 * <p>Reemplazar las filas no borra historia clinica ni economica: el cambio queda en la
 * auditoria y en {@code subscription_transition}.
 */
@Repository
class ContratacionRepository implements ContratacionRepositoryPort {

	private final JdbcTemplate jdbc;

	ContratacionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<Servicio> serviciosDe(long organizationId) {
		if (!definida(organizationId)) {
			return EnumSet.allOf(Servicio.class);
		}
		Set<Servicio> servicios = EnumSet.noneOf(Servicio.class);
		jdbc.query("SELECT servicio FROM subscription_servicio WHERE organization_id = ?",
				rs -> {
					servicios.add(Servicio.valueOf(rs.getString(1)));
				}, organizationId);
		return servicios;
	}

	@Override
	public Set<Adicional> adicionalesDe(long organizationId) {
		if (!definida(organizationId)) {
			return EnumSet.allOf(Adicional.class);
		}
		Set<Adicional> adicionales = EnumSet.noneOf(Adicional.class);
		jdbc.query("SELECT adicional FROM subscription_adicional WHERE organization_id = ?",
				rs -> {
					adicionales.add(Adicional.valueOf(rs.getString(1)));
				}, organizationId);
		return adicionales;
	}

	@Override
	public boolean nacioEnPrueba(long organizationId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM subscription_transition WHERE organization_id = ?"
						+ " AND from_status IS NULL AND to_status = 'PRUEBA')",
				Boolean.class, organizationId));
	}

	@Override
	public void reemplazar(long organizationId, Set<Servicio> servicios, Set<Adicional> adicionales) {
		jdbc.update("DELETE FROM subscription_servicio WHERE organization_id = ?", organizationId);
		for (Servicio servicio : servicios) {
			jdbc.update("INSERT INTO subscription_servicio (organization_id, servicio, created_at) "
					+ "VALUES (?, ?, now())", organizationId, servicio.name());
		}
		jdbc.update("DELETE FROM subscription_adicional WHERE organization_id = ?", organizationId);
		for (Adicional adicional : adicionales) {
			jdbc.update("INSERT INTO subscription_adicional (organization_id, adicional, created_at) "
					+ "VALUES (?, ?, now())", organizationId, adicional.name());
		}
		jdbc.update("UPDATE subscription SET contratacion_definida = true WHERE organization_id = ?",
				organizationId);
	}

	@Override
	public List<PruebasGratis.PruebaEnCurso> pruebasQueTerminanEntre(Instant desde, Instant hasta) {
		return jdbc.query("""
				SELECT o.id, o.name, s.prueba_hasta
				  FROM subscription s
				  JOIN organization o ON o.id = s.organization_id AND o.active
				 WHERE s.status = 'PRUEBA' AND s.active
				   AND s.prueba_hasta >= ? AND s.prueba_hasta < ?
				 ORDER BY s.prueba_hasta, o.id""",
				(rs, n) -> new PruebasGratis.PruebaEnCurso(rs.getLong(1), rs.getString(2),
						rs.getTimestamp(3).toInstant()),
				Timestamp.from(desde), Timestamp.from(hasta));
	}

	@Override
	public Set<Long> administradores(long organizationId) {
		return new HashSet<>(jdbc.queryForList("""
				SELECT DISTINCT account_id FROM membership
				 WHERE organization_id = ? AND role_code = 'ORG_ADMIN' AND estado = 'ACTIVA'
				   AND active AND deleted_at IS NULL AND valid_from <= now()
				   AND (valid_until IS NULL OR valid_until > now())""", Long.class, organizationId));
	}

	@Override
	public boolean definida(long organizationId) {
		List<Boolean> marca = jdbc.queryForList(
				"SELECT contratacion_definida FROM subscription WHERE organization_id = ?",
				Boolean.class, organizationId);
		return !marca.isEmpty() && Boolean.TRUE.equals(marca.get(0));
	}

	@Override
	public Set<Long> conAdicional(java.util.Collection<Long> organizationIds, Adicional adicional) {
		if (organizationIds.isEmpty()) {
			return Set.of();
		}
		Set<Long> con = new HashSet<>();
		jdbc.query("SELECT s.organization_id FROM subscription s"
						+ " WHERE s.organization_id = ANY (?) AND s.deleted_at IS NULL"
						+ " AND (s.contratacion_definida = false OR EXISTS (SELECT 1 FROM subscription_adicional a"
						+ " WHERE a.organization_id = s.organization_id AND a.adicional = ?))",
				rs -> {
					con.add(rs.getLong(1));
				},
				organizationIds.toArray(Long[]::new), adicional.name());
		return con;
	}

	@Override
	public java.util.Map<Long, Set<Servicio>> serviciosDeVarios(java.util.Collection<Long> organizationIds) {
		java.util.Map<Long, Set<Servicio>> porCentro = new java.util.HashMap<>();
		if (organizationIds.isEmpty()) {
			return porCentro;
		}
		jdbc.query("SELECT s.organization_id, s.contratacion_definida, ss.servicio FROM subscription s"
						+ " LEFT JOIN subscription_servicio ss ON ss.organization_id = s.organization_id"
						+ " WHERE s.organization_id = ANY (?) AND s.deleted_at IS NULL",
				rs -> {
					Set<Servicio> servicios = porCentro.computeIfAbsent(rs.getLong(1),
							id -> EnumSet.noneOf(Servicio.class));
					if (!rs.getBoolean(2)) {
						servicios.addAll(EnumSet.allOf(Servicio.class));
					} else if (rs.getString(3) != null) {
						servicios.add(Servicio.valueOf(rs.getString(3)));
					}
				},
				// El cast evita que el arreglo se reparta como varargs: es UN parametro, el de ANY (?).
				(Object) organizationIds.toArray(Long[]::new));
		return porCentro;
	}
}
