package com.akine.organization.application;

import java.util.List;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.port.MembershipGrantRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.platform.spi.pedido.MemoriaDelPedido;

/**
 * Las tres lecturas de las que sale toda decision de acceso —rol de plataforma, memberships de
 * una cuenta en una organizacion y grants de una membership—, leidas <b>una vez por pedido</b>
 * (docs/carga.md §9).
 *
 * <p>Las comparten los filtros de seguridad ({@code OrganizationPlatformRoleDirectory},
 * {@code OrganizationMembershipDirectory}) y {@link PermissionEvaluatorService}: el filtro lee,
 * y la evaluacion del mismo pedido reutiliza esa lectura en vez de repetirla. No es una cache:
 * {@link MemoriaDelPedido} se descarta al terminar el pedido, no existe fuera de uno y no se usa
 * dentro de una transaccion de escritura, asi que la revalidacion contra la base en cada pedido
 * (ADR-0020, T-7) queda igual.
 *
 * <p>Se memorizan las <b>filas</b> y nunca la decision: vigencia y alcance se siguen evaluando con
 * el instante de cada llamada.
 *
 * <p><b>Lo memorizado son entidades JPA, y son mutables.</b> La lista es inmutable, sus elementos
 * no: son entidades leidas en otra transaccion (desprendidas). Se comparten entre todas las
 * llamadas del pedido, asi que <b>nadie las modifica</b>: estos metodos son solo para decidir
 * acceso, y ningun llamador las devuelve ni las guarda. Quien necesite cambiar una membership la
 * lee por su repositorio, dentro de su transaccion. Copiarlas a records exigiria reescribir
 * {@code MembershipSelection} y los predicados de vigencia del dominio sobre otro tipo.
 */
public final class LecturasDeAcceso {

	private record RolesDePlataforma(long accountId) {
	}

	private record Memberships(long organizationId, long accountId) {
	}

	private record Grants(long membershipId) {
	}

	private LecturasDeAcceso() {
		// Utilidad.
	}

	/** Roles de plataforma activos de la cuenta (la vigencia la evalua quien los usa). */
	public static List<PlatformRole> rolesDePlataforma(
			PlatformRoleRepositoryPort repositorio, long accountId) {
		return MemoriaDelPedido.memorizar(new RolesDePlataforma(accountId),
				() -> List.copyOf(repositorio.findAllByAccountIdAndActiveTrue(accountId)));
	}

	/** Memberships activas de la cuenta en la organizacion, en orden de id. */
	public static List<Membership> memberships(
			MembershipRepositoryPort repositorio, long organizationId, long accountId) {
		return MemoriaDelPedido.memorizar(new Memberships(organizationId, accountId),
				() -> List.copyOf(repositorio
						.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
								organizationId, accountId)));
	}

	/** Grants activos de la membership. */
	public static List<MembershipGrant> grants(
			MembershipGrantRepositoryPort repositorio, long membershipId) {
		return MemoriaDelPedido.memorizar(new Grants(membershipId),
				() -> List.copyOf(repositorio.findAllByMembershipIdAndActiveTrue(membershipId)));
	}
}
