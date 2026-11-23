package com.akine.identity.application;

import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.PruebasGratis.PagoQueVence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/** Que aviso le toca a cada momento de un vencimiento (19/11/2026, AKINE-cobro-mensual.md). */
class AvisosDeCobroTest {

	private static final Instant V = Instant.parse("2026-12-19T03:00:00Z");
	private static final PagoQueVence PAGO = new PagoQueVence(7L, "Centro Sintetico", V,
			V.plus(Duration.ofDays(3)), V.plus(Duration.ofDays(18)));

	@Test
	@DisplayName("los hitos son 15, 10, 5, 2 y 1 dias, y solo cuenta el ultimo alcanzado")
	void hitos() {
		assertThat(HitosDeAviso.alcanzado(V, V.minus(Duration.ofDays(16)))).isEmpty();
		assertThat(HitosDeAviso.alcanzado(V, V.minus(Duration.ofDays(15)))).isEqualTo(OptionalInt.of(15));
		assertThat(HitosDeAviso.alcanzado(V, V.minus(Duration.ofDays(11)))).isEqualTo(OptionalInt.of(15));
		assertThat(HitosDeAviso.alcanzado(V, V.minus(Duration.ofDays(4))))
				.as("un worker caido del dia 14 al 4 manda solo el de 5").isEqualTo(OptionalInt.of(5));
		assertThat(HitosDeAviso.alcanzado(V, V.minus(Duration.ofHours(30)))).isEqualTo(OptionalInt.of(2));
		assertThat(HitosDeAviso.alcanzado(V, V.minus(Duration.ofHours(1)))).isEqualTo(OptionalInt.of(1));
		assertThat(HitosDeAviso.alcanzado(V, V)).as("vencido ya no es un hito previo").isEmpty();

		assertThat(HitosDeAviso.diasRestantes(V, V.minus(Duration.ofDays(4)).minus(Duration.ofHours(3)))).isEqualTo(5);
		assertThat(HitosDeAviso.diasRestantes(V, V.minus(Duration.ofDays(5)))).isEqualTo(5);
		assertThat(HitosDeAviso.diasRestantes(V, V.plus(Duration.ofHours(1)))).isZero();
	}

	@Test
	@DisplayName("antes de vencer avisa por vencer con los dias, despues vencida, suspendida, cerrada y nada mas")
	void avisos_por_etapa() {
		assertThat(AvisosDeCobro.avisoDe(PAGO, V.minus(Duration.ofDays(20)))).isEmpty();

		AvisosDeCobro.Aviso porVencer = AvisosDeCobro.avisoDe(PAGO, V.minus(Duration.ofDays(9))).orElseThrow();
		assertThat(porVencer.tipo()).isEqualTo(NotificationType.SUSCRIPCION_POR_VENCER);
		assertThat(porVencer.diasRestantes()).isEqualTo(9);
		assertThat(porVencer.clave()).isEqualTo("suscripcion-por-vencer-10-7-" + V.getEpochSecond());

		assertThat(AvisosDeCobro.avisoDe(PAGO, V.plus(Duration.ofDays(1))).orElseThrow().tipo())
				.isEqualTo(NotificationType.SUSCRIPCION_VENCIDA);
		assertThat(AvisosDeCobro.avisoDe(PAGO, V.plus(Duration.ofDays(3))).orElseThrow().tipo())
				.isEqualTo(NotificationType.SUSCRIPCION_SUSPENDIDA);
		AvisosDeCobro.Aviso cerrada = AvisosDeCobro.avisoDe(PAGO, V.plus(Duration.ofDays(20))).orElseThrow();
		assertThat(cerrada.tipo()).isEqualTo(NotificationType.SUSCRIPCION_CERRADA);
		assertThat(cerrada.diasRestantes()).isNull();
		assertThat(AvisosDeCobro.avisoDe(PAGO, V.plus(Duration.ofDays(26))))
				.as("un cierre viejo ya no se avisa").isEmpty();
	}
}
