package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La serie de lotes de un financiador en una sede.
 *
 * <p><b>No es un cache de {@code COUNT(*)}.</b> Existe para asignar el numero de lote de forma
 * ATOMICA: un {@code SELECT MAX(numero) + 1} deja una ventana entre la lectura y la escritura, y
 * dos confirmaciones concurrentes del mismo financiador se llevan el mismo numero. El
 * {@code UPDATE ... SET ultimo_numero = ultimo_numero + 1} toma un lock exclusivo de fila y
 * serializa, sin leer nada antes. Mismo patron que {@link ComprobanteNumerador}.
 *
 * <p><b>La serie es por financiador</b> y no solo por sede: cada obra social recibe su propia
 * numeracion, que es como se numeran en la practica y lo que hace que el numero que el
 * administrativo dice por telefono signifique algo.
 *
 * <p>El unique {@code uk_presentacion_numero} de V56 es el respaldo, no el mecanismo: si el
 * numerador fallara, la base impide el numero repetido en vez de dejarlo pasar.
 */
@Entity
@Table(name = "presentacion_numerador")
public class PresentacionNumerador {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "financiador_id", nullable = false, updatable = false)
	private Long financiadorId;

	@Column(name = "ultimo_numero", nullable = false)
	private int ultimoNumero;

	protected PresentacionNumerador() {
		// Requerido por JPA.
	}

	public Long getId() {
		return id;
	}

	public int getUltimoNumero() {
		return ultimoNumero;
	}
}
