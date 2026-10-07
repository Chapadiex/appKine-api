package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe.PrepagoDeTurno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

	@Test
	@DisplayName("antes del check-in (E-8): PENDIENTE si la oferta lo exige; cancelado NO_EXIGIDO; anticipo REGISTRADO aunque este cancelado")
	void antes_de_la_llegada() {
		assertThat(PrepagoDeRecepcion.antesDeLaLlegada(EXIGE, turno(), null).estado())
				.isEqualTo(PrepagoView.PENDIENTE);
		assertThat(PrepagoDeRecepcion.antesDeLaLlegada(EXIGE, turno(), null).importeSugerido())
				.isEqualByComparingTo("8500.00");
		assertThat(PrepagoDeRecepcion.antesDeLaLlegada(NO_EXIGE, turno(), null).estado())
				.isEqualTo(PrepagoView.NO_EXIGIDO);

		Turno cancelado = turno();
		cancelado.cancelar("Viaje", 9L, Instant.now());
		assertThat(PrepagoDeRecepcion.antesDeLaLlegada(EXIGE, cancelado, null).estado())
				.isEqualTo(PrepagoView.NO_EXIGIDO);
		assertThat(PrepagoDeRecepcion.antesDeLaLlegada(EXIGE, cancelado, new PrepagoDeTurno(
				301L, 88L, new BigDecimal("8500.00"), new BigDecimal("8500.00"), "ARS")).estado())
				.as("el anticipo vigente se ve: hay plata para reintegrar")
				.isEqualTo(PrepagoView.REGISTRADO);
	}

	@Test
	@DisplayName("lote de la agenda (E-8): un turno con y otro sin recepcion, una sola pregunta a billing y una por oferta")
	void lote_con_y_sin_recepcion() {
		OfertaDirectory ofertas = mock(OfertaDirectory.class);
		PrepagoDeTurnoProbe prepagos = mock(PrepagoDeTurnoProbe.class);
		given(ofertas.precioDe(1L, 7L, 42L)).willReturn(EXIGE);
		given(prepagos.prepagosDe(eq(1L), any())).willReturn(Map.of());

		Turno sinLlegada = turno();
		Turno conLlegada = turno();
		ReflectionTestUtils.setField(conLlegada, "id", 302L);
		Recepcion recepcion = Recepcion.llegada(conLlegada, Instant.now(), 9L);
		recepcion.validarConCobertura(33L, 412L, 9L, 9L, Instant.now());

		Map<Long, PrepagoView> resultado = new PrepagoDeRecepcion(ofertas, prepagos)
				.de(1L, 7L, List.of(sinLlegada, conLlegada), Map.of(302L, recepcion));

		assertThat(resultado.get(301L).estado()).isEqualTo(PrepagoView.PENDIENTE);
		assertThat(resultado.get(302L).estado())
				.as("con recepcion manda la regla de E-6: con cobertura no se exige")
				.isEqualTo(PrepagoView.NO_EXIGIDO);
		verify(prepagos, times(1)).prepagosDe(eq(1L), any());
		verify(ofertas, times(1)).precioDe(1L, 7L, 42L);
	}

	private static Turno turno() {
		Instant inicio = Instant.now().plus(Duration.ofDays(1));
		Turno turno = new Turno(1L, 7L, 42L, 128L, 31L, null,
				inicio, inicio.plus(Duration.ofMinutes(45)), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", 301L);
		return turno;
	}

	private static Recepcion recepcion() {
		Instant inicio = Instant.now().plus(Duration.ofDays(1));
		Turno turno = new Turno(1L, 7L, 42L, 128L, 31L, null,
				inicio, inicio.plus(Duration.ofMinutes(45)), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", 301L);
		return Recepcion.llegada(turno, Instant.now(), 9L);
	}
}
