package com.akine.organization.domain;

import com.akine.organization.domain.exception.CobroNoAdmitidoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Linea de tiempo del cobro mensual y su efecto sobre el estado (19/11/2026, AKINE-cobro-mensual.md). */
class SituacionDeCobroTest {

	private static final Instant V = Instant.parse("2026-12-19T03:00:00Z");
	private static final Duration UN_NANO = Duration.ofNanos(1);

	@Test
	@DisplayName("cada borde de la linea de tiempo cae del lado que dice el diseño")
	void bordes() {
		assertThat(SituacionDeCobro.de(null, V)).isEqualTo(SituacionDeCobro.SIN_CONTROL);
		assertThat(SituacionDeCobro.de(V, V.minus(Duration.ofDays(15)).minus(UN_NANO))).isEqualTo(SituacionDeCobro.AL_DIA);
		assertThat(SituacionDeCobro.de(V, V.minus(Duration.ofDays(15)))).isEqualTo(SituacionDeCobro.POR_VENCER);
		assertThat(SituacionDeCobro.de(V, V.minus(UN_NANO))).isEqualTo(SituacionDeCobro.POR_VENCER);
		assertThat(SituacionDeCobro.de(V, V)).isEqualTo(SituacionDeCobro.EN_GRACIA);
		assertThat(SituacionDeCobro.de(V, V.plus(Duration.ofDays(3)).minus(UN_NANO))).isEqualTo(SituacionDeCobro.EN_GRACIA);
		assertThat(SituacionDeCobro.de(V, V.plus(Duration.ofDays(3)))).isEqualTo(SituacionDeCobro.SOLO_LECTURA);
		assertThat(SituacionDeCobro.de(V, V.plus(Duration.ofDays(18)).minus(UN_NANO))).isEqualTo(SituacionDeCobro.SOLO_LECTURA);
		assertThat(SituacionDeCobro.de(V, V.plus(Duration.ofDays(18)))).isEqualTo(SituacionDeCobro.SIN_ACCESO);

		assertThat(SituacionDeCobro.cierreEl(V)).isEqualTo(V.plus(Duration.ofDays(18)));
		assertThat(SituacionDeCobro.soloLecturaDesde(V)).isEqualTo(V.plus(Duration.ofDays(3)));
		assertThat(SituacionDeCobro.cierreEl(null)).isNull();
		assertThat(SituacionDeCobro.soloLecturaDesde(null)).isNull();
	}

	@Test
	@DisplayName("una ACTIVA pagada opera en la gracia, lee despues y queda sin acceso al cierre; sin control no cambia nada")
	void estado_operativo() {
		SubscriptionStatus activa = SubscriptionStatus.ACTIVA;
		assertThat(Subscription.estadoOperativo(activa, null, null, V.plus(Duration.ofDays(400)), true))
				.as("sin pagado_hasta no hay control").isEqualTo(OperationalStatus.ACTIVA);
		assertThat(Subscription.estadoOperativo(activa, null, V, V.plus(Duration.ofDays(2)), true))
				.isEqualTo(OperationalStatus.ACTIVA);
		assertThat(Subscription.estadoOperativo(activa, null, V, V.plus(Duration.ofDays(3)), true))
				.isEqualTo(OperationalStatus.SUSPENDIDA);
		assertThat(Subscription.estadoOperativo(activa, null, V, V.plus(Duration.ofDays(18)), true))
				.isEqualTo(OperationalStatus.SIN_ACCESO);
		assertThat(Subscription.estadoOperativo(activa, null, V, V.plus(Duration.ofDays(18)), false))
				.as("la baja de la organizacion gana").isEqualTo(OperationalStatus.BAJA);
		assertThat(Subscription.estadoOperativo(SubscriptionStatus.CANCELADA, null, V, V.plus(Duration.ofDays(30)), true))
				.as("la cancelada guardada manda").isEqualTo(OperationalStatus.CANCELADA);
		assertThat(Subscription.estadoOperativo(SubscriptionStatus.SUSPENDIDA, null, V, V.minus(Duration.ofDays(30)), true))
				.as("la suspendida guardada manda aunque este pagada").isEqualTo(OperationalStatus.SUSPENDIDA);
	}

	@Test
	@DisplayName("el cobro de una prueba la pasa a ACTIVA, y una suspendida no admite cobro")
	void registrar_cobro() {
		Instant ahora = Instant.parse("2026-11-19T15:00:00Z");
		Subscription prueba = Subscription.enPrueba(7L, 1L, ahora.minus(Duration.ofDays(10)), ahora.plus(Duration.ofDays(20)));
		prueba.registrarCobro(V);
		assertThat(prueba.getStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(prueba.getPruebaHasta()).isNull();
		assertThat(prueba.getPagadoHasta()).isEqualTo(V);
		assertThatThrownBy(() -> prueba.registrarCobro(V))
				.as("un cobro tiene que extender el periodo").isInstanceOf(IllegalArgumentException.class);

		Subscription suspendida = new Subscription(8L, 1L, ahora);
		suspendida.transitionTo(SubscriptionStatus.SUSPENDIDA);
		assertThatThrownBy(() -> suspendida.registrarCobro(V)).isInstanceOf(CobroNoAdmitidoException.class);
	}

	@Test
	@DisplayName("el periodo arranca en el vencimiento si no llego o ahora si paso, y suma meses en la zona del centro")
	void periodo() {
		ZoneId cordoba = ZoneId.of("America/Argentina/Cordoba");
		Instant ahora = Instant.parse("2026-11-19T15:00:00Z");

		SubscriptionCobro.Periodo primero = SubscriptionCobro.periodo(null, ahora, 1, cordoba);
		assertThat(primero.desde()).isEqualTo(ahora);
		assertThat(primero.hasta()).isEqualTo(Instant.parse("2026-12-19T15:00:00Z"));

		SubscriptionCobro.Periodo adelantado = SubscriptionCobro.periodo(V, ahora, 2, cordoba);
		assertThat(adelantado.desde()).as("pagar antes conserva el ciclo").isEqualTo(V);
		assertThat(adelantado.hasta()).isEqualTo(Instant.parse("2027-02-19T03:00:00Z"));

		SubscriptionCobro.Periodo tarde = SubscriptionCobro.periodo(ahora.minus(Duration.ofDays(5)), ahora, 1, cordoba);
		assertThat(tarde.desde()).as("pagar tarde reinicia desde hoy").isEqualTo(ahora);

		// 31/01 a las 22 de Cordoba es 01/02 en UTC: en UTC el mes sumado caeria en marzo.
		Instant finDeEnero = Instant.parse("2027-02-01T01:00:00Z");
		assertThat(SubscriptionCobro.periodo(null, finDeEnero, 1, cordoba).hasta())
				.as("31/01 + 1 mes es el ultimo dia de febrero en la zona del centro")
				.isEqualTo(Instant.parse("2027-03-01T01:00:00Z"));

		assertThatThrownBy(() -> SubscriptionCobro.periodo(null, ahora, 0, cordoba))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SubscriptionCobro.periodo(null, ahora, 13, cordoba))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
