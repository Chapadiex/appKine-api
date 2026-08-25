package com.akine.resource.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Especialidad clinica del catalogo (RF-M06-001): Kinesiologia, Fisioterapia, Fonoaudiologia.
 *
 * <p>Global de plataforma cuando {@code organizationId} es {@code null}, propia de un tenant
 * cuando tiene valor. Todo lo demas —vigencia, baja logica, unicidad— esta en
 * {@link CatalogoConcepto} y no se repite aca.
 *
 * <p>No agrega ningun campo propio, y que la clase exista igual no es ceremonia: es la que le
 * da su tabla, su repositorio y su tipo. Sin ella, "especialidad" y "nomenclador" serian filas
 * de la misma tabla distinguidas por una cadena, y nada impediria colgar una practica de un
 * nomenclador.
 */
@Entity
@Table(name = "especialidad")
public class Especialidad extends CatalogoConcepto {

	protected Especialidad() {
		// Requerido por JPA.
	}

	public Especialidad(
			Long organizationId,
			String codigo,
			String name,
			String descripcion,
			Instant validFrom,
			Instant validUntil) {

		super(organizationId, codigo, name, descripcion, validFrom, validUntil);
	}
}
