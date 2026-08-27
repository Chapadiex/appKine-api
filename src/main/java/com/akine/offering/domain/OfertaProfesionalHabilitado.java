package com.akine.offering.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Que profesional puede prestar una Oferta (RF-M27-006, RF-M05-007).
 *
 * <h2>Se identifica por membership, no por cuenta</h2>
 *
 * <p>La misma persona puede ser profesional en un centro y administrativa en otro. Habilitar la
 * <b>cuenta</b> habilitaria a alguien que en esta organizacion no atiende, y ademas obligaria a
 * revalidar el rol en cada lectura. La {@code membership} ya es "esta persona, en esta
 * organizacion, con este rol y esta vigencia", que es exactamente lo que hay que habilitar. Es la
 * misma decision que tomo {@code V23} para la disponibilidad.
 *
 * <h2>Lista vacia significa TODOS</h2>
 *
 * <p>La ausencia de filas para una oferta NO significa que nadie pueda prestarla: significa que la
 * oferta no esta restringida. Si fuera al reves, toda oferta naceria inutilizable y el alta de
 * 02.06 —que a proposito no pide quince datos— quedaria rota. Quien decide eso es
 * {@code OfertaHabilitacionService}, no esta clase: aca no hay forma de expresar "no hay filas".
 */
@Entity
@Table(name = "oferta_profesional_habilitado")
public class OfertaProfesionalHabilitado extends Habilitacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "membership_id", nullable = false)
	private Long membershipId;

	protected OfertaProfesionalHabilitado() {
		// Requerido por JPA.
	}

	public OfertaProfesionalHabilitado(
			Long organizationId,
			Long consultorioId,
			Long ofertaId,
			Long membershipId,
			Instant validFrom,
			Instant validUntil) {

		super(organizationId, consultorioId, ofertaId, validFrom, validUntil);
		if (membershipId == null) {
			throw new IllegalArgumentException(
					"La habilitacion de un profesional exige su membership");
		}
		this.membershipId = membershipId;
	}

	public Long getId() {
		return id;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	@Override
	public Long getRecursoId() {
		return membershipId;
	}
}
