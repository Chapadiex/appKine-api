package com.akine.notification.domain;

import com.akine.notification.domain.exception.InvalidOutboxTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Maquina de estados de la entrega (RF-M26-004).
 *
 * <p>Se prueba EXHAUSTIVAMENTE —las 36 combinaciones mas el alta— porque es una tabla: si una
 * transicion de mas se cuela, el worker puede reenviar una notificacion ya entregada o dejarla
 * girando sin agotarse nunca, y ninguna de las dos cosas se ve en un test de camino feliz.
 */
class OutboxStateMachineTest {

	/**
	 * La matriz completa {@code OutboxStatus x OutboxStatus}, celda por celda, transcrita a
	 * mano desde la tabla del JavaDoc de {@link OutboxStateMachine} (diseno 01.02 seccion 7).
	 *
	 * <p>NO se deriva de {@code allowedTargets}: si se derivara, el test aprobaria cualquier
	 * transicion que alguien le agregara a produccion, que es exactamente el cambio peligroso
	 * que tiene que detener.
	 *
	 * <p>Orden de los argumentos: origen, destino, permitida.
	 */
	private static Stream<Arguments> matrizCompleta() {
		return Stream.of(
				// PENDIENTE: recien encolada. Lo unico que le puede pasar es que el worker la
				// tome. Saltar directo a ENVIADA seria marcar entregado algo que nadie mando.
				Arguments.of(OutboxStatus.PENDIENTE, OutboxStatus.PENDIENTE, false),
				Arguments.of(OutboxStatus.PENDIENTE, OutboxStatus.PROCESANDO, true),
				Arguments.of(OutboxStatus.PENDIENTE, OutboxStatus.ENVIADA, false),
				Arguments.of(OutboxStatus.PENDIENTE, OutboxStatus.FALLIDA, false),
				Arguments.of(OutboxStatus.PENDIENTE, OutboxStatus.REINTENTABLE, false),
				Arguments.of(OutboxStatus.PENDIENTE, OutboxStatus.AGOTADA, false),

				// PROCESANDO: el unico estado con ramificacion. Sale hacia los cuatro
				// resultados posibles del envio y no vuelve a PENDIENTE (perderia el contador
				// de intentos y podria girar para siempre sin agotarse).
				Arguments.of(OutboxStatus.PROCESANDO, OutboxStatus.PENDIENTE, false),
				Arguments.of(OutboxStatus.PROCESANDO, OutboxStatus.PROCESANDO, false),
				Arguments.of(OutboxStatus.PROCESANDO, OutboxStatus.ENVIADA, true),
				Arguments.of(OutboxStatus.PROCESANDO, OutboxStatus.FALLIDA, true),
				Arguments.of(OutboxStatus.PROCESANDO, OutboxStatus.REINTENTABLE, true),
				Arguments.of(OutboxStatus.PROCESANDO, OutboxStatus.AGOTADA, true),

				// ENVIADA: terminal y feliz. Ninguna salida: una salida de aca es un mail
				// repetido a una persona real.
				Arguments.of(OutboxStatus.ENVIADA, OutboxStatus.PENDIENTE, false),
				Arguments.of(OutboxStatus.ENVIADA, OutboxStatus.PROCESANDO, false),
				Arguments.of(OutboxStatus.ENVIADA, OutboxStatus.ENVIADA, false),
				Arguments.of(OutboxStatus.ENVIADA, OutboxStatus.FALLIDA, false),
				Arguments.of(OutboxStatus.ENVIADA, OutboxStatus.REINTENTABLE, false),
				Arguments.of(OutboxStatus.ENVIADA, OutboxStatus.AGOTADA, false),

				// FALLIDA: fallo permanente. Solo vuelve por reintento administrativo
				// explicito, y vuelve a REINTENTABLE, nunca directo a PROCESANDO.
				Arguments.of(OutboxStatus.FALLIDA, OutboxStatus.PENDIENTE, false),
				Arguments.of(OutboxStatus.FALLIDA, OutboxStatus.PROCESANDO, false),
				Arguments.of(OutboxStatus.FALLIDA, OutboxStatus.ENVIADA, false),
				Arguments.of(OutboxStatus.FALLIDA, OutboxStatus.FALLIDA, false),
				Arguments.of(OutboxStatus.FALLIDA, OutboxStatus.REINTENTABLE, true),
				Arguments.of(OutboxStatus.FALLIDA, OutboxStatus.AGOTADA, false),

				// REINTENTABLE: espera su turno en la cola. Solo la toma el worker.
				Arguments.of(OutboxStatus.REINTENTABLE, OutboxStatus.PENDIENTE, false),
				Arguments.of(OutboxStatus.REINTENTABLE, OutboxStatus.PROCESANDO, true),
				Arguments.of(OutboxStatus.REINTENTABLE, OutboxStatus.ENVIADA, false),
				Arguments.of(OutboxStatus.REINTENTABLE, OutboxStatus.FALLIDA, false),
				Arguments.of(OutboxStatus.REINTENTABLE, OutboxStatus.REINTENTABLE, false),
				// AGOTADA solo se alcanza registrando el fallo que consume el ultimo intento,
				// es decir desde PROCESANDO: si se pudiera saltar desde REINTENTABLE, una fila
				// podria cerrarse sin haber gastado los intentos que dice haber gastado.
				Arguments.of(OutboxStatus.REINTENTABLE, OutboxStatus.AGOTADA, false),

				// AGOTADA: se acabaron los intentos. Como FALLIDA, solo sale por reintento
				// administrativo, que reinicia el contador.
				Arguments.of(OutboxStatus.AGOTADA, OutboxStatus.PENDIENTE, false),
				Arguments.of(OutboxStatus.AGOTADA, OutboxStatus.PROCESANDO, false),
				Arguments.of(OutboxStatus.AGOTADA, OutboxStatus.ENVIADA, false),
				Arguments.of(OutboxStatus.AGOTADA, OutboxStatus.FALLIDA, false),
				Arguments.of(OutboxStatus.AGOTADA, OutboxStatus.REINTENTABLE, true),
				Arguments.of(OutboxStatus.AGOTADA, OutboxStatus.AGOTADA, false));
	}

	@Test
	@DisplayName("La matriz del test cubre todas las celdas posibles")
	void la_matriz_del_test_esta_completa() {
		long celdasPosibles = (long) OutboxStatus.values().length * OutboxStatus.values().length;

		// Meta-test: si alguien agrega un estado nuevo (por ejemplo DESCARTADA) sin decidir su
		// fila y su columna, este test falla antes que ningun otro y obliga a tomar la
		// decision, en vez de dejar el estado nuevo sin una sola celda cubierta.
		assertThat(matrizCompleta().count()).isEqualTo(celdasPosibles);
	}

	@ParameterizedTest(name = "{0} -> {1} permitida={2}")
	@MethodSource("matrizCompleta")
	@DisplayName("Cada celda de la matriz coincide con la tabla del diseno")
	void cada_celda_coincide_con_la_tabla(
			OutboxStatus desde, OutboxStatus hacia, boolean permitida) {
		assertThat(OutboxStateMachine.isAllowed(desde, hacia)).isEqualTo(permitida);
	}

	@ParameterizedTest(name = "{0} -> {1} permitida={2}")
	@MethodSource("matrizCompleta")
	@DisplayName("assertTransitionAllowed falla exactamente en las celdas prohibidas")
	void assert_falla_exactamente_en_las_celdas_prohibidas(
			OutboxStatus desde, OutboxStatus hacia, boolean permitida) {
		// Las dos vistas de la misma tabla tienen que decidir igual: si divergieran, existiria
		// un camino de escritura que saltea la regla sin que nadie se entere.
		if (permitida) {
			assertThatCode(() -> OutboxStateMachine.assertTransitionAllowed(desde, hacia))
					.doesNotThrowAnyException();
		} else {
			assertThatThrownBy(() -> OutboxStateMachine.assertTransitionAllowed(desde, hacia))
					.isInstanceOf(InvalidOutboxTransitionException.class);
		}
	}

	@ParameterizedTest
	@EnumSource(OutboxStatus.class)
	@DisplayName("allowedTargets coincide celda por celda con isAllowed")
	void allowed_targets_coincide_con_is_allowed(OutboxStatus desde) {
		Set<OutboxStatus> destinos = OutboxStateMachine.allowedTargets(desde);
		for (OutboxStatus hacia : OutboxStatus.values()) {
			assertThat(destinos.contains(hacia))
					.as("%s -> %s", desde, hacia)
					.isEqualTo(OutboxStateMachine.isAllowed(desde, hacia));
		}
	}

	@ParameterizedTest
	@EnumSource(OutboxStatus.class)
	@DisplayName("El conjunto de destinos no se puede modificar desde afuera")
	void los_destinos_son_inmutables(OutboxStatus desde) {
		Set<OutboxStatus> destinos = OutboxStateMachine.allowedTargets(desde);

		// La tabla es estatica y compartida por todo el proceso: si el conjunto fuera mutable,
		// un llamador distraido podria habilitar ENVIADA -> PROCESANDO para siempre.
		assertThatThrownBy(() -> destinos.add(OutboxStatus.PROCESANDO))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("esTerminal y la tabla de transiciones dicen lo mismo, salvo el reintento manual")
	void terminal_y_tabla_coinciden() {
		// Un estado terminal no cambia SOLO: la unica salida que puede tener es la que abre un
		// administrador a mano. Si divergieran, el worker podria seguir tomando una fila que
		// la UI ya muestra como cerrada.
		for (OutboxStatus estado : OutboxStatus.values()) {
			boolean sinSalidaAutomatica = OutboxStateMachine.allowedTargets(estado).isEmpty()
					|| estado.admiteReintentoManual();
			assertThat(OutboxStateMachine.esTerminal(estado))
					.as("%s", estado)
					.isEqualTo(sinSalidaAutomatica);

			// Y al reves: un estado terminal jamas puede ser reclamable por el worker, o el
			// worker seguiria tomando filas ya cerradas.
			assertThat(estado.esReclamable() && OutboxStateMachine.esTerminal(estado))
					.as("%s", estado)
					.isFalse();
		}
	}

	@Test
	@DisplayName("Origen y destino nulos a la vez tampoco se aceptan")
	void origen_y_destino_nulos_no_se_aceptan() {
		assertThat(OutboxStateMachine.isAllowed(null, null)).isFalse();
	}

	@Test
	@DisplayName("El estado inicial declarado es PENDIENTE")
	void el_estado_inicial_declarado_es_pendiente() {
		assertThat(OutboxStatus.ESTADO_INICIAL).isEqualTo(OutboxStatus.PENDIENTE);
	}

	@Test
	@DisplayName("Los estados del outbox son exactamente los seis del diseno")
	void los_estados_son_exactamente_seis() {
		// Si aparece uno nuevo, la matriz de arriba deja de estar completa y el meta-test lo
		// canta. Este test explica cual era la lista original.
		assertThat(OutboxStatus.values()).containsExactly(
				OutboxStatus.PENDIENTE,
				OutboxStatus.PROCESANDO,
				OutboxStatus.ENVIADA,
				OutboxStatus.FALLIDA,
				OutboxStatus.REINTENTABLE,
				OutboxStatus.AGOTADA);
	}

	@Test
	@DisplayName("La maquina de estados no tiene estado propio ni se instancia")
	void la_maquina_no_se_instancia() throws Exception {
		// Es una regla pura: si se pudiera instanciar, el paso siguiente seria inyectarla con
		// una tabla distinta por entorno y la regla dejaria de ser una sola.
		assertThat(Modifier.isFinal(OutboxStateMachine.class.getModifiers())).isTrue();

		Constructor<OutboxStateMachine> constructor =
				OutboxStateMachine.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
		constructor.setAccessible(true);
		assertThat(constructor.newInstance()).isNotNull();
	}

	@Test
	@DisplayName("Una notificacion solo puede nacer PENDIENTE")
	void solo_nace_pendiente() {
		assertThat(OutboxStateMachine.isAllowed(null, OutboxStatus.PENDIENTE)).isTrue();
		assertThat(OutboxStateMachine.allowedTargets(null)).containsExactly(OutboxStatus.PENDIENTE);
		for (OutboxStatus otro : OutboxStatus.values()) {
			if (otro != OutboxStatus.PENDIENTE) {
				assertThat(OutboxStateMachine.isAllowed(null, otro)).isFalse();
			}
		}
	}

	@Test
	@DisplayName("La tabla completa de transiciones es exactamente la documentada")
	void la_tabla_es_exactamente_la_documentada() {
		assertThat(OutboxStateMachine.allowedTargets(OutboxStatus.PENDIENTE))
				.containsExactlyInAnyOrder(OutboxStatus.PROCESANDO);
		assertThat(OutboxStateMachine.allowedTargets(OutboxStatus.PROCESANDO))
				.containsExactlyInAnyOrder(
						OutboxStatus.ENVIADA,
						OutboxStatus.REINTENTABLE,
						OutboxStatus.FALLIDA,
						OutboxStatus.AGOTADA);
		assertThat(OutboxStateMachine.allowedTargets(OutboxStatus.REINTENTABLE))
				.containsExactlyInAnyOrder(OutboxStatus.PROCESANDO);
		assertThat(OutboxStateMachine.allowedTargets(OutboxStatus.FALLIDA))
				.containsExactlyInAnyOrder(OutboxStatus.REINTENTABLE);
		assertThat(OutboxStateMachine.allowedTargets(OutboxStatus.AGOTADA))
				.containsExactlyInAnyOrder(OutboxStatus.REINTENTABLE);
		assertThat(OutboxStateMachine.allowedTargets(OutboxStatus.ENVIADA)).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(OutboxStatus.class)
	@DisplayName("Ninguna transicion hacia el mismo estado esta permitida")
	void ninguna_transicion_a_si_mismo(OutboxStatus estado) {
		assertThat(OutboxStateMachine.isAllowed(estado, estado)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(OutboxStatus.class)
	@DisplayName("Un destino nulo nunca esta permitido")
	void destino_nulo_nunca(OutboxStatus estado) {
		assertThat(OutboxStateMachine.isAllowed(estado, null)).isFalse();
	}

	@Test
	@DisplayName("ENVIADA es terminal: una notificacion entregada no vuelve a la cola")
	void enviada_es_terminal() {
		for (OutboxStatus destino : OutboxStatus.values()) {
			assertThat(OutboxStateMachine.isAllowed(OutboxStatus.ENVIADA, destino)).isFalse();
		}
	}

	@Test
	@DisplayName("Solo FALLIDA y AGOTADA admiten reintento administrativo")
	void reintento_manual_solo_desde_fallida_y_agotada() {
		Set<OutboxStatus> admiten = Set.of(OutboxStatus.FALLIDA, OutboxStatus.AGOTADA);
		for (OutboxStatus estado : OutboxStatus.values()) {
			assertThat(estado.admiteReintentoManual()).isEqualTo(admiten.contains(estado));
		}
	}

	@Test
	@DisplayName("Solo PENDIENTE y REINTENTABLE son reclamables por el worker")
	void reclamables() {
		Set<OutboxStatus> reclamables = Set.of(OutboxStatus.PENDIENTE, OutboxStatus.REINTENTABLE);
		for (OutboxStatus estado : OutboxStatus.values()) {
			assertThat(estado.esReclamable()).isEqualTo(reclamables.contains(estado));
		}
	}

	@Test
	@DisplayName("Son terminales ENVIADA, FALLIDA y AGOTADA")
	void terminales() {
		assertThat(OutboxStateMachine.esTerminal(OutboxStatus.ENVIADA)).isTrue();
		assertThat(OutboxStateMachine.esTerminal(OutboxStatus.FALLIDA)).isTrue();
		assertThat(OutboxStateMachine.esTerminal(OutboxStatus.AGOTADA)).isTrue();
		assertThat(OutboxStateMachine.esTerminal(OutboxStatus.PENDIENTE)).isFalse();
		assertThat(OutboxStateMachine.esTerminal(OutboxStatus.PROCESANDO)).isFalse();
		assertThat(OutboxStateMachine.esTerminal(OutboxStatus.REINTENTABLE)).isFalse();
	}

	@Test
	@DisplayName("assertTransitionAllowed falla con los dos estados en la excepcion")
	void assert_falla_con_detalle() {
		assertThatThrownBy(() -> OutboxStateMachine.assertTransitionAllowed(
				OutboxStatus.ENVIADA, OutboxStatus.PROCESANDO))
				.isInstanceOf(InvalidOutboxTransitionException.class)
				.hasMessageContaining("ENVIADA")
				.hasMessageContaining("PROCESANDO");

		InvalidOutboxTransitionException error = new InvalidOutboxTransitionException(
				OutboxStatus.ENVIADA, OutboxStatus.PROCESANDO);
		assertThat(error.getDesde()).isEqualTo(OutboxStatus.ENVIADA);
		assertThat(error.getHacia()).isEqualTo(OutboxStatus.PROCESANDO);
	}

	@Test
	@DisplayName("assertTransitionAllowed no molesta cuando la transicion es valida")
	void assert_no_molesta_si_es_valida() {
		OutboxStateMachine.assertTransitionAllowed(OutboxStatus.PENDIENTE, OutboxStatus.PROCESANDO);
	}
}
