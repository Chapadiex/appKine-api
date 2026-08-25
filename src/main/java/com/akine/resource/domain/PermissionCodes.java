package com.akine.resource.domain;

/**
 * Codigos del catalogo de la matriz de permisos que este modulo evalua.
 *
 * <h2>Por que son literales y no el enum de {@code organization}</h2>
 *
 * <p>{@code organization.domain.PermissionCode} es privado de ese modulo y ArchUnit prohibe
 * importarlo ({@code modulos_solo_se_alcanzan_por_su_spi}). El {@code spi} lo expone a
 * proposito como {@code String} —lo dice el javadoc de {@code PermissionQuery}—, asi que cada
 * modulo consumidor declara los codigos que usa. Tenerlos en una constante con nombre y no
 * esparcidos como literales es lo que hace que un renombre del catalogo sea un solo grep.
 *
 * <p><b>Ningun codigo de aca es nuevo.</b> Los dos salen de la seccion 5 de
 * {@code docs/seguridad/matriz-permisos-minima.md}, que es vinculante: una etapa no le agrega
 * filas al catalogo. La propuesta de {@code espacio:read} —que M04 justificaria y que hoy no
 * existe— esta registrada en la seccion 9.8 de ese documento y <b>no se implementa aca</b>.
 */
public final class PermissionCodes {

	/**
	 * Gestionar consultorio. Es el permiso que la matriz seccion 5 le asigna a la
	 * administracion de una sede, y un espacio es configuracion de la sede.
	 *
	 * <p>Se evalua con la sede como alcance, de modo que un {@code CONSULTORIO_ADMIN} lo tiene
	 * sobre la suya y un {@code ORG_ADMIN} sobre todas las de su organizacion: es la formula
	 * del evaluador operando, sin ningun caso especial escrito.
	 */
	public static final String CONSULTORIO_MANAGE = "consultorio:manage";

	/**
	 * Lectura de datos del tenant. <b>Solo se usa para el {@code PLATFORM_ADMIN}</b>, que no
	 * tiene membership en ningun tenant (matriz seccion 1.3) y por lo tanto no puede pasar por
	 * la comprobacion de pertenencia que autoriza a todos los demas.
	 *
	 * <p>Su alcance es {@code SOPORTE} desde la enmienda 9.7 de la matriz: sin
	 * {@code support_access} vigente el resultado es 403, y con el queda
	 * {@code SUPPORT_ACCESS_USED} en la auditoria del tenant leido.
	 */
	public static final String TENANT_READ = "tenant:read";

	private PermissionCodes() {
		// Catalogo de constantes.
	}
}
