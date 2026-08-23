package com.akine.organization.domain;

import com.akine.organization.spi.FeatureCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica la habilitacion de funcionalidades por plan (RF-M01-004).
 *
 * <p>La habilitacion se representa por PRESENCIA de una fila activa, no por un booleano: un
 * plan viejo no puede heredar por omision una feature que nadie decidio darle.
 */
class PlanFeatureTest {

	private static final Long PLAN = 6L;

	@Test
	@DisplayName("Una feature nueva nace activa: la fila ES la habilitacion")
	void nace_activa() {
		PlanFeature feature = new PlanFeature(PLAN, FeatureCode.REPORTES_AVANZADOS);

		// Si naciera inactiva, insertar la fila no habilitaria nada y el plan se venderia con
		// una funcion que el sistema no deja usar.
		assertThat(feature.isActive()).isTrue();
		assertThat(feature.getDeletedAt()).isNull();
		assertThat(feature.getPlanId()).isEqualTo(PLAN);
		assertThat(feature.getFeatureCode()).isEqualTo(FeatureCode.REPORTES_AVANZADOS);
		assertThat(feature.getId()).isNull();
	}

	@Test
	@DisplayName("Quitar una feature es baja logica, y devolverla reusa la fila")
	void quitar_y_devolver_reusan_la_fila() {
		PlanFeature feature = new PlanFeature(PLAN, FeatureCode.NOTIFICACIONES_PACIENTE);
		Instant momento = Instant.parse("2026-03-15T18:00:00Z");

		feature.deactivate(momento);
		assertThat(feature.isActive()).isFalse();
		assertThat(feature.getDeletedAt()).isEqualTo(momento);

		feature.reactivate();

		// Como en PlanLimit, el unique (plan_id, feature_code) no mira active: rehabilitar
		// insertando otra fila choca contra la restriccion. Y una fila activa con deleted_at
		// cargado seria un estado contradictorio que ninguna consulta sabria interpretar.
		assertThat(feature.isActive()).isTrue();
		assertThat(feature.getDeletedAt()).isNull();
	}

	@Test
	@DisplayName("La feature hidratada por JPA arranca activa")
	void la_hidratada_por_jpa_arranca_activa() {
		assertThat(new PlanFeature().isActive()).isTrue();
	}

	@Test
	@DisplayName("Los codigos de feature son exactamente los del catalogo de F1")
	void los_codigos_son_los_de_f1() {
		assertThat(FeatureCode.values()).containsExactly(
				FeatureCode.REPORTES_AVANZADOS, FeatureCode.NOTIFICACIONES_PACIENTE);
	}

	@ParameterizedTest
	@EnumSource(FeatureCode.class)
	@DisplayName("Los codigos de feature se persisten literales y entran en la columna")
	void los_codigos_se_persisten_literales(FeatureCode codigo) {
		// feature_code es VARCHAR(48) con EnumType.STRING.
		assertThat(FeatureCode.valueOf(codigo.name())).isEqualTo(codigo);
		assertThat(codigo.name().length()).isLessThanOrEqualTo(48);
	}
}
