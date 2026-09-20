package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La secuencia de planes de un Caso Clinico.
 *
 * <p><b>No es un cache de {@code COUNT(*)}.</b> Existe para asignar el correlativo de forma
 * ATOMICA: un {@code SELECT MAX(numero) + 1} deja una ventana entre la lectura y la escritura, y
 * dos planes creados a la vez para el mismo caso se llevan el mismo numero. El
 * {@code UPDATE ... SET ultimo_numero = ultimo_numero + 1} toma un lock exclusivo de fila y
 * serializa, sin leer nada antes.
 *
 * <p>Y aca el numero no es solo presentacion: {@code plan_tratamiento.numero_plan} es el
 * discriminador de {@code activo_key}, la columna generada sobre la que se apoya el unique de "un
 * solo plan activo por caso". Un numero repetido no seria un detalle cosmetico — desarmaria ese
 * unique.
 *
 * <p>Gemelo de {@link CasoNumerador} y de {@code encounter.domain.SesionNumerador}, y no se reusa
 * ninguno porque son otra clave —y el ultimo, ademas, de otro modulo: ArchUnit rechaza importarlo y
 * compartirlo exigiria una capa global, que AGENT.md seccion 4 regla 3 prohibe.
 */
@Entity
@Table(name = "plan_numerador")
public class PlanNumerador {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "caso_clinico_id", nullable = false, updatable = false)
	private Long casoClinicoId;

	@Column(name = "ultimo_numero", nullable = false)
	private int ultimoNumero;

	protected PlanNumerador() {
		// Requerido por JPA.
	}

	public Long getId() {
		return id;
	}

	public int getUltimoNumero() {
		return ultimoNumero;
	}
}
