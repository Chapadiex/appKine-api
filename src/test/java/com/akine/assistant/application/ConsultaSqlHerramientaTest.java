package com.akine.assistant.application;

import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.domain.port.LectorDeVistas;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsultaSqlHerramientaTest {

	private static final ActorDelAsistente PROFESIONAL = new ActorDelAsistente(11L, false, 7L, 3L, "PROFESIONAL");
	private static final ActorDelAsistente ADMIN = new ActorDelAsistente(12L, false, 7L, 3L, "ORG_ADMIN");

	private final LectorDeVistas lector = (sql, org, sede, zona, limite) -> List.of(Map.of("pacientes", 4));
	private final AuditoriaDelAsistente auditoria = new AuditoriaDelAsistente(e -> {
	});
	private final AsistenteAjustes ajustes = new AsistenteAjustes(true, Duration.ofSeconds(5), true);

	/** Concede lo que pide cada actor: el profesional nada; el admin asistente:sql, y hc:read con el alcance dado. */
	private PermissionEvaluator permisos(String alcanceHc) {
		PermissionEvaluator permisos = mock(PermissionEvaluator.class);
		when(permisos.evaluate(any())).thenAnswer(inv -> {
			var q = (com.akine.organization.spi.PermissionQuery) inv.getArgument(0);
			if (q.accountId() == PROFESIONAL.accountId()) {
				return PermissionDecision.rechazada(DenialKind.NO_PERMISSION);
			}
			return PermissionCodes.ASISTENTE_SQL.equals(q.permissionCode()) ? PermissionDecision.concedida("ORGANIZACION", false)
					: PermissionDecision.concedida(alcanceHc, false);
		});
		return permisos;
	}

	private CatalogoDeHerramientas catalogo(String alcanceHc) {
		PermissionEvaluator permisos = permisos(alcanceHc);
		return new CatalogoDeHerramientas(List.of(
				new ConsultaSqlHerramienta(false, "comun", lector, auditoria, permisos, ajustes),
				new ConsultaSqlHerramienta(true, "clinica", lector, auditoria, permisos, ajustes)));
	}

	@Test
	void un_profesional_no_recibe_consulta_sql() {
		assertThat(catalogo("ORGANIZACION").disponiblesPara(PROFESIONAL)).isEmpty();
	}

	@Test
	void el_administrador_recibe_la_clinica_solo_sin_recorte() {
		assertThat(catalogo("ORGANIZACION").disponiblesPara(ADMIN)).extracting(d -> d.nombre())
				.containsExactly("consulta_sql", "consulta_sql_clinica");
		assertThat(catalogo("RESTRINGIDO").disponiblesPara(ADMIN)).extracting(d -> d.nombre())
				.containsExactly("consulta_sql");
	}

	@Test
	void la_comun_rechaza_las_vistas_clinicas_y_devuelve_el_motivo_al_modelo() {
		var comun = new ConsultaSqlHerramienta(false, "comun", lector, auditoria, permisos("ORGANIZACION"), ajustes);

		assertThatThrownBy(() -> comun.ejecutar(ADMIN, Map.of("sql", "SELECT * FROM clinico_casos"), null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clinico_casos");
		ResultadoDeHerramienta ok = comun.ejecutar(ADMIN, Map.of("sql", "SELECT count(*) AS pacientes FROM personas"), null);
		assertThat(ok.filas()).hasSize(1);
	}

	@Test
	void el_error_del_motor_vuelve_como_argumento_invalido() {
		LectorDeVistas falla = (sql, org, sede, zona, limite) -> {
			throw new LectorDeVistas.ConsultaInvalidaException("column \"x\" does not exist", null);
		};
		var comun = new ConsultaSqlHerramienta(false, "comun", falla, auditoria, permisos("ORGANIZACION"), ajustes);

		assertThatThrownBy(() -> comun.ejecutar(ADMIN, Map.of("sql", "SELECT x FROM personas"), null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
	}
}
