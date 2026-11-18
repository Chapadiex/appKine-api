package com.akine.organization.infrastructure;

import com.akine.organization.domain.port.ContratacionRepositoryPort;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.EnumSet;
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
	public boolean definida(long organizationId) {
		List<Boolean> marca = jdbc.queryForList(
				"SELECT contratacion_definida FROM subscription WHERE organization_id = ?",
				Boolean.class, organizationId);
		return !marca.isEmpty() && Boolean.TRUE.equals(marca.get(0));
	}
}
