package com.akine.offering;

import com.akine.TestcontainersConfiguration;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.Naturaleza;
import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V143 contra MySQL real: dos poblaciones en {@code servicio}, separadas por {@code owner_key}.
 *
 * <p>Lo que un unitario no puede ver: que el unique realmente admita el mismo codigo en el catalogo
 * global y como servicio propio, que lo rechace dos veces dentro del mismo duenio, y que la consulta
 * por {@code owner_key} no esconda el global ni filtre el servicio de otro centro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ServicioPropioIT {

	@Autowired private ServicioRepositoryPort servicios;
	@Autowired private JdbcTemplate jdbc;

	private String codigo;
	private long centroA;
	private long centroB;

	@BeforeEach
	void setUp() {
		codigo = "YOGA-" + UUID.randomUUID().toString().substring(0, 8);
		centroA = crearOrganizacion();
		centroB = crearOrganizacion();
	}

	@Test
	@DisplayName("El mismo codigo vive en el global y como propio; repetido dentro del mismo duenio choca")
	void el_unique_es_por_duenio() {
		servicios.saveAndFlush(yoga(null));
		servicios.saveAndFlush(yoga(centroA));
		servicios.saveAndFlush(yoga(centroB));

		assertThatThrownBy(() -> servicios.saveAndFlush(yoga(centroA)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Cada centro ve el global y lo suyo, nunca lo del otro; la plataforma ve solo el global")
	void la_visibilidad_sale_de_owner_key() {
		Servicio global = servicios.saveAndFlush(yoga(null));
		Servicio deA = servicios.saveAndFlush(yoga(centroA));
		Servicio deB = servicios.saveAndFlush(yoga(centroB));
		String patron = codigo + "%";

		assertThat(servicios.buscarVisibles(List.of(0L, centroA), patron, 1)).extracting(Servicio::getId)
				.containsExactlyInAnyOrder(global.getId(), deA.getId());
		assertThat(servicios.buscarVisibles(List.of(0L), patron, 1)).extracting(Servicio::getId)
				.containsExactly(global.getId());
		assertThat(servicios.findVisible(deB.getId(), List.of(0L, centroA)))
				.as("el servicio de otro centro no existe para A: 404")
				.isEmpty();
		assertThat(servicios.findVisible(global.getId(), List.of(0L, centroA))).isPresent();
	}

	private Servicio yoga(Long organizationId) {
		return new Servicio(organizationId, codigo, "Yoga " + codigo, null, Naturaleza.BIENESTAR,
				Modalidad.GRUPAL, false, false);
	}

	private long crearOrganizacion() {
		String slug = "servicio-propio-" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + slug, slug);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}
}
