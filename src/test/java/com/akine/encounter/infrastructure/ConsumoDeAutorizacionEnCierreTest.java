package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.AutorizacionesDelCaso;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.ConsumoDeAutorizaciones;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La decision que mas facil se implementa mal: <b>este observador NO hace fallar el cierre</b>.
 *
 * <p>{@code billing.ObligacionDevengador} si lo hace cuando no puede devengar, y esta bien —una
 * prestacion sin deuda es plata perdida—. Aca es al reves: la atencion ocurrio, y bloquear el
 * cierre de una historia clinica porque al financiador se le acabo el cupo es exactamente lo que
 * DP-06 prohibe. Estos casos lo dejan ejecutable, para que el proximo que quiera "arreglarlo"
 * rompa un test en vez de romper el producto.
 */
@DisplayName("Consumo de autorizacion al cerrar la sesion (AKINE-04.05)")
class ConsumoDeAutorizacionEnCierreTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 1204L;
	private static final long SESION = 3312L;

	private final ConsumoDeAutorizaciones consumo = mock(ConsumoDeAutorizaciones.class);
	private final ConsultorioDirectory consultorios = mock(ConsultorioDirectory.class);
	private final AutorizacionesDelCaso autorizacionesDelCaso = mock(AutorizacionesDelCaso.class);

	private final ConsumoDeAutorizacionEnCierre observador =
			new ConsumoDeAutorizacionEnCierre(consumo, consultorios, autorizacionesDelCaso);

	@Test
	@DisplayName("un cierre SIN SALDO no lanza: el cierre clinico sigue")
	void sin_saldo_no_rompe_el_cierre() {
		darSede("America/Argentina/Cordoba");
		given(consumo.consumirPorSesion(any())).willReturn(List.of(ResultadoDeConsumo.sinSaldo(77L)));

		assertThatCode(() -> observador.alCerrar(cierre(true))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("sin autorizacion elegible tampoco lanza: es el caso mas frecuente")
	void sin_autorizacion_no_rompe_el_cierre() {
		darSede("America/Argentina/Cordoba");
		given(consumo.consumirPorSesion(any()))
				.willReturn(List.of(ResultadoDeConsumo.sinAutorizacionElegible()));

		assertThatCode(() -> observador.alCerrar(cierre(true))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("sin asistencia no se consume: una ausencia no es una prestacion")
	void sin_asistencia_no_consume() {
		observador.alCerrar(cierre(false));

		verifyNoInteractions(consumo);
		verifyNoInteractions(consultorios);
	}

	@Test
	@DisplayName("atendida como Particular en la recepcion: no se consume autorizacion (AKINE E-7)")
	void particular_por_recepcion_no_consume() {
		observador.alCerrar(new SesionCerrada(
				SESION, ORG, SEDE, PERSONA, 500L, 4, true,
				Instant.parse("2027-03-15T14:00:00Z"), 42L, BigDecimal.TEN, "ARS", Set.of(1L),
				null, true, 600L, true));

		verifyNoInteractions(consumo, consultorios, autorizacionesDelCaso);
	}

	@Test
	@DisplayName("el dia se calcula en la zona de la SEDE, no en UTC")
	void el_dia_es_local() {
		darSede("America/Argentina/Cordoba");
		given(consumo.consumirPorSesion(any()))
				.willReturn(List.of(ResultadoDeConsumo.consumida(77L, 9001L, 5)));

		// 01:30 UTC del dia 16 es todavia el 15 en Cordoba (UTC-3). Resolverlo en UTC correria
		// el dia y una autorizacion que vence el 15 rechazaria esta sesion.
		observador.alCerrar(new SesionCerrada(
				SESION, ORG, SEDE, PERSONA, 500L, 4, true,
				Instant.parse("2027-03-16T01:30:00Z"), 42L, BigDecimal.TEN, "ARS", Set.of()));

		ArgumentCaptor<ConsumoPorSesion> pedido = ArgumentCaptor.forClass(ConsumoPorSesion.class);
		verify(consumo).consumirPorSesion(pedido.capture());
		assertThat(pedido.getValue().fecha()).isEqualTo(LocalDate.of(2027, 3, 15));
		// Y viaja quien cerro, que es para lo que SesionCerrada se agrando.
		assertThat(pedido.getValue().actorCuentaId()).isEqualTo(42L);
		assertThat(pedido.getValue().cantidad()).isEqualTo(1);
	}

	@Test
	@DisplayName("una zona invalida cae a UTC en vez de dejar la unidad sin gastar")
	void zona_invalida_cae_a_utc() {
		darSede("Zona/Inventada");
		given(consumo.consumirPorSesion(any()))
				.willReturn(List.of(ResultadoDeConsumo.consumida(77L, 9001L, 5)));

		observador.alCerrar(new SesionCerrada(
				SESION, ORG, SEDE, PERSONA, 500L, 4, true,
				Instant.parse("2027-03-16T01:30:00Z"), 42L, BigDecimal.TEN, "ARS", Set.of()));

		ArgumentCaptor<ConsumoPorSesion> pedido = ArgumentCaptor.forClass(ConsumoPorSesion.class);
		verify(consumo).consumirPorSesion(pedido.capture());
		assertThat(pedido.getValue().fecha()).isEqualTo(LocalDate.of(2027, 3, 16));
	}

	@Test
	@DisplayName("con caso, excluye las autorizaciones atadas a OTRO caso (AKINE C-4)")
	void con_caso_excluye_las_de_otro_caso() {
		darSede("America/Argentina/Cordoba");
		given(autorizacionesDelCaso.deOtrosCasos(ORG, 900L)).willReturn(Set.of(88L));
		given(consumo.consumirPorSesion(any()))
				.willReturn(List.of(ResultadoDeConsumo.consumida(77L, 9001L, 5),
						ResultadoDeConsumo.consumida(78L, 9002L, 2)));

		observador.alCerrar(new SesionCerrada(
				SESION, ORG, SEDE, PERSONA, 500L, 4, true,
				Instant.parse("2027-03-15T14:00:00Z"), 42L, BigDecimal.TEN, "ARS",
				Set.of(1L, 2L), 900L));

		ArgumentCaptor<ConsumoPorSesion> pedido = ArgumentCaptor.forClass(ConsumoPorSesion.class);
		verify(consumo).consumirPorSesion(pedido.capture());
		assertThat(pedido.getValue().autorizacionesDeOtroCaso()).containsExactly(88L);
		assertThat(pedido.getValue().practicasRealizadas()).containsExactlyInAnyOrder(1L, 2L);
		// DP-12: una unidad por autorizacion involucrada; el agrupamiento lo hace person.
		assertThat(pedido.getValue().cantidad()).isEqualTo(1);
	}

	@Test
	@DisplayName("sin caso no pregunta a clinical ni excluye nada")
	void sin_caso_no_excluye() {
		darSede("America/Argentina/Cordoba");
		given(consumo.consumirPorSesion(any()))
				.willReturn(List.of(ResultadoDeConsumo.sinAutorizacionElegible()));

		observador.alCerrar(cierre(true));

		verifyNoInteractions(autorizacionesDelCaso);
		ArgumentCaptor<ConsumoPorSesion> pedido = ArgumentCaptor.forClass(ConsumoPorSesion.class);
		verify(consumo).consumirPorSesion(pedido.capture());
		assertThat(pedido.getValue().autorizacionesDeOtroCaso()).isEmpty();
	}

	private void darSede(String zona) {
		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(new ConsultorioSnapshot(
				SEDE, ORG, "Sede sintetica", zona, true)));
	}

	private static SesionCerrada cierre(boolean asistio) {
		return new SesionCerrada(
				SESION, ORG, SEDE, PERSONA, 500L, 4, asistio,
				Instant.parse("2027-03-15T14:00:00Z"), 42L, BigDecimal.TEN, "ARS", Set.of());
	}
}
