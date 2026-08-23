package com.akine.platform.infrastructure.tenant;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;

/**
 * Implementacion del holder de contexto sobre {@link ThreadLocal}.
 *
 * <p>Vive en {@code infrastructure} y no en {@code spi} (decision D-12): el contrato es una
 * interfaz, el estado por request es un detalle de adaptador.
 *
 * <p><b>Por que {@code ThreadLocal} y no {@code @RequestScope}.</b> Un bean {@code @RequestScope}
 * solo existe si hay un {@code RequestContextHolder} activo, y eso ata la resolucion de tenant
 * al transporte HTTP: un job programado, un consumidor de eventos o un test unitario no podrian
 * establecer contexto. Con {@code ThreadLocal} el mecanismo es el mismo dentro y fuera de un
 * request. La contrapartida —limpiar siempre— la cubre {@code TenantContextFilter} en un
 * {@code finally}.
 *
 * <p><b>No es {@code InheritableThreadLocal} a proposito.</b> Heredar el contexto a hilos hijos
 * suena comodo y es peligroso: un pool de hilos hereda el contexto del primer request que lo
 * creo y lo conserva para siempre, con lo cual una tarea asincrona terminaria operando sobre el
 * tenant equivocado. Si algun dia hace falta propagar contexto a un hilo, se propaga explicito.
 *
 * <p>{@code @Component} y no {@code @Service}: {@code servicios_solo_en_application} reserva
 * {@code @Service} para la capa de negocio.
 */
@Component
public class ThreadLocalTenantContextHolder implements TenantContextHolder {

	private static final ThreadLocal<RequestTenantContext> CONTEXT = new ThreadLocal<>();

	@Override
	public void set(RequestTenantContext context) {
		if (context == null) {
			throw new IllegalArgumentException("No se publica un contexto nulo: para limpiar se usa clear()");
		}
		CONTEXT.set(context);
	}

	@Override
	public Optional<RequestTenantContext> current() {
		return Optional.ofNullable(CONTEXT.get());
	}

	@Override
	public RequestTenantContext require() {
		RequestTenantContext context = CONTEXT.get();
		if (context == null) {
			throw new IllegalStateException(
					"No hay contexto de tenant en el request: esta operacion no puede ejecutarse "
							+ "sin organizacion resuelta");
		}
		return context;
	}

	@Override
	public void clear() {
		// remove() y no set(null): set(null) deja la entrada en el mapa del hilo y con ella la
		// referencia al ThreadLocal, que en un contenedor con pools de hilos es una fuga.
		CONTEXT.remove();
	}
}
