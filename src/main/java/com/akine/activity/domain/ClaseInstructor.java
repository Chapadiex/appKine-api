package com.akine.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un instructor ADICIONAL de una clase (AKINE-08.09b, DP-38).
 *
 * <p>El principal sigue en {@link ClaseProgramada#getProfesionalMembershipId()} y no se toca: esta
 * fila agrega los demas. Existe porque la decision de honorarios reparte el total de una clase
 * <b>en partes iguales entre sus instructores</b>, y hasta V114 una clase tenia uno solo.
 *
 * <p><b>Reemplazar la lista no borra</b>: la fila se marca con {@code removido_en} (regla maestra
 * 10). <b>El adicional no ocupa agenda</b>: la exclusion de turnos y clases sigue mirando solo al
 * principal (limite declarado en el diseno de 08.09b).
 */
@Entity
@Table(name = "clase_instructor")
public class ClaseInstructor {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "clase_id", nullable = false, updatable = false)
	private Long claseId;

	@Column(name = "membership_id", nullable = false, updatable = false)
	private Long membershipId;

	@Column(name = "agregado_en", nullable = false, updatable = false)
	private Instant agregadoEn;

	@Column(name = "agregado_por_cuenta_id", nullable = false, updatable = false)
	private Long agregadoPorCuentaId;

	@Column(name = "removido_en")
	private Instant removidoEn;

	@Column(name = "removido_por_cuenta_id")
	private Long removidoPorCuentaId;

	protected ClaseInstructor() {
		// Requerido por JPA.
	}

	public ClaseInstructor(
			long organizationId, long consultorioId, long claseId, long membershipId,
			Instant agregadoEn, long agregadoPorCuentaId) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.claseId = claseId;
		this.membershipId = membershipId;
		this.agregadoEn = agregadoEn;
		this.agregadoPorCuentaId = agregadoPorCuentaId;
	}

	public void remover(Instant cuando, long cuentaId) {
		this.removidoEn = cuando;
		this.removidoPorCuentaId = cuentaId;
	}

	public Long getId() {
		return id;
	}

	public Long getClaseId() {
		return claseId;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	public Instant getAgregadoEn() {
		return agregadoEn;
	}

	public Instant getRemovidoEn() {
		return removidoEn;
	}
}
