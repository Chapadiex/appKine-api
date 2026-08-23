package com.akine.organization.domain;

import com.akine.organization.spi.LimitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica la evaluacion de limites de plan (RF-M01-004).
 *
 * <p>{@link PlanLimit#allows(long)} es el control que decide si un alta entra. Sus bordes son
 * la diferencia entre dejar crear un recurso de mas y rechazar el ultimo que si entraba: con
 * un limite de 3 y 3 recursos ya creados, el cuarto tiene que rechazarse; con 2 creados, el
 * tercero tiene que pasar.
 */
class PlanLimitTest {

	private static final Long PLAN = 2L;

	@ParameterizedTest(name = "limite {0}, uso {1} -> entra={2}")
	@CsvSource({
			"3, 0, true",
			"3, 2, true",
			"3, 3, false",
			"3, 4, false",
			"1, 0, true",
			"1, 1, false",
			"0, 0, false"})
	@DisplayName("El limite se evalua contra el uso actual, borde incluido")
	void el_limite_se_evalua_contra_el_uso(int limite, long uso, boolean entra) {
		PlanLimit planLimit = new PlanLimit(PLAN, LimitCode.MAX_CONSULTORIOS, limite);

		// El off-by-one aca es caro en las dos direcciones: de un lado se vende un plan de 3
		// que en realidad permite 4, del otro se rechaza el tercero de un plan de 3.
		assertThat(planLimit.allows(uso)).isEqualTo(entra);
	}

	@Test
	@DisplayName("Un limite sin valor es ilimitado y acepta cualquier uso")
	void sin_valor_es_ilimitado() {
		PlanLimit ilimitado = new PlanLimit(PLAN, LimitCode.MAX_MIEMBROS_ACTIVOS, null);

		// null = ilimitado NO es lo mismo que no tener fila: la fila declara que el limite se
		// evaluo y se decidio sin tope. Si allows tratara el null como cero, el plan
		// ilimitado no dejaria crear nada.
		assertThat(ilimitado.isUnlimited()).isTrue();
		assertThat(ilimitado.allows(0)).isTrue();
		assertThat(ilimitado.allows(Long.MAX_VALUE)).isTrue();
	}

	@Test
	@DisplayName("Un limite con valor no es ilimitado")
	void con_valor_no_es_ilimitado() {
		assertThat(new PlanLimit(PLAN, LimitCode.MAX_CONSULTORIOS, 0).isUnlimited()).isFalse();
	}

	@Test
	@DisplayName("Un limite nuevo nace activo con su codigo y su valor")
	void nace_activo() {
		PlanLimit planLimit = new PlanLimit(PLAN, LimitCode.MAX_CONSULTORIOS, 5);

		assertThat(planLimit.isActive()).isTrue();
		assertThat(planLimit.getDeletedAt()).isNull();
		assertThat(planLimit.getPlanId()).isEqualTo(PLAN);
		assertThat(planLimit.getLimitCode()).isEqualTo(LimitCode.MAX_CONSULTORIOS);
		assertThat(planLimit.getLimitValue()).isEqualTo(5);
		assertThat(planLimit.getId()).isNull();
	}

	@Test
	@DisplayName("Cambiar el valor puede volver el limite ilimitado y al reves")
	void cambiar_el_valor_alterna_ilimitado() {
		PlanLimit planLimit = new PlanLimit(PLAN, LimitCode.MAX_CONSULTORIOS, 5);

		planLimit.changeValue(null);
		assertThat(planLimit.isUnlimited()).isTrue();

		planLimit.changeValue(2);
		assertThat(planLimit.isUnlimited()).isFalse();
		assertThat(planLimit.allows(2)).isFalse();
	}

	@Test
	@DisplayName("Bajar el limite no invalida lo ya creado, solo frena el alta siguiente")
	void el_downgrade_es_prospectivo() {
		PlanLimit planLimit = new PlanLimit(PLAN, LimitCode.MAX_CONSULTORIOS, 5);

		planLimit.changeValue(1);

		// RN-M01-004: un downgrade jamas toca datos existentes. El unico efecto es que la
		// proxima alta se rechaza; las 3 sedes que ya existen siguen operativas.
		assertThat(planLimit.allows(3)).isFalse();
		assertThat(planLimit.getLimitValue()).isEqualTo(1);
	}

	@Test
	@DisplayName("Rehabilitar un limite reusa la fila en vez de insertar otra")
	void reactivar_reusa_la_fila() {
		PlanLimit planLimit = new PlanLimit(PLAN, LimitCode.MAX_CONSULTORIOS, 5);
		Instant momento = Instant.parse("2026-04-05T08:00:00Z");

		planLimit.deactivate(momento);
		assertThat(planLimit.isActive()).isFalse();
		assertThat(planLimit.getDeletedAt()).isEqualTo(momento);

		planLimit.reactivate();

		// El unique (plan_id, limit_code) no discrimina por active: insertar una fila nueva
		// para rehabilitar el limite chocaria contra la restriccion. Si reactivate dejara
		// deleted_at con valor, la fila quedaria activa y borrada a la vez.
		assertThat(planLimit.isActive()).isTrue();
		assertThat(planLimit.getDeletedAt()).isNull();
	}

	@Test
	@DisplayName("El limite hidratado por JPA arranca activo")
	void el_hidratado_por_jpa_arranca_activo() {
		assertThat(new PlanLimit().isActive()).isTrue();
	}

	@Test
	@DisplayName("Los codigos de limite son exactamente los dos evaluables en F1")
	void los_codigos_de_limite_son_los_de_f1() {
		// El catalogo crece por etapa consumidora: un codigo que ninguna etapa cuenta es un
		// limite que se configura y nunca se aplica.
		assertThat(LimitCode.values()).containsExactly(
				LimitCode.MAX_CONSULTORIOS, LimitCode.MAX_MIEMBROS_ACTIVOS);
	}

	@ParameterizedTest
	@EnumSource(LimitCode.class)
	@DisplayName("Los codigos de limite se persisten literales y entran en la columna")
	void los_codigos_se_persisten_literales(LimitCode codigo) {
		// limit_code es VARCHAR(48) con EnumType.STRING: el nombre viaja como texto, asi que
		// renombrar un valor deja las filas viejas ilegibles.
		assertThat(LimitCode.valueOf(codigo.name())).isEqualTo(codigo);
		assertThat(codigo.name().length()).isLessThanOrEqualTo(48);
	}
}
