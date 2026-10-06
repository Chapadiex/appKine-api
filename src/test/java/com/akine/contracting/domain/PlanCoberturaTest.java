package com.akine.contracting.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los invariantes de {@link PlanCobertura}, sin base de datos.
 *
 * <p>Las dos afirmaciones que este test fija y que el resto de M15 hereda: que
 * {@code vigenciaHasta} es <b>inclusiva</b> —V24 dejo esa ambiguedad y aca no se repite— y que
 * <b>ciclo de vida y vigencia son dos cosas distintas</b>, que es lo que hace representable el
 * caso borde "plan sin nuevas altas pero con pacientes vigentes".
 */
@DisplayName("PlanCobertura")
class PlanCoberturaTest {

	private static final LocalDate DESDE = LocalDate.of(2026, 1, 1);
	private static final LocalDate HASTA = LocalDate.of(2026, 12, 31);

	private static PlanCobertura nuevo(LocalDate hasta) {
		return new PlanCobertura(
				7L, 31L, "210", "Plan 210", null, DESDE, hasta,
				false, true, new BigDecimal("1500.00"), "ars");
	}

	@Nested
	@DisplayName("Vigencia")
	class Vigencia {

		@Test
		@DisplayName("vigenciaHasta es INCLUSIVA: el ultimo dia todavia vale")
		void el_ultimo_dia_vale() {
			PlanCobertura plan = nuevo(HASTA);

			assertThat(plan.vigenteEl(HASTA)).isTrue();
			assertThat(plan.vigenteEl(HASTA.plusDays(1))).isFalse();
			assertThat(plan.vigenteEl(DESDE)).isTrue();
			assertThat(plan.vigenteEl(DESDE.minusDays(1))).isFalse();
		}

		@Test
		@DisplayName("Sin vigenciaHasta el plan no vence")
		void sin_fin_previsto() {
			assertThat(nuevo(null).vigenteEl(LocalDate.of(2099, 1, 1))).isTrue();
		}

		@Test
		@DisplayName("Un plan que vale un solo dia es valido; una ventana invertida no")
		void la_ventana_se_valida() {
			assertThat(nuevo(DESDE).vigenteEl(DESDE)).isTrue();

			assertThatThrownBy(() -> nuevo(DESDE.minusDays(1)))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Cerrar la vigencia NO da de baja: el plan sigue operable")
		void cerrar_no_es_dar_de_baja() {
			// Es la distincion que RF-M15-005 pide y que hace representable el caso borde de la
			// etapa: el plan deja de ofrecerse y sigue existiendo.
			PlanCobertura plan = nuevo(null);

			plan.cerrarVigencia(HASTA);

			assertThat(plan.isOperable()).isTrue();
			assertThat(plan.isActive()).isTrue();
			assertThat(plan.vigenteEl(HASTA.plusDays(1))).isFalse();
		}

		@Test
		@DisplayName("La edicion valida la vigencia como PAR aunque llegue de a una")
		void la_edicion_valida_el_par() {
			// Mandar solo vigenciaHasta tiene que compararse contra el vigenciaDesde guardado.
			// Validar cada campo por separado dejaria pasar una ventana invertida que despues
			// rechaza el CHECK con un 500 en vez de un 400.
			PlanCobertura plan = nuevo(HASTA);

			assertThatThrownBy(() -> plan.updateDatos(
					null, null, null, DESDE.minusYears(1).minusDays(1), null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("Seleccionabilidad — RN-M15-002")
	class Seleccionable {

		@Test
		@DisplayName("Exige las TRES condiciones: financiador operable, plan operable y vigente")
		void las_tres_condiciones() {
			PlanCobertura plan = nuevo(HASTA);

			assertThat(plan.seleccionableEl(DESDE, true)).isTrue();

			// Un plan impecable de un financiador dado de baja no se puede elegir. Esta condicion
			// llega por parametro porque el plan NO conoce a su financiador, y no debe: una
			// relacion JPA hacia el abriria la puerta a la cascada que RN-M15-003 prohibe.
			assertThat(plan.seleccionableEl(DESDE, false)).isFalse();

			assertThat(plan.seleccionableEl(HASTA.plusDays(1), true)).isFalse();

			plan.deactivate(Instant.now(), "motivo");
			assertThat(plan.seleccionableEl(DESDE, true)).isFalse();
		}
	}

	@Nested
	@DisplayName("Copago")
	class Copago {

		@Test
		@DisplayName("La moneda se normaliza a mayusculas y viaja con el importe")
		void copago_con_moneda() {
			assertThat(nuevo(HASTA).getMoneda()).isEqualTo("ARS");
			assertThat(nuevo(HASTA).getCopago()).isEqualByComparingTo("1500.00");
		}

		@Test
		@DisplayName("Un importe sin moneda, o una moneda sin importe, se rechaza")
		void no_viajan_solos() {
			assertThatThrownBy(() -> new PlanCobertura(
					7L, 31L, "c", "n", null, DESDE, null, false, true,
					new BigDecimal("10.00"), null))
					.isInstanceOf(IllegalArgumentException.class);

			assertThatThrownBy(() -> new PlanCobertura(
					7L, 31L, "c", "n", null, DESDE, null, false, true, null, "ARS"))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Sin copago declarado es valido, y NO es lo mismo que cero")
		void sin_copago_no_es_cero() {
			PlanCobertura plan = new PlanCobertura(
					7L, 31L, "c", "n", null, DESDE, null, false, true, null, null);

			assertThat(plan.getCopago()).isNull();
			assertThat(plan.getMoneda()).isNull();
		}

		@Test
		@DisplayName("Un copago negativo no es un descuento: es un dato roto")
		void copago_negativo() {
			assertThatThrownBy(() -> new PlanCobertura(
					7L, 31L, "c", "n", null, DESDE, null, false, true,
					new BigDecimal("-1.00"), "ARS"))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Una moneda que no es ISO 4217 de 3 letras se rechaza")
		void moneda_invalida() {
			assertThatThrownBy(() -> new PlanCobertura(
					7L, 31L, "c", "n", null, DESDE, null, false, true,
					new BigDecimal("1.00"), "PESOS"))
					.isInstanceOf(IllegalArgumentException.class);
			// Tres letras no alcanzan: tiene que ser un codigo que exista.
			assertThatThrownBy(() -> new PlanCobertura(
					7L, 31L, "c", "n", null, DESDE, null, false, true,
					new BigDecimal("1.00"), "XYZ"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("ISO 4217");
		}
	}

	@Test
	@DisplayName("RN-M15-001: un plan sin financiador no existe")
	void exige_financiador() {
		assertThatThrownBy(() -> new PlanCobertura(
				7L, null, "c", "n", null, DESDE, null, false, true, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("La edicion NO puede cambiar el codigo ni el financiador")
	void la_identidad_es_inmutable() {
		PlanCobertura plan = nuevo(HASTA);

		plan.updateDatos("Plan 310", "otra", null, null, true, false, null, null);

		assertThat(plan.getCodigo()).isEqualTo("210");
		assertThat(plan.getFinanciadorId()).isEqualTo(31L);
		assertThat(plan.getNombre()).isEqualTo("Plan 310");
		assertThat(plan.isRequiereAutorizacion()).isTrue();
		assertThat(plan.isRequiereCredencial()).isFalse();
	}

	@Test
	@DisplayName("La baja exige motivo y no borra la identidad")
	void la_baja_exige_motivo() {
		PlanCobertura plan = nuevo(HASTA);
		Instant ahora = Instant.now();

		assertThatThrownBy(() -> plan.deactivate(ahora, null))
				.isInstanceOf(IllegalArgumentException.class);

		plan.deactivate(ahora, "ya no se ofrece");

		assertThat(plan.isOperable()).isFalse();
		assertThat(plan.getCodigo()).isEqualTo("210");
		assertThat(plan.getDeactivationReason()).isEqualTo("ya no se ofrece");
	}
}
