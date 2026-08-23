package com.akine.platform.tenant;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.akine.platform.infrastructure.tenant.ThreadLocalTenantContextHolder;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantOperationalStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El holder es el lugar donde vive el {@code organizationId} que despues termina en todos los
 * {@code WHERE} del sistema. Sus garantias son dos: que no se pueda leer un contexto que no
 * existe, y que no se filtre de un hilo a otro.
 */
class ThreadLocalTenantContextHolderTest {

	private final ThreadLocalTenantContextHolder holder = new ThreadLocalTenantContextHolder();

	@AfterEach
	void limpiar() {
		holder.clear();
	}

	@Test
	@DisplayName("Publicar y leer el contexto del request")
	void publica_y_lee() {
		holder.set(contexto());

		assertThat(holder.current()).isPresent();
		assertThat(holder.require().organizationId()).isEqualTo(100L);
	}

	@Test
	@DisplayName("require() falla ruidosamente si no hay contexto, en vez de devolver null")
	void require_falla_sin_contexto() {
		// Un organizationId nulo que llega a un WHERE es exactamente como se filtra
		// informacion entre tenants: preferimos romper el request.
		assertThatThrownBy(holder::require)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("contexto de tenant");
	}

	@Test
	@DisplayName("clear() deja el holder vacio")
	void clear_vacia() {
		holder.set(contexto());
		holder.clear();

		assertThat(holder.current()).isEmpty();
	}

	@Test
	@DisplayName("El contexto NO se hereda a otro hilo: un pool no puede quedarse con el tenant ajeno")
	void el_contexto_no_se_hereda() throws Exception {
		holder.set(contexto());

		AtomicReference<Boolean> habiaContexto = new AtomicReference<>();
		Thread otro = new Thread(() -> habiaContexto.set(holder.current().isPresent()));
		otro.start();
		otro.join();

		// Si el holder fuera InheritableThreadLocal, un pool de hilos heredaria el contexto del
		// primer request que lo creo y lo conservaria para siempre.
		assertThat(habiaContexto.get()).isFalse();
	}

	@Test
	@DisplayName("No se publica un contexto nulo: para limpiar existe clear()")
	void no_acepta_contexto_nulo() {
		assertThatThrownBy(() -> holder.set(null)).isInstanceOf(IllegalArgumentException.class);
	}

	private static RequestTenantContext contexto() {
		return new RequestTenantContext(7L, 100L, 200L, "ORG_ADMIN", TenantOperationalStatus.ACTIVA);
	}
}
