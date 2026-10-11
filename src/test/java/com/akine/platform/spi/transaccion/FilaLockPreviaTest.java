package com.akine.platform.spi.transaccion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La regla que evita el traba del pool: la fila-lock se asegura FUERA de toda transaccion.
 *
 * <p>Lo que demuestra que el pool ya no se traba es {@code PoolDeConexionesIT}, contra MySQL real.
 * Esto fija las dos decisiones del helper que un IT no aisla: que falle con una transaccion activa
 * —en vez de abrir otra anidada que retiene dos conexiones— y que no toque una fila que ya existe.
 */
class FilaLockPreviaTest {

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
	private final FilaLockPrevia fila = new FilaLockPrevia("agenda_sede", transactionManager);

	@AfterEach
	void limpiar() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	@DisplayName("con una transaccion activa y la fila por crear falla, y no crea nada")
	void dentro_de_una_transaccion_falla() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		AtomicInteger creaciones = new AtomicInteger();

		assertThatThrownBy(() -> fila.asegurar(() -> false, creaciones::incrementAndGet))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("agenda_sede")
				.hasMessageContaining("ANTES");
		assertThat(creaciones).hasValue(0);
		verify(transactionManager, never()).getTransaction(any());
	}

	@Test
	@DisplayName("con una transaccion activa y la fila ya creada no hace falta otra conexion: no falla")
	void dentro_de_una_transaccion_con_la_fila_creada_no_falla() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		AtomicInteger creaciones = new AtomicInteger();

		fila.asegurar(() -> true, creaciones::incrementAndGet);

		assertThat(creaciones).hasValue(0);
		verify(transactionManager, never()).getTransaction(any());
	}

	@Test
	@DisplayName("si la fila ya existe no abre transaccion ni inserta")
	void la_fila_existente_no_se_toca() {
		AtomicInteger creaciones = new AtomicInteger();

		fila.asegurar(() -> true, creaciones::incrementAndGet);

		assertThat(creaciones).hasValue(0);
		verify(transactionManager, never()).getTransaction(any());
	}

	@Test
	@DisplayName("si falta, la crea en su propia transaccion y la commitea")
	void la_fila_que_falta_se_crea_en_su_transaccion() {
		AtomicInteger creaciones = new AtomicInteger();

		fila.asegurar(() -> false, creaciones::incrementAndGet);

		assertThat(creaciones).hasValue(1);
		verify(transactionManager).getTransaction(any());
		verify(transactionManager).commit(any());
	}
}
