package com.akine.organization.infrastructure.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akine.organization.application.LecturasDeAcceso;
import com.akine.organization.domain.LecturaDeContexto;
import com.akine.organization.domain.Membership;
import com.akine.organization.domain.MembershipSelection;
import com.akine.organization.domain.OperationalStatus;
import com.akine.organization.domain.Subscription;
import com.akine.organization.infrastructure.MembershipRepository;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.TenantMembership;
import com.akine.platform.spi.tenant.TenantOperationalStatus;

/**
 * Implementacion del puerto {@link MembershipDirectory} de {@code platform}.
 *
 * <p><b>La flecha va en un solo sentido.</b> {@code organization} depende de
 * {@code platform.spi}; {@code platform} no conoce esta clase ni ninguna otra de
 * {@code organization}: recibe la interfaz por inyeccion. Ese es el unico motivo por el que
 * {@code sin_ciclos_entre_modulos} no detecta un ciclo, y por eso este adaptador nunca devuelve
 * entities: solo el record {@link TenantMembership} del {@code spi}.
 *
 * <p>Es el codigo mas caliente del sistema —se ejecuta en cada request— y aun asi no tiene
 * cache, por decision T-7: la ventana de revocacion tiene que ser cero. La consulta va por
 * {@code ix_membership_account} y las tres uniones por clave primaria o unique.
 */
@Component
public class OrganizationMembershipDirectory implements MembershipDirectory {

	private final MembershipRepository membershipRepository;

	public OrganizationMembershipDirectory(MembershipRepository membershipRepository) {
		this.membershipRepository = membershipRepository;
	}

	/**
	 * Resuelve el contexto contra la base, ahora.
	 *
	 * <p>El orden de las comprobaciones esta elegido para que ninguna pueda ver datos de otro
	 * tenant y para que todo rechazo sea indistinguible:
	 * <ol>
	 *   <li>membership activa de esa cuenta EN esa organizacion;</li>
	 *   <li>vigencia temporal, evaluada por el dominio ({@code Membership.isValidAt});</li>
	 *   <li>alcance: una membership con {@code consultorio_id} propio no habilita otras sedes;</li>
	 *   <li>consultorio activo y <b>de esa organizacion</b> —se busca por (id, organizationId),
	 *       nunca por id pelado: un id ajeno inyectado en el token no puede resolver;</li>
	 *   <li>organizacion vigente y suscripcion, para calcular el estado operativo.</li>
	 * </ol>
	 *
	 * <p><b>Una sola sentencia</b> trae todo lo que deciden esos cinco pasos
	 * ({@link MembershipRepository#leerContextoDeAcceso}); hasta docs/carga.md §13 eran cuatro
	 * lecturas dentro de una transaccion de solo lectura, que estaba para verlas en una misma foto.
	 * Una sentencia es una foto por si misma, asi que la transaccion sobra: {@code SUPPORTS}, como
	 * la lectura del rol de plataforma (§9). Dentro de una transaccion del llamador se une a ella,
	 * igual que antes.
	 */
	@Override
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public Optional<TenantMembership> resolveMembership(
			long accountId, long organizationId, long consultorioId, Instant at) {

		List<LecturaDeContexto> filas =
				LecturasDeAcceso.contexto(membershipRepository, organizationId, accountId, consultorioId);

		// Una cuenta puede tener VARIAS memberships en la misma organizacion desde V10 (una por
		// sede, mas la de alcance organizacion): RN-M02-002 exige que la misma persona pueda
		// tener roles distintos en consultorios distintos. Este metodo decide un acceso, asi
		// que la eleccion entre ellas es una decision de autorizacion y esta centralizada en
		// MembershipSelection: se toman las VIGENTES cuyo alcance cubre la sede pedida y gana
		// la mas especifica —la acotada a esa sede— sobre la de alcance organizacion. La
		// membership de una sede existe para decir algo distinto de la general; si ganara la
		// general, escribirla no tendria ningun efecto.
		//
		// Vigencia (activa pero vencida no habilita), alcance (consultorio_id NULL habilita
		// cualquier sede del tenant, con valor solo esa) y baja logica (ya filtrada en la
		// consulta) estan dentro de applicableAt. Vacio significa cualquiera de los tres, sin
		// distinguir.
		Optional<Membership> encontrada = MembershipSelection.applicableAt(
				filas.stream().map(LecturaDeContexto::membership).toList(), consultorioId, at);
		if (encontrada.isEmpty()) {
			return Optional.empty();
		}

		Membership membership = encontrada.get();
		// Sede, organizacion y suscripcion son las mismas en todas las filas.
		LecturaDeContexto contexto = filas.get(0);

		if (contexto.consultorioId() == null) {
			// Inexistente, dado de baja, o de OTRA organizacion. El llamador responde 404 sin
			// distinguir: revelar cual de los tres es filtraria la existencia de datos ajenos.
			return Optional.empty();
		}

		// La organizacion VIGENTE. Una dada de baja logica no se resuelve como contexto de
		// trabajo, y para quien pregunta es indistinguible de una inexistente: las dos terminan
		// en el mismo 404. Por eso no hace falta traer las inactivas para calcular
		// TenantOperationalStatus.BAJA —ese estado existe en el spi para que el filtro sepa
		// rechazarlo si algun dia otro adaptador lo produce, pero este camino ya corto antes.
		if (contexto.organizacionId() == null) {
			return Optional.empty();
		}

		if (contexto.estadoSuscripcion() == null) {
			// Toda organizacion nace con suscripcion en la misma transaccion (ADR-0008). Si no
			// la tiene, el tenant esta roto: se niega el acceso en vez de asumir un estado.
			return Optional.empty();
		}

		TenantOperationalStatus estado =
				traducir(Subscription.operationalStatus(contexto.estadoSuscripcion(), true));

		return Optional.of(new TenantMembership(
				membership.getId(), membership.getRoleCode().name(), estado));
	}

	/**
	 * Traduce el estado operativo del dominio al enum del {@code spi}.
	 *
	 * <p>La traduccion es explicita y el {@code switch} es exhaustivo: si {@code organization}
	 * agrega un estado y nadie lo mapea, esto deja de compilar. Es preferible a un
	 * {@code valueOf} por nombre, que fallaria recien en produccion y sobre una decision de
	 * seguridad.
	 */
	private TenantOperationalStatus traducir(OperationalStatus estado) {
		return switch (estado) {
			case ACTIVA -> TenantOperationalStatus.ACTIVA;
			case SUSPENDIDA -> TenantOperationalStatus.SUSPENDIDA;
			case CANCELADA -> TenantOperationalStatus.CANCELADA;
			case BAJA -> TenantOperationalStatus.BAJA;
		};
	}
}
