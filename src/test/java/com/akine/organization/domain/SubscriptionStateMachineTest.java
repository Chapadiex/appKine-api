package com.akine.organization.domain;

import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica la tabla de transiciones de la suscripcion (design 01.01 seccion 3.1, RF-M01-003).
 *
 * <p>Es la unica logica con ramificacion real del dominio, asi que se recorre la matriz
 * COMPLETA {@code SubscriptionStatus x SubscriptionStatus} celda por celda en lugar de probar
 * una muestra. La expectativa de cada celda esta escrita a mano desde la tabla del diseno y
 * NO se deriva de la tabla de produccion: si se derivara, el test aprobaria cualquier cambio
 * que alguien le hiciera a la maquina de estados.
 *
 * <p>Lo que previene: que un tenant cancelado vuelva a la vida por una transicion de salida
 * agregada sin querer, y que un reintento de "suspender" sobre una suscripcion ya suspendida
 * produzca un segundo efecto y una segunda fila de historico.
 */
class SubscriptionStateMachineTest {

	/**
	 * La matriz completa, celda por celda, transcrita de la tabla del diseno 3.1.
	 *
	 * <p>Orden de los argumentos: origen, destino, permitida.
	 */
	private static Stream<Arguments> matrizCompleta() {
		return Stream.of(
				// ACTIVA: sale hacia los dos estados restantes, nunca hacia si misma.
				Arguments.of(SubscriptionStatus.ACTIVA, SubscriptionStatus.ACTIVA, false),
				Arguments.of(SubscriptionStatus.ACTIVA, SubscriptionStatus.SUSPENDIDA, true),
				Arguments.of(SubscriptionStatus.ACTIVA, SubscriptionStatus.CANCELADA, true),

				// SUSPENDIDA: vuelve a ACTIVA (suspender bloquea, no destruye) y puede cancelarse.
				Arguments.of(SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.ACTIVA, true),
				Arguments.of(SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.SUSPENDIDA, false),
				Arguments.of(SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.CANCELADA, true),

				// CANCELADA: terminal. Ninguna salida, ni siquiera hacia si misma.
				Arguments.of(SubscriptionStatus.CANCELADA, SubscriptionStatus.ACTIVA, false),
				Arguments.of(SubscriptionStatus.CANCELADA, SubscriptionStatus.SUSPENDIDA, false),
				Arguments.of(SubscriptionStatus.CANCELADA, SubscriptionStatus.CANCELADA, false));
	}

	@Test
	@DisplayName("La matriz del test cubre todas las celdas posibles")
	void la_matriz_del_test_esta_completa() {
		long celdasPosibles = (long) SubscriptionStatus.values().length
				* SubscriptionStatus.values().length;

		// Si alguien agrega un estado nuevo (por ejemplo TRIAL) sin decidir su fila y su
		// columna, este test falla antes que ninguno: obliga a tomar la decision en vez de
		// dejar el estado nuevo sin cubrir.
		assertThat(matrizCompleta().count()).isEqualTo(celdasPosibles);
	}

	@ParameterizedTest(name = "{0} -> {1} permitida={2}")
	@MethodSource("matrizCompleta")
	@DisplayName("Cada celda de la matriz coincide con la tabla del diseno")
	void cada_celda_coincide_con_la_tabla(
			SubscriptionStatus desde, SubscriptionStatus hasta, boolean permitida) {
		assertThat(SubscriptionStateMachine.isAllowed(desde, hasta)).isEqualTo(permitida);
	}

	@ParameterizedTest(name = "{0} -> {1} permitida={2}")
	@MethodSource("matrizCompleta")
	@DisplayName("assertTransitionAllowed falla exactamente en las celdas prohibidas")
	void assert_falla_exactamente_en_las_celdas_prohibidas(
			SubscriptionStatus desde, SubscriptionStatus hasta, boolean permitida) {
		if (permitida) {
			assertThatCode(() -> SubscriptionStateMachine.assertTransitionAllowed(desde, hasta))
					.doesNotThrowAnyException();
		} else {
			// La version que falla y la que devuelve boolean tienen que decidir lo mismo:
			// si divergieran, existiria un camino de escritura que saltea la regla.
			assertThatThrownBy(() -> SubscriptionStateMachine.assertTransitionAllowed(desde, hasta))
					.isInstanceOf(InvalidSubscriptionTransitionException.class);
		}
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Una suscripcion cancelada no tiene ninguna transicion de salida")
	void cancelada_es_terminal(SubscriptionStatus destino) {
		// El estado terminal es la garantia comercial de que "cancelar" es definitivo: si
		// alguna salida se colara, un tenant dado de baja podria reactivarse sin ADR.
		assertThat(SubscriptionStateMachine.isAllowed(SubscriptionStatus.CANCELADA, destino))
				.isFalse();
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Repetir el estado actual esta prohibido en todos los estados")
	void la_transicion_vacia_esta_prohibida(SubscriptionStatus estado) {
		// Un PUT repetido no puede producir un segundo efecto ni una segunda fila de
		// historico. Incluye ACTIVA -> ACTIVA, que es la que se cuela cuando alguien
		// confunde "idempotente" con "no hacer nada y devolver 200".
		assertThat(SubscriptionStateMachine.isAllowed(estado, estado)).isFalse();
	}

	@Test
	@DisplayName("ACTIVA y SUSPENDIDA se alternan en los dos sentidos")
	void activa_y_suspendida_van_y_vuelven() {
		assertThat(SubscriptionStateMachine.isAllowed(
				SubscriptionStatus.ACTIVA, SubscriptionStatus.SUSPENDIDA)).isTrue();
		assertThat(SubscriptionStateMachine.isAllowed(
				SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.ACTIVA)).isTrue();
	}

	@Test
	@DisplayName("El alta solo puede nacer ACTIVA")
	void el_alta_solo_nace_activa() {
		assertThat(SubscriptionStateMachine.isAllowed(null, SubscriptionStatus.ACTIVA)).isTrue();

		// Nacer suspendida o cancelada dejaria un tenant creado y sin operacion desde el
		// primer minuto, sin ninguna transicion que explique por que.
		assertThat(SubscriptionStateMachine.isAllowed(null, SubscriptionStatus.SUSPENDIDA))
				.isFalse();
		assertThat(SubscriptionStateMachine.isAllowed(null, SubscriptionStatus.CANCELADA))
				.isFalse();
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Un destino nulo nunca se acepta")
	void un_destino_nulo_nunca_se_acepta(SubscriptionStatus desde) {
		// Un destino nulo llegando de una deserializacion incompleta no puede interpretarse
		// como "dejar el estado como esta": tiene que fallar.
		assertThat(SubscriptionStateMachine.isAllowed(desde, null)).isFalse();
	}

	@Test
	@DisplayName("Origen y destino nulos a la vez tampoco se aceptan")
	void origen_y_destino_nulos_no_se_aceptan() {
		assertThat(SubscriptionStateMachine.isAllowed(null, null)).isFalse();
	}

	@Test
	@DisplayName("El estado inicial de toda suscripcion es ACTIVA")
	void el_estado_inicial_es_activa() {
		assertThat(SubscriptionStateMachine.ESTADO_INICIAL).isEqualTo(SubscriptionStatus.ACTIVA);
	}

	@Test
	@DisplayName("allowedTargets devuelve los destinos de cada estado")
	void allowed_targets_devuelve_los_destinos() {
		assertThat(SubscriptionStateMachine.allowedTargets(SubscriptionStatus.ACTIVA))
				.containsExactlyInAnyOrder(
						SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.CANCELADA);
		assertThat(SubscriptionStateMachine.allowedTargets(SubscriptionStatus.SUSPENDIDA))
				.containsExactlyInAnyOrder(
						SubscriptionStatus.ACTIVA, SubscriptionStatus.CANCELADA);
		assertThat(SubscriptionStateMachine.allowedTargets(SubscriptionStatus.CANCELADA))
				.isEmpty();
		assertThat(SubscriptionStateMachine.allowedTargets(null))
				.containsExactly(SubscriptionStatus.ACTIVA);
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("allowedTargets coincide celda por celda con isAllowed")
	void allowed_targets_coincide_con_is_allowed(SubscriptionStatus desde) {
		Set<SubscriptionStatus> destinos = SubscriptionStateMachine.allowedTargets(desde);

		// La UI dibuja los botones con allowedTargets y la escritura valida con isAllowed.
		// Si las dos vistas de la misma tabla divergieran, el usuario veria una accion que
		// el backend despues rechaza con 409.
		for (SubscriptionStatus hasta : SubscriptionStatus.values()) {
			assertThat(destinos.contains(hasta))
					.as("%s -> %s", desde, hasta)
					.isEqualTo(SubscriptionStateMachine.isAllowed(desde, hasta));
		}
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("El conjunto de destinos no se puede modificar desde afuera")
	void los_destinos_son_inmutables(SubscriptionStatus desde) {
		Set<SubscriptionStatus> destinos = SubscriptionStateMachine.allowedTargets(desde);

		// La tabla es estatica y compartida: si el conjunto fuera mutable, un llamador
		// distraido podria agregarle CANCELADA -> ACTIVA para todo el proceso.
		assertThatThrownBy(() -> destinos.add(SubscriptionStatus.ACTIVA))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("Suspender y cancelar exigen motivo; reactivar no")
	void solo_suspender_y_cancelar_exigen_motivo() {
		// Sin motivo, el historico no responde "por que dejo de funcionar" seis meses
		// despues, que es justo cuando se pregunta.
		assertThat(SubscriptionStateMachine.requiresReason(SubscriptionStatus.SUSPENDIDA)).isTrue();
		assertThat(SubscriptionStateMachine.requiresReason(SubscriptionStatus.CANCELADA)).isTrue();
		assertThat(SubscriptionStateMachine.requiresReason(SubscriptionStatus.ACTIVA)).isFalse();
	}

	@Test
	@DisplayName("El error de transicion nombra los dos estados sin filtrar internos")
	void el_error_nombra_los_dos_estados() {
		InvalidSubscriptionTransitionException error = capturarTransicionInvalida(
				SubscriptionStatus.CANCELADA, SubscriptionStatus.ACTIVA);

		assertThat(error.getFrom()).isEqualTo(SubscriptionStatus.CANCELADA);
		assertThat(error.getTo()).isEqualTo(SubscriptionStatus.ACTIVA);

		// Los dos estados tienen que estar en el mensaje: sin ellos, el log de produccion
		// dice "transicion no permitida" y no alcanza para reconstruir que paso.
		assertThat(error.getMessage())
				.contains("CANCELADA")
				.contains("ACTIVA")
				// ...pero nada de la maquinaria interna, que es lo que termina en la respuesta
				// HTTP si alguien encadena el mensaje sin pensarlo.
				.doesNotContain("com.akine")
				.doesNotContain("EnumMap")
				.doesNotContain("TRANSICIONES");
	}

	@Test
	@DisplayName("El alta fallida se reporta con origen nulo")
	void el_alta_fallida_reporta_origen_nulo() {
		InvalidSubscriptionTransitionException error =
				capturarTransicionInvalida(null, SubscriptionStatus.CANCELADA);

		assertThat(error.getFrom()).isNull();
		assertThat(error.getTo()).isEqualTo(SubscriptionStatus.CANCELADA);
	}

	@Test
	@DisplayName("La maquina de estados no tiene estado propio ni se instancia")
	void la_maquina_de_estados_no_se_instancia() throws Exception {
		// Es una regla pura: si alguien pudiera instanciarla, el paso siguiente seria
		// inyectarla con una tabla distinta por entorno y la regla dejaria de ser una.
		assertThat(Modifier.isFinal(SubscriptionStateMachine.class.getModifiers())).isTrue();

		Constructor<SubscriptionStateMachine> constructor =
				SubscriptionStateMachine.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();

		constructor.setAccessible(true);
		assertThat(constructor.newInstance()).isNotNull();
	}

	@Test
	@DisplayName("isTerminal y la tabla de transiciones dicen lo mismo")
	void terminal_y_tabla_coinciden() {
		// Dos formas de expresar la misma verdad: un estado terminal no puede tener salidas.
		// Si divergieran, el codigo que confia en isTerminal para ocultar acciones dejaria
		// pasar una transicion que la tabla si permite (o al reves).
		for (SubscriptionStatus estado : SubscriptionStatus.values()) {
			assertThat(estado.isTerminal())
					.as("%s", estado)
					.isEqualTo(SubscriptionStateMachine.allowedTargets(estado).isEmpty());
		}
	}

	@Test
	@DisplayName("Solo CANCELADA es terminal")
	void solo_cancelada_es_terminal() {
		assertThat(EnumSet.allOf(SubscriptionStatus.class).stream()
				.filter(SubscriptionStatus::isTerminal))
				.containsExactly(SubscriptionStatus.CANCELADA);
	}

	private static InvalidSubscriptionTransitionException capturarTransicionInvalida(
			SubscriptionStatus desde, SubscriptionStatus hasta) {
		Throwable error = org.assertj.core.api.Assertions.catchThrowable(
				() -> SubscriptionStateMachine.assertTransitionAllowed(desde, hasta));
		assertThat(error).isInstanceOf(InvalidSubscriptionTransitionException.class);
		return (InvalidSubscriptionTransitionException) error;
	}

	@Test
	@DisplayName("Los estados de suscripcion son exactamente los tres del diseno")
	void los_estados_son_exactamente_tres() {
		// Trial y morosidad se dejaron fuera a proposito (diseno 3.1): aparecen con
		// facturacion y ADR propio, no por goteo.
		assertThat(SubscriptionStatus.values())
				.containsExactly(
						SubscriptionStatus.ACTIVA,
						SubscriptionStatus.SUSPENDIDA,
						SubscriptionStatus.CANCELADA);
	}
}
