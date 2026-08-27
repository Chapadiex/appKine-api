package com.akine.offering.domain;

/**
 * Codigos del catalogo de la matriz de permisos que este modulo evalua.
 *
 * <p><b>Ninguno es nuevo.</b> El diseno de la etapa (§1 de
 * {@code docs/diseno/AKINE-02.06-servicio-y-oferta.md}) lo deja cerrado: "reusar los existentes,
 * sin codigos nuevos", mismo criterio que 02.04 y 02.05 — la matriz de permisos no la amplia una
 * etapa.
 *
 * <h2>Por que es literal y no el enum de {@code organization}</h2>
 *
 * <p>{@code organization.domain.PermissionCode} es privado de ese modulo y ArchUnit prohibe
 * importarlo ({@code modulos_solo_se_alcanzan_por_su_spi}). El {@code spi} lo expone a proposito
 * como {@code String}, asi que cada modulo consumidor declara los codigos que usa. Mismo patron
 * que {@code resource.domain.PermissionCodes}.
 *
 * <h2>Lo que NO esta aca, y por que</h2>
 *
 * <p>La matriz del contrato de esta etapa (§5 del diseno) tiene tres autorizaciones distintas y
 * solo una es un codigo de permiso evaluado por el evaluador de {@code organization}:
 *
 * <pre>
 *   Mutar una Oferta        consultorio:manage   -> SI es un codigo de permiso. Es {@link
 *                                                    #CONSULTORIO_MANAGE}.
 *   Leer una Oferta         pertenencia a la sede -> NO es un codigo: se autoriza comprobando
 *                                                    que la sede es del tenant del actor, igual
 *                                                    que las lecturas de espacio y de catalogo
 *                                                    antes de que existiera espacio:read.
 *   Mutar un Servicio       rol de plataforma     -> NO es un codigo de este catalogo: es un
 *                                                    chequeo de rol de plataforma vigente, el
 *                                                    mismo mecanismo que ya usan las rutas de
 *                                                    soporte de {@code organization}. Un Servicio
 *                                                    es global y no tiene sede que pertenezca a
 *                                                    nadie, asi que la formula del evaluador
 *                                                    "consultorio:manage con alcance de sede" no
 *                                                    aplica.
 * </pre>
 */
public final class PermissionCodes {

	/**
	 * Gestionar consultorio. Es el permiso que la matriz seccion 5 le asigna a la administracion
	 * de una sede, y una Oferta de servicio es configuracion comercial de esa sede — mismo
	 * razonamiento con el que 02.02 lo reuso para {@code Espacio} y 02.05 lo reuso, de forma
	 * interina, para el catalogo clinico contextual.
	 *
	 * <p>Se evalua con la sede como alcance: un {@code CONSULTORIO_ADMIN} lo tiene sobre la suya
	 * y un {@code ORG_ADMIN} sobre todas las de su organizacion, formula del evaluador sin ningun
	 * caso especial escrito para {@code offering}.
	 */
	public static final String CONSULTORIO_MANAGE = "consultorio:manage";

	private PermissionCodes() {
		// Catalogo de constantes.
	}
}
