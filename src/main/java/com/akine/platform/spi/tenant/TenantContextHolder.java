package com.akine.platform.spi.tenant;

import java.util.Optional;

/**
 * Acceso al contexto de tenant validado del request en curso.
 *
 * <p><b>Es una interfaz y vive en {@code spi} por decision D-12:</b> {@code spi} son contratos.
 * Un holder con estado por request es un adaptador, y su implementacion —{@code ThreadLocal} o
 * bean {@code @RequestScope}— vive en {@code platform.infrastructure.tenant}. Asi los modulos
 * consumidores dependen del contrato y no del mecanismo, y el mecanismo se puede cambiar (por
 * ejemplo al pasar a hilos virtuales o a WebFlux) sin tocar a nadie.
 *
 * <p>Quien puebla el holder es {@code TenantContextFilter}, una sola vez por request y solo
 * despues de revalidar contra la base. Ningun otro codigo debe llamar a {@link #set} ni a
 * {@link #clear}: hacerlo equivale a cambiarse de tenant a mitad de operacion.
 */
public interface TenantContextHolder {

	/**
	 * Publica el contexto validado. Lo invoca unicamente el filtro de contexto.
	 *
	 * @param context contexto ya revalidado contra la base
	 */
	void set(RequestTenantContext context);

	/**
	 * Contexto del request, o vacio si no hay ninguno.
	 *
	 * <p>Vacio es un estado legitimo: rutas exceptuadas del filtro, requests de
	 * {@code PLATFORM_ADMIN} sin tenant, jobs y consumidores de eventos.
	 */
	Optional<RequestTenantContext> current();

	/**
	 * Contexto del request, exigiendolo.
	 *
	 * <p>Lo usa el codigo de negocio que no tiene sentido sin tenant. Falla ruidosamente en vez
	 * de devolver {@code null}: un {@code organizationId} nulo que llega a un {@code WHERE} es
	 * exactamente como se filtra informacion entre tenants.
	 *
	 * @throws IllegalStateException si no hay contexto publicado
	 */
	RequestTenantContext require();

	/**
	 * Limpia el contexto al terminar el request.
	 *
	 * <p>Obligatorio y en {@code finally}: los hilos del contenedor se reutilizan, y un
	 * contexto que sobrevive al request se lo lleva puesto el siguiente usuario que caiga en
	 * ese hilo. Es la forma mas silenciosa de filtrar datos entre tenants.
	 */
	void clear();
}
