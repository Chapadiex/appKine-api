package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Plan de un financiador del catalogo global (M15, DP-43). Se copia a {@link PlanCobertura} cuando
 * un centro adopta su financiador; despues de eso la copia no lo vuelve a leer.
 */
@Entity
@Table(name = "plan_catalogo")
public class PlanCatalogo extends PlanBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "financiador_catalogo_id", nullable = false, updatable = false)
	private Long financiadorCatalogoId;

	protected PlanCatalogo() {
		// Requerido por JPA.
	}

	public PlanCatalogo(
			Long financiadorCatalogoId,
			String codigo,
			String nombre,
			String descripcion,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean requiereAutorizacion,
			boolean requiereCredencial,
			BigDecimal copago,
			String moneda) {

		super(codigo, nombre, descripcion, vigenciaDesde, vigenciaHasta, requiereAutorizacion,
				requiereCredencial, copago, moneda);
		this.financiadorCatalogoId = exigirNoNulo(
				financiadorCatalogoId, "Un plan del catalogo pertenece siempre a un financiador");
	}

	public Long getId() {
		return id;
	}

	public Long getFinanciadorCatalogoId() {
		return financiadorCatalogoId;
	}
}
