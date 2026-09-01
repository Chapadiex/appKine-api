package com.akine.encounter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La secuencia de sesiones de una historia clinica.
 *
 * <p><b>No es un cache de {@code COUNT(*)}.</b> Existe para poder asignar el correlativo de forma
 * ATOMICA: un {@code SELECT MAX(numero) + 1} deja una ventana entre la lectura y la escritura, y
 * dos cierres concurrentes de dos sesiones del mismo paciente se llevan el mismo numero. El
 * {@code UPDATE ... SET ultimo_numero = ultimo_numero + 1} toma un lock exclusivo de fila y
 * serializa, sin leer nada antes.
 *
 * <p>El unique {@code (historia_clinica_id, numero_sesion)} sobre {@code sesion} es el respaldo, no
 * el mecanismo: si el numerador fallara, la base impide el numero repetido en vez de dejarlo pasar.
 */
@Entity
@Table(name = "sesion_numerador")
public class SesionNumerador {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	@Column(name = "ultimo_numero", nullable = false)
	private int ultimoNumero;

	protected SesionNumerador() {
		// Requerido por JPA.
	}

	public Long getId() {
		return id;
	}

	public int getUltimoNumero() {
		return ultimoNumero;
	}
}
