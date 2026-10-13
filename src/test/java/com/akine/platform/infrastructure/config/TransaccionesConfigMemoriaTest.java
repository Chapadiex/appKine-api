package com.akine.platform.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.DefaultJpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.akine.platform.spi.pedido.MemoriaDelPedido;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

/**
 * El transaction manager de la aplicacion descarta la memoria del pedido al commitear una
 * transaccion de escritura, y no al commitear una de lectura (docs/carga.md §9).
 */
class TransaccionesConfigMemoriaTest {

	private final AtomicInteger lecturas = new AtomicInteger();

	@Test
	@DisplayName("Despues de commitear una escritura, el pedido vuelve a leer la base")
	void el_commit_de_escritura_olvida() {
		JpaTransactionManager manager = manager();
		try (MemoriaDelPedido.Alcance pedido = MemoriaDelPedido.abrir()) {
			leer();

			commitear(manager, false);

			leer();
		}
		assertThat(lecturas).hasValue(2);
	}

	@Test
	@DisplayName("Una transaccion de lectura no descarta lo memorizado")
	void el_commit_de_lectura_no_olvida() {
		JpaTransactionManager manager = manager();
		try (MemoriaDelPedido.Alcance pedido = MemoriaDelPedido.abrir()) {
			leer();

			commitear(manager, true);

			leer();
		}
		assertThat(lecturas).hasValue(1);
	}

	private void leer() {
		MemoriaDelPedido.memorizar("membership", lecturas::incrementAndGet);
	}

	private static void commitear(JpaTransactionManager manager, boolean soloLectura) {
		DefaultTransactionDefinition definicion = new DefaultTransactionDefinition();
		definicion.setReadOnly(soloLectura);
		TransactionStatus estado = manager.getTransaction(definicion);
		manager.commit(estado);
	}

	private static JpaTransactionManager manager() {
		EntityTransaction transaccion = mock(EntityTransaction.class);
		EntityManager entityManager = mock(EntityManager.class);
		when(entityManager.getTransaction()).thenReturn(transaccion);
		EntityManagerFactory fabrica = mock(EntityManagerFactory.class);
		when(fabrica.createEntityManager()).thenReturn(entityManager);
		when(fabrica.createEntityManager(any(java.util.Map.class))).thenReturn(entityManager);

		JpaTransactionManager manager = new TransaccionesConfig.AvisaAlTerminar();
		manager.setEntityManagerFactory(fabrica);
		manager.setJpaDialect(new DefaultJpaDialect());
		return manager;
	}
}
