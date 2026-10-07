package com.akine.scheduling.application;

import com.akine.offering.spi.PrecioDeOferta;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe.PrepagoDeTurno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** El estado del prepago de una recepcion (AKINE E-6): una alerta calculada al leer. */
@DisplayName("Prepago de la recepcion (E-6)")
class PrepagoDeRecepcionTest {

	private static final Optional<PrecioDeOferta> EXIGE =
			Optional.of(new PrecioDeOferta(42L, new BigDecimal("8500.00"), "ARS", false, true));
	private static final Optional<PrecioDeOferta> NO_EXIGE =
			Optional.of(new PrecioDeOferta(42L, new BigDecimal("8500.00"), "ARS", false, false));

	@Test
	@DisplayName("la oferta exige y no hay anticipo: PENDIENTE, con el precio particular sugerido")
	void pendiente() {
		PrepagoView prepago = PrepagoDeRecepcion.calcular(EXIGE, recepcion(), null);

		assertThat(prepago.estado()).isEqualTo(PrepagoView.PENDIENTE);
		assertThat(prepago.pendiente()).isTrue();
		assertThat(prepago.importeSugerido()).isEqualByComparingTo("8500.00");
		assertThat(prepago.moneda()).isEqualTo("ARS");
	}

	@Test
	@DisplayName("una oferta sin precio exige igual, sin importe sugerido")
	void pendiente_sin_precio() {
		PrepagoView prepago = PrepagoDeRecepcion.calcular(
				Optional.of(new PrecioDeOferta(42L, null, null, false, true)), recepcion(), null);

		assertThat(prepago.estado()).isEqualTo(PrepagoView.PENDIENTE);
		assertThat(prepago.importeSugerido()).isNull();
	}

	@Test
	@DisplayName("con anticipo vigente: REGISTRADO con cobro, importe y saldo a favor, aunque la oferta no lo exija")
	void registrado() {
		PrepagoDeTurno anticipo = new PrepagoDeTurno(301L, 88L, new BigDecimal("8500.00"),
				new BigDecimal("1500.00"), "ARS");

		for (Optional<PrecioDeOferta> precio : java.util.List.of(EXIGE, NO_EXIGE)) {
			PrepagoView prepago = PrepagoDeRecepcion.calcular(precio, recepcion(), anticipo);
			assertThat(prepago.estado()).isEqualTo(PrepagoView.REGISTRADO);
			assertThat(prepago.cobroId()).isEqualTo(88L);
			assertThat(prepago.importe()).isEqualByComparingTo("8500.00");
			assertThat(prepago.saldoAFavor()).isEqualByComparingTo("1500.00");
		}
	}

	@Test
	@DisplayName("NO_EXIGIDO: la oferta no lo exige, no resuelve, la atencion es con cobertura o la recepcion se anulo")
	void no_exigido() {
		assertThat(PrepagoDeRecepcion.calcular(NO_EXIGE, recepcion(), null).estado())
				.isEqualTo(PrepagoView.NO_EXIGIDO);
		assertThat(PrepagoDeRecepcion.calcular(Optional.empty(), recepcion(), null).estado())
				.isEqualTo(PrepagoView.NO_EXIGIDO);

		Recepcion conCobertura = recepcion();
		conCobertura.validarConCobertura(33L, 412L, 9L, 9L, Instant.now());
		assertThat(PrepagoDeRecepcion.calcular(EXIGE, conCobertura, null).estado())
				.as("con cobertura paga el financiador; el coseguro no se cotiza en la recepcion")
				.isEqualTo(PrepagoView.NO_EXIGIDO);

		Recepcion particular = recepcion();
		particular.atenderComoParticular("Sin orden", 9L, Instant.now());
		assertThat(PrepagoDeRecepcion.calcular(EXIGE, particular, null).estado())
				.isEqualTo(PrepagoView.PENDIENTE);

		Recepcion anulada = recepcion();
		anulada.anular("check-in por error", 9L, Instant.now());
		assertThat(PrepagoDeRecepcion.calcular(EXIGE, anulada, null).estado())
				.isEqualTo(PrepagoView.NO_EXIGIDO);
	}

	private static Recepcion recepcion() {
		Instant inicio = Instant.now().plus(Duration.ofDays(1));
		Turno turno = new Turno(1L, 7L, 42L, 128L, 31L, null,
				inicio, inicio.plus(Duration.ofMinutes(45)), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", 301L);
		return Recepcion.llegada(turno, Instant.now(), 9L);
	}
}
