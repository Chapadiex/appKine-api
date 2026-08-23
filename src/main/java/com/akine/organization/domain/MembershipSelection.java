package com.akine.organization.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Criterios para elegir QUE membership aplica cuando una cuenta tiene varias en la misma
 * organizacion.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Hasta la migracion V10 la pregunta no tenia sentido: {@code uk_membership_org_account}
 * garantizaba a lo sumo una membership por (organizacion, cuenta), y todo el modulo consultaba
 * un {@code Optional}. Ese unique contradecia RN-M02-002 —la misma persona puede tener roles
 * distintos en consultorios distintos— y ademas escondia un 500 latente: en cuanto existiera la
 * segunda fila, el {@code Optional} se convertia en
 * {@code IncorrectResultSizeDataAccessException} en la resolucion de contexto, que corre en
 * CADA request.
 *
 * <p>V10 expande el unique. Con varias memberships posibles, "la membership de esta cuenta aca"
 * deja de tener una respuesta unica, y elegir mal es abrir o cerrar accesos indebidamente. Los
 * criterios viven aca, en el dominio y en un solo lugar, para que ningun llamador invente el
 * suyo.
 *
 * <h2>Los dos criterios</h2>
 *
 * <ol>
 *   <li><b>Lo mas especifico gana</b> ({@link #applicableAt}). Para decidir si alguien puede
 *       trabajar en UNA sede se toman las memberships vigentes cuyo alcance cubre esa sede y se
 *       prefiere la acotada a la sede sobre la de alcance organizacion. Una membership escrita
 *       para una sede concreta existe justamente para decir algo distinto de lo que dice la
 *       general; si ganara la general, escribirla no tendria efecto. El criterio es ademas
 *       determinista y no depende del orden en que la base devuelva las filas.</li>
 *   <li><b>Lo organizacional para lo organizacional</b> ({@link #organizationScoped}). Para
 *       decidir sobre la organizacion ENTERA —administrarla, cambiar su suscripcion— hace falta
 *       una membership cuyo alcance sea la organizacion entera. Una membership acotada a una
 *       sede no puede autorizar mas alla de esa sede: aceptarla seria una escalada de
 *       privilegio silenciosa el dia que 01.03 empiece a escribir memberships por sede.</li>
 * </ol>
 *
 * <p>Los dos criterios son <b>fail-closed</b>: ante la duda alcanzan menos, no mas. Cuando
 * 01.03 traiga la evaluacion fina de la matriz de permisos —y si trae jerarquia de roles— es el
 * momento de revisar si "lo mas especifico gana" debe convivir con "lo mas privilegiado gana";
 * mientras no exista esa jerarquia, elegir por privilegio seria inventarla aca.
 */
public final class MembershipSelection {

	/**
	 * Orden de preferencia: primero la acotada a una sede, despues la de alcance organizacion.
	 * El id desempata para que el resultado no dependa del orden de la base ni siquiera si
	 * alguna vez conviviesen dos filas del mismo alcance.
	 */
	private static final Comparator<Membership> MAS_ESPECIFICA_PRIMERO =
			Comparator.comparing(Membership::isOrganizationScoped)
					.thenComparing(Membership::getId, Comparator.nullsLast(Comparator.naturalOrder()));

	private MembershipSelection() {
		// Utilidad sin estado.
	}

	/**
	 * Membership que gobierna el trabajo de una cuenta en una sede concreta.
	 *
	 * <p>Filtra por vigencia ANTES de elegir: si la membership de la sede vencio y la de la
	 * organizacion sigue viva, la persona conserva el acceso general. Elegir primero y validar
	 * despues le cerraria la puerta por una fila que ya no dice nada.
	 *
	 * @param memberships memberships activas de la cuenta en la organizacion
	 * @param consultorioId sede sobre la que se decide
	 * @param at instante de la decision
	 */
	public static Optional<Membership> applicableAt(
			List<Membership> memberships, long consultorioId, Instant at) {

		return memberships.stream()
				.filter(m -> m.isValidAt(at))
				.filter(m -> m.covers(consultorioId))
				.min(MAS_ESPECIFICA_PRIMERO);
	}

	/**
	 * Membership de alcance ORGANIZACION, la unica que puede hablar por el tenant entero.
	 *
	 * <p><b>No filtra por vigencia</b>, a proposito: quien decide un permiso encadena
	 * {@link Membership#isValidAt(Instant)} y quien solo quiere explicar por que un acceso
	 * fallo necesita ver la fila vencida. Devolver ya filtrado obligaria a los dos usos a
	 * consultas distintas y haria imposible el segundo.
	 *
	 * @param memberships memberships activas de la cuenta en la organizacion
	 */
	public static Optional<Membership> organizationScoped(List<Membership> memberships) {
		return memberships.stream()
				.filter(Membership::isOrganizationScoped)
				.min(MAS_ESPECIFICA_PRIMERO);
	}
}
