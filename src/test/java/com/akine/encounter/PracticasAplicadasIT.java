package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Miembro;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import com.akine.organization.application.PermissionEvaluatorService;
import com.akine.organization.spi.PermissionDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/**
 * {@code practicas_aplicadas} contra el motor (foco 1 del diseño del agente, §10.8): dos
 * profesionales aplican TENS en la misma sede; el que tiene {@code hc:read} recortado a su
 * actividad ve solo lo suyo y la advertencia, el otro ve las dos.
 *
 * <p>Ningun rol base tiene hoy {@code hc:read} con alcance de actividad propia, asi que el
 * evaluador real se espia y se le concede ese alcance a UNA cuenta; todo lo demas es la cadena real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PracticasAplicadasIT {

	private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");

	@Autowired private List<HerramientaDelAgente> herramientas;
	@Autowired private JdbcTemplate jdbc;

	@MockitoSpyBean
	private PermissionEvaluatorService permisos;

	@AfterEach
	void restaurarPermisos() {
		reset(permisos);
	}

	@Test
	@DisplayName("el profesional con hc:read limitado ve solo sus TENS y la advertencia; el otro ve los dos")
	void alcance_propio_recorta_y_avisa() {
		EncounterFixtures fixtures = new EncounterFixtures(jdbc);
		Mundo mundo = fixtures.crearMundo();
		Miembro propio = mundo.profesional();
		Miembro otro = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
				"PROFESIONAL", "prof-b-" + mundo.sufijo());
		String codigo = "TENS-" + mundo.sufijo();
		long tens = fixtures.crearPractica(mundo.organizationId(), mundo.especialidadId(), codigo,
				"TENS " + mundo.sufijo());

		long sesionPropia = sesionCerradaConTratamiento(fixtures, mundo, propio, tens, 1);
		long sesionAjena = sesionCerradaConTratamiento(fixtures, mundo, otro, tens, 2);
		// Una sesion abierta no cuenta: lo aplicado se lee al cierre.
		long abierta = fixtures.crearSesion(mundo, propio);
		insertarTratamiento(mundo, abierta, propio, tens);

		doReturn(PermissionDecision.concedida(PermissionDecision.ALCANCE_ACTIVIDAD_PROPIA, false))
				.when(permisos).evaluate(argThat(q -> q != null && q.accountId() == propio.cuentaId()
						&& "hc:read".equals(q.permissionCode())));

		ResultadoDeHerramienta limitado = ejecutar(mundo, propio, codigo);
		assertThat(limitado.filas()).extracting(f -> f.get("sesion_id")).containsExactly(sesionPropia);
		assertThat(limitado.advertencias()).anyMatch(a -> a.startsWith("Sólo tus pacientes"));

		ResultadoDeHerramienta completo = ejecutar(mundo, otro, codigo);
		assertThat(completo.filas()).extracting(f -> f.get("sesion_id"))
				.containsExactlyInAnyOrder(sesionPropia, sesionAjena);
		assertThat(completo.advertencias()).noneMatch(a -> a.startsWith("Sólo tus pacientes"));
		assertThat(completo.filas()).allSatisfy(f -> assertThat(f.get("practica_id")).isEqualTo(tens));
	}

	private ResultadoDeHerramienta ejecutar(Mundo mundo, Miembro quien, String practica) {
		HerramientaDelAgente herramienta = herramientas.stream()
				.filter(h -> h.nombre().equals("practicas_aplicadas")).findFirst().orElseThrow();
		ActorDelAsistente actor = new ActorDelAsistente(quien.cuentaId(), false, mundo.organizationId(),
				mundo.consultorioId(), "PROFESIONAL");
		return herramienta.ejecutar(actor, Map.of("practica", practica),
				new ContextoDeConsulta(LocalDate.now(ZONA), ZONA, "/asistente"));
	}

	private long sesionCerradaConTratamiento(EncounterFixtures fixtures, Mundo mundo, Miembro duenio,
			long practicaId, int numero) {
		long sesionId = fixtures.crearSesion(mundo, duenio);
		insertarTratamiento(mundo, sesionId, duenio, practicaId);
		jdbc.update("""
				UPDATE sesion
				   SET estado = 'CERRADA', numero_sesion = ?, ultimo_numero_version = 1,
				       cerrada_en = CURRENT_TIMESTAMP - INTERVAL '1 day', cerrada_por_cuenta_id = ?
				 WHERE id = ?
				""", numero, duenio.cuentaId(), sesionId);
		return sesionId;
	}

	private void insertarTratamiento(Mundo mundo, long sesionId, Miembro duenio, long practicaId) {
		jdbc.update("""
				INSERT INTO tratamiento_realizado
				       (organization_id, consultorio_id, sesion_id, orden, practica_id,
				        practica_codigo, practica_nombre, profesional_membership_id, registrado_en,
				        registrado_por_cuenta_id, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, ?, 'TENS', 'TENS', ?, CURRENT_TIMESTAMP, ?, true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", mundo.organizationId(), mundo.consultorioId(), sesionId, practicaId,
				duenio.membershipId(), duenio.cuentaId());
	}
}
