package com.akine.platform.spi.pedido;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Las tres garantias de {@link MemoriaDelPedido}: no cruza pedidos, no existe fuera de uno y no
 * se usa dentro de una transaccion de escritura (docs/carga.md §9).
 */
class MemoriaDelPedidoTest {

	private final AtomicInteger lecturas = new AtomicInteger();
	private final Supplier<Integer> lector = lecturas::incrementAndGet;

	@AfterEach
	void limpiarTransaccion() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
		TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
	}

	@Test
	@DisplayName("Dentro de un pedido, la misma lectura se hace una sola vez")
	void una_vez_por_pedido() {
		try (MemoriaDelPedido.Alcance pedido = MemoriaDelPedido.abrir()) {
			assertThat(MemoriaDelPedido.memorizar("clave", lector)).isEqualTo(1);
			assertThat(MemoriaDelPedido.memorizar("clave", lector)).isEqualTo(1);
			assertThat(MemoriaDelPedido.memorizar("otra", lector)).isEqualTo(2);
		}
		assertThat(lecturas).hasValue(2);
	}

	@Test
	@DisplayName("El pedido siguiente no ve nada de lo que leyo el anterior")
	void no_cruza_pedidos() {
		try (MemoriaDelPedido.Alcance pedido = MemoriaDelPedido.abrir()) {
			MemoriaDelPedido.memorizar("clave", lector);
		}
		try (MemoriaDelPedido.Alcance pedido = MemoriaDelPedido.abrir()) {
			assertThat(MemoriaDelPedido.memorizar("clave", lector)).isEqualTo(2);
		}
	}

	@Test
	@DisplayName("Fuera de un pedido cada lectura va a la base")
	void fuera_de_un_pedido_no_memoriza() {
		MemoriaDelPedido.memorizar("clave", lector);
		MemoriaDelPedido.memorizar("clave", lector);

		assertThat(lecturas).hasValue(2);
	}

	@Test
	@DisplayName("En una transaccion de lectura memoriza")
	void transaccion_de_lectura() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
		try (MemoriaDelPedido.Alcance pedido = MemoriaDelPedido.abrir()) {
			MemoriaDelPedido.memorizar("clave", lector);
			MemoriaDelPedido.memorizar("clave", lector);
		}
		assertThat(lecturas).hasValue(1);
	}

	@Test
	@DisplayName("En una transaccion de escritura lee la base y descarta lo memorizado antes")
	void transaccion_de_escritura() {
		try (MemoriaDelPedido.Alcance pedido = MemoriaDelPedido.abrir()) {
			// El filtro lee fuera de la transaccion de negocio...
			MemoriaDelPedido.memorizar("clave", lector);

			// ...la mutacion, ya con el bloqueo tomado, tiene que leer la base otra vez.
			TransactionSynchronizationManager.setActualTransactionActive(true);
			TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
			assertThat(MemoriaDelPedido.memorizar("clave", lector)).isEqualTo(2);
			assertThat(MemoriaDelPedido.memorizar("clave", lector)).isEqualTo(3);

			// Y lo que se habia memorizado antes de escribir ya no vale.
			TransactionSynchronizationManager.setActualTransactionActive(false);
			assertThat(MemoriaDelPedido.memorizar("clave", lector)).isEqualTo(4);
		}
	}
}
