package com.akine.billing.domain;

import com.akine.billing.domain.exception.CobroAnuladoException;
import com.akine.billing.domain.exception.ImputacionesNoSumanException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.SaldoAFavorInsuficienteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las invariantes del cobro con anticipo (F-3): lo que la base no puede expresar porque MySQL no
 * admite subconsultas en un {@code CHECK}.
 */
@DisplayName("Cobro con anticipo (F-3)")
class CobroAnticipoTest {

	private static final long ORG = 7L;
	private static final Instant COBRADO_EN = Instant.parse("2027-04-08T13:00:00Z");
	private static final long CUENTA = 31L;

	@Test
	@DisplayName("imputaciones mas anticipo tienen que dar el total, comparando valor y no escala")
	void la_suma_incluye_el_anticipo() {
		Cobro cobro = cobro("10000.00", List.of(imputacion(1L, "6000")), "4000.00");

		assertThat(cobro.getSaldoAFavor()).isEqualByComparingTo("4000");
		assertThatThrownBy(() -> cobro("10000.00", List.of(imputacion(1L, "6000")), "3999.99"))
				.isInstanceOf(ImputacionesNoSumanException.class)
				.satisfies(error -> assertThat(((ImputacionesNoSumanException) error).getRecibido())
						.isEqualByComparingTo("9999.99"));
	}

	@Test
	@DisplayName("un anticipo negativo no existe")
	void anticipo_negativo() {
		assertThatThrownBy(() -> cobro("100.00", List.of(), "-1.00"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("las imputaciones que nacen con el cobro toman su instante y su actor")
	void las_imputaciones_originales_se_sellan() {
		Cobro cobro = cobro("100.00", List.of(imputacion(1L, "100.00")), "0");

		assertThat(cobro.getImputaciones()).singleElement().satisfies(imputacion -> {
			assertThat(imputacion.getImputadaEn()).isEqualTo(COBRADO_EN);
			assertThat(imputacion.getImputadaPorCuentaId()).isEqualTo(CUENTA);
		});
	}

	@Test
	@DisplayName("imputar descuenta el anticipo; mas de lo que queda, o dos veces a la misma deuda, no")
	void imputar() {
		Cobro cobro = cobro("5000.00", List.of(), "5000.00");
		Instant despues = COBRADO_EN.plusSeconds(3600);

		cobro.imputar(CobroImputacion.posterior(ORG, 1L, new BigDecimal("3000.00"), despues, 40L, "k", "h"));

		assertThat(cobro.getSaldoAFavor()).isEqualByComparingTo("2000.00");
		assertThat(cobro.getImputaciones()).singleElement().satisfies(imputacion -> {
			assertThat(imputacion.getImputadaEn()).as("la posterior conserva su instante").isEqualTo(despues);
			assertThat(imputacion.getImputadaPorCuentaId()).isEqualTo(40L);
		});
		assertThat(cobro.imputacionConClave("k")).isPresent();
		assertThatThrownBy(() -> cobro.imputar(
				CobroImputacion.posterior(ORG, 2L, new BigDecimal("2000.01"), despues, 40L, null, null)))
				.isInstanceOf(SaldoAFavorInsuficienteException.class);
		assertThatThrownBy(() -> cobro.imputar(
				CobroImputacion.posterior(ORG, 1L, new BigDecimal("1.00"), despues, 40L, null, null)))
				.isInstanceOf(ObligacionNoCobrableException.class);
		assertThat(cobro.getSaldoAFavor()).as("los rechazos no tocan el saldo").isEqualByComparingTo("2000.00");
	}

	@Test
	@DisplayName("reintegrar descuenta el anticipo y no puede pasarse")
	void reintegrar() {
		Cobro cobro = cobro("5000.00", List.of(), "5000.00");

		cobro.reintegrar(new BigDecimal("5000.00"));

		assertThat(cobro.getSaldoAFavor()).isEqualByComparingTo("0");
		assertThatThrownBy(() -> cobro.reintegrar(new BigDecimal("0.01")))
				.isInstanceOf(SaldoAFavorInsuficienteException.class);
	}

	@Test
	@DisplayName("anular marca con motivo, deja el saldo en cero y cierra todo lo demas")
	void anular() {
		Cobro cobro = cobro("5000.00", List.of(), "5000.00");
		Instant cuando = COBRADO_EN.plusSeconds(60);

		cobro.anular("  Error de carga  ", cuando, CUENTA);

		assertThat(cobro.estaAnulado()).isTrue();
		assertThat(cobro.getAnuladoEn()).isEqualTo(cuando);
		assertThat(cobro.getMotivoAnulacion()).isEqualTo("Error de carga");
		assertThat(cobro.getSaldoAFavor()).isEqualByComparingTo("0");
		assertThatThrownBy(() -> cobro.anular("otra", cuando, CUENTA)).isInstanceOf(CobroAnuladoException.class);
		assertThatThrownBy(() -> cobro.reintegrar(BigDecimal.ONE)).isInstanceOf(CobroAnuladoException.class);
		assertThatThrownBy(() -> cobro.imputar(
				CobroImputacion.posterior(ORG, 1L, BigDecimal.ONE, cuando, CUENTA, null, null)))
				.isInstanceOf(CobroAnuladoException.class);
	}

	@Test
	@DisplayName("anular exige motivo")
	void anular_sin_motivo() {
		Cobro cobro = cobro("5000.00", List.of(), "5000.00");

		assertThatThrownBy(() -> cobro.anular(" ", COBRADO_EN, CUENTA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(cobro.estaAnulado()).isFalse();
	}

	private static Cobro cobro(String total, List<CobroImputacion> imputaciones, String anticipo) {
		BigDecimal importe = new BigDecimal(total);
		return new Cobro(ORG, 20L, 4100L, importe, "ARS", 1, COBRADO_EN, CUENTA, null, null,
				List.of(new CobroMedio(ORG, MedioDePago.EFECTIVO, importe, null)),
				imputaciones, new BigDecimal(anticipo));
	}

	private static CobroImputacion imputacion(long obligacionId, String importe) {
		return new CobroImputacion(ORG, obligacionId, new BigDecimal(importe));
	}
}
