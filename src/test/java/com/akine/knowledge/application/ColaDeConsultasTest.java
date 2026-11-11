package com.akine.knowledge.application;

import com.akine.knowledge.domain.exception.ConsultaOcupadaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColaDeConsultasTest {

	private final ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void cerrar() {
		hilos.shutdownNow();
	}

	@Test
	void quintaConsultaRecibeOcupada() {
		ColaDeConsultas cola = new ColaDeConsultas(3);
		ColaDeConsultas.Turno primero = cola.reservar();
		List<ColaDeConsultas.Turno> esperando = List.of(cola.reservar(), cola.reservar(), cola.reservar());

		assertThat(cola.enCurso()).isEqualTo(1);
		assertThat(cola.enEspera()).isEqualTo(3);
		assertThatThrownBy(cola::reservar).isInstanceOf(ConsultaOcupadaException.class);

		// Al liberar un lugar de espera, vuelve a haber lugar para uno.
		esperando.get(2).close();
		assertThat(cola.enEspera()).isEqualTo(2);
		cola.reservar().close();
		primero.close();
	}

	@Test
	void cerrarLiberaAlSiguiente() throws Exception {
		ColaDeConsultas cola = new ColaDeConsultas(3);
		ColaDeConsultas.Turno primero = cola.reservar();
		assertThat(primero.esperar(p -> { })).isTrue();

		List<List<Integer>> posiciones = new ArrayList<>();
		List<CompletableFuture<Boolean>> entradas = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ColaDeConsultas.Turno turno = cola.reservar();
			List<Integer> vistas = new CopyOnWriteArrayList<>();
			posiciones.add(vistas);
			entradas.add(CompletableFuture.supplyAsync(() -> turno.esperar(vistas::add), hilos));
		}
		dentroDe5s(() -> {
			assertThat(posiciones.get(0)).containsExactly(1);
			assertThat(posiciones.get(1)).containsExactly(2);
			assertThat(posiciones.get(2)).containsExactly(3);
		});

		primero.close();

		assertThat(entradas.get(0).get(5, TimeUnit.SECONDS)).isTrue();
		dentroDe5s(() -> {
			assertThat(posiciones.get(1)).containsExactly(2, 1);
			assertThat(posiciones.get(2)).containsExactly(3, 2);
		});
		assertThat(entradas.get(1)).isNotDone();
		assertThat(cola.enCurso()).isEqualTo(1);
		assertThat(cola.enEspera()).isEqualTo(2);
	}

	@Test
	void abandonarLaEsperaAdelantaALosDeAtrasYNoTomaElLugar() throws Exception {
		ColaDeConsultas cola = new ColaDeConsultas(3);
		ColaDeConsultas.Turno primero = cola.reservar();
		ColaDeConsultas.Turno segundo = cola.reservar();
		ColaDeConsultas.Turno tercero = cola.reservar();
		List<Integer> vistasDelTercero = new CopyOnWriteArrayList<>();
		CompletableFuture<Boolean> esperaDelSegundo = CompletableFuture.supplyAsync(() -> segundo.esperar(p -> { }), hilos);
		CompletableFuture<Boolean> esperaDelTercero =
				CompletableFuture.supplyAsync(() -> tercero.esperar(vistasDelTercero::add), hilos);
		dentroDe5s(() -> assertThat(vistasDelTercero).contains(2));

		segundo.abandonar();

		assertThat(esperaDelSegundo.get(5, TimeUnit.SECONDS)).isFalse();
		dentroDe5s(() -> assertThat(vistasDelTercero).containsExactly(2, 1));
		primero.close();
		assertThat(esperaDelTercero.get(5, TimeUnit.SECONDS)).isTrue();
		segundo.close();
		assertThat(cola.enCurso()).isEqualTo(1);
		assertThat(cola.enEspera()).isZero();
		tercero.close();
		assertThat(cola.enCurso()).isZero();
	}

	/** Reintenta el aserto hasta que pase o pasen 5 s. */
	private static void dentroDe5s(Runnable aserto) throws InterruptedException {
		long vence = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			try {
				aserto.run();
				return;
			} catch (AssertionError e) {
				if (System.nanoTime() - vence >= 0) {
					throw e;
				}
				Thread.sleep(20);
			}
		}
	}
}
