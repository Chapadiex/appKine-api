package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La secuencia de comprobantes de una sede.
 *
 * <p><b>No es un cache de {@code COUNT(*)}.</b> Existe para poder asignar el numero de comprobante de forma
 * ATOMICA: un {@code SELECT MAX(numero) + 1} deja una ventana entre la lectura y la escritura, y
 * dos cobros concurrentes de la misma sede se llevan el mismo numero, y un comprobante repetido es un problema fiscal. El
 * {@code UPDATE ... SET ultimo_numero = ultimo_numero + 1} toma un lock exclusivo de fila y
 * serializa, sin leer nada antes.
 *
 * <p>El unique {@code (consultorio_id, numero_sesion)} sobre {@code sesion} es el respaldo, no
 * el mecanismo: si el numerador fallara, la base impide el numero repetido en vez de dejarlo pasar.
 */
@Entity
@Table(name = "comprobante_numerador")
public class ComprobanteNumerador {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "ultimo_numero", nullable = false)
	private int ultimoNumero;

	protected ComprobanteNumerador() {
		// Requerido por JPA.
	}

	public Long getId() {
		return id;
	}

	public int getUltimoNumero() {
		return ultimoNumero;
	}
}
