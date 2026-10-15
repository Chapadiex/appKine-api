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
 * <p>{@code catalogo:read} y {@code catalogo:manage} los agrego la decision U-08 (matriz §17.2).
 * Los demas salen de la seccion 5 de
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
	 * Consultar el catalogo clinico —especialidades, practicas, nomencladores, vigencias, tipos de
	 * medicion y solicitudes de alta—, global y propio (U-08, matriz §11.1 y §17.2).
	 *
	 * <p>Reemplaza la autorizacion interina por pertenencia de 02.05: lo tiene todo el personal y
	 * la plataforma (GLOBAL, solo el catalogo comun); el rol {@code PACIENTE} recibe 403. Se
	 * evalua con la sede del contexto.
	 */
	public static final String CATALOGO_READ = "catalogo:read";

	/**
	 * Administrar los conceptos del catalogo clinico y las solicitudes de alta (U-08, matriz
	 * §11.1 y §17.2).
	 *
	 * <p>Reemplaza el {@link #CONSULTORIO_MANAGE} interino de 02.05 con los mismos roles:
	 * {@code ORG_ADMIN} (organizacion) y {@code CONSULTORIO_ADMIN} (su sede) sobre conceptos
	 * contextuales; {@code PLATFORM_ADMIN} (GLOBAL) sobre los globales y la resolucion de
	 * solicitudes. Los codigos HTTP de rechazo no cambian.
	 */
	public static final String CATALOGO_MANAGE = "catalogo:manage";

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

	/**
	 * Listar colaboradores del alcance. Es lo que exigen las LECTURAS de disponibilidad
	 * profesional (M05, AKINE-02.04): la disponibilidad de un profesional es informacion de un
	 * colaborador, asi que el codigo aplica sin estirarlo.
	 *
	 * <p><b>No es un codigo nuevo.</b> Sale de la seccion 5 de la matriz, es de F1 y esta
	 * aprobado desde 01.03. La seccion 6 se lo da a {@code PROFESIONAL} y a
	 * {@code ADMINISTRATIVO} con alcance Consultorio, y de ahi sale —sin inventar nada— la
	 * politica confirmada de M05: <b>el profesional LEE su disponibilidad y no la edita</b>. Las
	 * mutaciones exigen {@link #CONSULTORIO_MANAGE}, que la misma seccion 6 le niega a esos dos
	 * roles.
	 *
	 * <p><b>Deliberadamente NO se usa {@link #ESPACIO_READ} para esto</b>, aunque su descripcion
	 * hable de "la disponibilidad de una sede": esa es la disponibilidad del ESPACIO FISICO
	 * (M04, RF-M04-003), que responde si un box esta en servicio. Son dos conceptos ortogonales
	 * y mezclarlos daria acceso al horario de las personas a quien solo pidio ver los boxes.
	 *
	 * <p>Su alcance para el {@code PLATFORM_ADMIN} es {@code SOPORTE} (matriz seccion 6, decision
	 * del 24/08/2026: el padron de personas de un centro ajeno es literalmente lo que la seccion
	 * 7 protege). Hoy eso no se ejerce por disponibilidad: las rutas de M05 cuelgan de la sede y
	 * no llevan {@code organizationId}, asi que un actor sin contexto de tenant no puede siquiera
	 * nombrar el tenant que quiere leer. Ver el javadoc de {@code DisponibilidadService}.
	 */
	public static final String COLABORADOR_READ = "colaborador:read";

	private PermissionCodes() {
		// Catalogo de constantes.
	}
}
