package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.application.FuncionesIaService;
import com.akine.organization.application.FuncionesIaView;
import com.akine.organization.spi.FuncionIa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las dos capas de las funciones de IA contra PostgreSQL: la fila que nace en la primera edicion, el
 * default prendido sin fila, la dependencia de las sugerencias y el despliegue que manda. El perfil
 * {@code local} deja la IA apagada en el despliegue: es justamente el caso "el centro la quiere y el
 * servidor no la ofrece".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class FuncionesIaDeOrganizacionIT {

	@Autowired private FuncionesIaService funciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("sin fila: el centro tiene todo prendido, leer no escribe, y nada queda activo si el despliegue no lo ofrece")
	void sin_fila_todo_prendido() {
		long org = insertarOrganizacion();

		for (FuncionIa f : FuncionIa.values()) {
			assertThat(funciones.prendida(org, f)).as(f.name()).isTrue();
		}
		FuncionesIaView vista = funciones.ver(org);
		assertThat(vista.version()).isZero();
		assertThat(vista.funciones()).hasSize(4).allSatisfy(f -> {
			assertThat(f.centro()).isTrue();
			assertThat(f.despliegue()).as("el perfil local no ofrece IA").isFalse();
			assertThat(f.activa()).as("activa exige el despliegue").isFalse();
		});
		assertThat(filas(org)).as("leer no crea la fila").isZero();
	}

	@Test
	@DisplayName("apagar la biblioteca apaga las sugerencias sin tocar su interruptor")
	void sugerencias_dependen_de_biblioteca_y_evolucion() {
		long org = insertarOrganizacion();

		FuncionesIaView guardada = funciones.actualizar(org, true, true, true, false, 0, null);

		assertThat(guardada.version()).as("la version nueva, no la leida").isEqualTo(1);
		assertThat(funciones.prendida(org, FuncionIa.ASISTENTE)).isTrue();
		assertThat(funciones.prendida(org, FuncionIa.BIBLIOTECA)).isFalse();
		assertThat(funciones.prendida(org, FuncionIa.SUGERENCIAS)).as("depende de la biblioteca").isFalse();
		assertThat(guardada.funciones()).filteredOn(f -> f.funcion() == FuncionIa.SUGERENCIAS)
				.singleElement().satisfies(f -> assertThat(f.centro()).as("su interruptor sigue prendido").isTrue());
		assertThat(jdbc.queryForObject(
				"SELECT details ->> 'apagadas' FROM audit_event WHERE organization_id = ? AND event_type = 'ORGANIZATION_IA_UPDATED'",
				String.class, org)).as("el detalle no queda tachado como contenido clinico").isEqualTo("BIBLIOTECA");
	}

	@Test
	@DisplayName("una version vieja no pisa la eleccion de otro")
	void version_vieja_choca() {
		long org = insertarOrganizacion();
		funciones.actualizar(org, false, true, true, true, 0, null);

		assertThatThrownBy(() -> funciones.actualizar(org, true, true, true, true, 0, null))
				.isInstanceOf(OptimisticLockingFailureException.class);
		assertThat(funciones.prendida(org, FuncionIa.ASISTENTE)).isFalse();
	}

	@Test
	@DisplayName("lo que elige un centro no alcanza a otro")
	void aislado_por_organizacion() {
		long a = insertarOrganizacion();
		long b = insertarOrganizacion();

		funciones.actualizar(a, false, false, false, false, 0, null);

		assertThat(funciones.prendida(b, FuncionIa.ASISTENTE)).isTrue();
		assertThat(filas(b)).isZero();
	}

	private int filas(long org) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM organization_ia WHERE organization_id = ?", Integer.class, org);
	}

	private long insertarOrganizacion() {
		String slug = "ia-it-" + UUID.randomUUID().toString().substring(0, 8);
		return jdbc.queryForObject("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				RETURNING id""", Long.class, "Centro sintetico " + slug, slug);
	}
}
