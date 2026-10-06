package com.akine.offering;

import com.akine.TestcontainersConfiguration;
import com.akine.offering.application.HabilitacionesView;
import com.akine.offering.application.OfertaHabilitacionService;
import com.akine.offering.application.OperatingActor;
import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario diferido 20 de {@code docs/tests-diferidos.md} (AKINE-02.07), contra MySQL real.
 *
 * <p>Reemplazar las habilitaciones de una oferta solo escribe en tablas hijas: ninguna columna de
 * {@code oferta_servicio_consultorio} queda sucia, asi que su {@code @Version} no avanzaria sola y
 * el segundo administrador que guarda con la version vieja pisaria al primero sin enterarse. Lo
 * evita {@code OPTIMISTIC_FORCE_INCREMENT} sobre la carga de la oferta. Los unitarios fijan que se
 * usa ese metodo; que <b>Hibernate efectivamente suba la version al commitear</b> solo lo contesta
 * una base real.
 *
 * <p>Y la reciproca de 04.02: la version avanza <b>una</b> vez, no dos. Si el padre tambien quedara
 * sucio, el cliente recibiria {@code leida+1} y su siguiente guardado chocaria contra
 * {@code leida+2}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HabilitacionesVersionForzadaIT {

	@Autowired private OfertaHabilitacionService habilitaciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("escenario 20: el segundo administrador que guarda con la version vieja recibe 409")
	void el_segundo_en_guardar_choca() {
		Fixture fixture = new AgendaFixtures(jdbc).crear(1);
		OperatingActor admin = new OperatingActor(
				fixture.actor().accountId(), false, fixture.organizationId(), fixture.consultorioId());
		long leida = versionDeLaOferta(fixture);

		// A y B leyeron la misma version. A guarda primero.
		habilitaciones.reemplazarProfesionales(admin, fixture.organizationId(),
				fixture.consultorioId(), fixture.ofertaId(),
				Set.of(fixture.profesionalMembershipId()), leida);

		assertThat(versionDeLaOferta(fixture))
				.as("el reemplazo solo toca tablas hijas y aun asi la version avanza, una sola vez")
				.isEqualTo(leida + 1);

		// B guarda con la version que leyo antes que A.
		assertThatThrownBy(() -> habilitaciones.reemplazarProfesionales(admin,
				fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				Set.of(), leida))
				.isInstanceOf(OptimisticLockingFailureException.class);

		// Control negativo: releyendo, B entra. Si fallara igual, el 409 de arriba no probaria nada.
		habilitaciones.reemplazarProfesionales(admin, fixture.organizationId(),
				fixture.consultorioId(), fixture.ofertaId(), Set.of(), leida + 1);
		assertThat(versionDeLaOferta(fixture)).isEqualTo(leida + 2);
	}

	@Test
	@DisplayName("ofertaVersion: tres reemplazos encadenados con la version que devolvio el "
			+ "anterior, sin releer, entran todos")
	void los_reemplazos_se_encadenan_con_la_version_devuelta() {
		Fixture fixture = new AgendaFixtures(jdbc).crear(1);
		OperatingActor admin = new OperatingActor(
				fixture.actor().accountId(), false, fixture.organizationId(), fixture.consultorioId());
		// La lectura exige pertenencia, y la pertenencia exige una organizacion con suscripcion
		// vigente. AgendaFixtures no la crea porque sus consumidores no pasan por ahi.
		jdbc.update("""
				INSERT INTO subscription (organization_id, plan_id, status, started_at, active,
				                          version, created_at, updated_at)
				SELECT ?, id, 'ACTIVA', UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
				FROM plan WHERE code = 'BASICO'
				""", fixture.organizationId());

		// El cliente parte de una lectura, como la pantalla.
		HabilitacionesView leida = habilitaciones.leer(admin, fixture.organizationId(),
				fixture.consultorioId(), fixture.ofertaId());
		assertThat(leida.ofertaVersion()).isEqualTo(versionDeLaOferta(fixture));

		// Cada guardado usa SOLO lo que respondio el anterior. Si la respuesta devolviera la
		// version leida (el force-increment se aplica al commitear, despues de armarla), el
		// segundo moriria con 409. Mezclar los dos endpoints prueba que comparten la version.
		HabilitacionesView primera = habilitaciones.reemplazarProfesionales(admin,
				fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				Set.of(fixture.profesionalMembershipId()), leida.ofertaVersion());
		assertThat(primera.ofertaVersion())
				.as("lo devuelto es lo que quedo en la base despues del commit")
				.isEqualTo(versionDeLaOferta(fixture))
				.isEqualTo(leida.ofertaVersion() + 1);

		HabilitacionesView segunda = habilitaciones.reemplazarEspacios(admin,
				fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				Set.of(), primera.ofertaVersion());
		assertThat(segunda.ofertaVersion()).isEqualTo(versionDeLaOferta(fixture));

		HabilitacionesView tercera = habilitaciones.reemplazarProfesionales(admin,
				fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				Set.of(), segunda.ofertaVersion());
		assertThat(tercera.ofertaVersion())
				.isEqualTo(versionDeLaOferta(fixture))
				.isEqualTo(leida.ofertaVersion() + 3);
		assertThat(tercera.restringidaPorProfesional()).isFalse();

		// Y la version devuelta sigue protegiendo: la de dos pasos atras choca.
		assertThatThrownBy(() -> habilitaciones.reemplazarProfesionales(admin,
				fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				Set.of(), primera.ofertaVersion()))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	private long versionDeLaOferta(Fixture fixture) {
		return jdbc.queryForObject(
				"SELECT version FROM oferta_servicio_consultorio WHERE id = ?",
				Long.class, fixture.ofertaId());
	}
}
