package com.akine.contracting.domain;

/**
 * Codigos del catalogo de la matriz de permisos que evalua {@code contracting}.
 *
 * <p>{@code convenio:manage} ya estaba en el catalogo de la matriz §5 desde AKINE-01.03,
 * declarado para F3, y 03.03 <b>no agrego ningun codigo</b>: la matriz no la amplia una etapa,
 * mismo criterio que 02.04, 02.05, 02.06 y 02.07. {@code convenio:read} lo agrego despues la
 * decision U-07 (matriz §17.1).
 *
 * <p>Es literal y no el enum de {@code organization} por el motivo de siempre:
 * {@code organization.domain.PermissionCode} es privado de ese modulo y ArchUnit prohibe
 * importarlo ({@code modulos_solo_se_alcanzan_por_su_spi}). El {@code spi} lo expone a proposito
 * como {@code String}.
 *
 * <h2>Lo que SI cambia esta etapa, y no es reversible en silencio</h2>
 *
 * <p>{@code convenio:manage} existia <b>sin asignacion base para ningun rol</b>, asi que denegaba
 * siempre. 03.03 le da asignacion base a {@code ORG_ADMIN} (alcance ORGANIZACION) y a
 * {@code CONSULTORIO_ADMIN} (alcance CONSULTORIO), que es literalmente lo que la fila
 * "Administrar Convenios" de la matriz §2 les dice: "Si" a los dos, "No" al {@code PROFESIONAL},
 * al {@code ADMINISTRATIVO} y al {@code PACIENTE}. La enmienda queda escrita en
 * {@code docs/seguridad/matriz-permisos-minima.md} §13. Es el mismo movimiento que 03.01 hizo con
 * {@code paciente:manage}.
 *
 * <h2>Las dos formas de autorizar de este modulo</h2>
 *
 * <pre>
 *   LEER   convenio:read evaluado con la sede del contexto (U-07, desde la propuesta de
 *          permisos recomendados; mismo criterio que DP-22 para el padron).
 *   MUTAR  convenio:manage evaluado CON la sede del contexto.
 * </pre>
 *
 * <p><b>Por que se evalua con la sede aunque el financiador sea de la organizacion.</b> Es la
 * misma trampa que 03.01 documento para {@code paciente:manage}: el evaluador concede
 * {@code CONSULTORIO_ADMIN} solo cuando la consulta trae una sede, asi que evaluar sin ella
 * dejaria afuera justamente a quien administra su sede. Un {@code ORG_ADMIN} pasa igual, porque
 * su alcance ORGANIZACION cubre cualquier sede de su organizacion.
 *
 * <h2>Dos huecos conocidos que esta etapa NO cierra, declarados</h2>
 *
 * <ol>
 *   <li><s>No existe {@code convenio:read}.</s> <b>Cerrado por U-07</b> con el mismo criterio
 *       que DP-22 cerro el padron: {@code convenio:read} solo para el personal, y el rol
 *       {@code PACIENTE} recibe 403 en todas las lecturas del modulo. El alcance {@code OWN} —que
 *       un paciente vea su propio financiador— sigue para despues del MVP.</li>
 *   <li><b>{@code PLATFORM_ADMIN} no recibe asignacion base.</b> Su celda dice "Catalogo global",
 *       que la matriz §3 define como "solo sobre el catalogo de plataforma (financiadores/planes
 *       globales), nunca sobre convenios de un tenant". <b>Ese catalogo global no existe</b>:
 *       03.03 modela el financiador como dato de la organizacion. Darle el permiso hoy no
 *       cumpliria su celda, la <i>violaria</i> — lo dejaria administrar los financiadores de un
 *       tenant, que es precisamente lo que su celda excluye. Queda sin cumplirse hasta que exista
 *       la poblacion global, y el camino de migracion esta escrito en la cabecera de V41.</li>
 * </ol>
 */
public final class PermissionCodes {

	/**
	 * Administrar Convenios (matriz §5, F3). Cubre el catalogo de financiadores y planes de M15,
	 * que es el cimiento de M16 y M17.
	 *
	 * <p>Se evalua con la sede del contexto como alcance. Ver la cabecera de la clase.
	 */
	public static final String CONVENIO_MANAGE = "convenio:manage";

	/**
	 * Ver financiadores, planes, convenios y aranceles (U-07, matriz §17.1). Lo tiene todo el
	 * personal; {@code PACIENTE} y {@code PLATFORM_ADMIN} no.
	 *
	 * <p>Se evalua con la sede del contexto. Ver {@code AutorizacionDeCatalogo.exigirLectura}.
	 */
	public static final String CONVENIO_READ = "convenio:read";

	private PermissionCodes() {
		// Catalogo de constantes.
	}
}
