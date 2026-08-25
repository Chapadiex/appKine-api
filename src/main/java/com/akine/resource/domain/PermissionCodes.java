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
 * filas al catalogo. {@code espacio:read} estuvo PROPUESTO en la seccion 10.1 mientras 02.02 lo
 * autorizaba por pertenencia; se aprobo el <b>25/08/2026</b> y desde entonces esta en la
 * seccion 5 y en la 6 como cualquier otro codigo del catalogo.
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
	 * Consultar el catalogo fisico y la disponibilidad de una sede. Es lo que exigen las tres
	 * lecturas —detalle, listado y disponibilidad—, y por eso un {@code PROFESIONAL} y un
	 * {@code ADMINISTRATIVO} pueden consultarlas y un {@code PACIENTE} no.
	 *
	 * <p>Se evalua con la sede como alcance, igual que {@link #CONSULTORIO_MANAGE}: la formula
	 * del evaluador le da al {@code ORG_ADMIN} todas las sedes de su organizacion y al
	 * {@code CONSULTORIO_ADMIN} la suya, sin ningun caso especial escrito.
	 *
	 * <p>Para el {@code PLATFORM_ADMIN} su alcance es {@code SOPORTE} (matriz seccion 6, con el
	 * criterio de la 9.7): sin {@code support_access} vigente da 403, y con el deja
	 * {@code SUPPORT_ACCESS_USED} en la auditoria del tenant leido.
	 */
	public static final String ESPACIO_READ = "espacio:read";

	private PermissionCodes() {
		// Catalogo de constantes.
	}
}
