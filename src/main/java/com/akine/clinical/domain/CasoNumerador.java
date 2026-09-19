package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La secuencia de casos de una historia clinica.
 *
 * <p><b>No es un cache de {@code COUNT(*)}.</b> Existe para poder asignar el correlativo de forma
 * ATOMICA: un {@code SELECT MAX(numero) + 1} deja una ventana entre la lectura y la escritura, y
 * dos administrativos abriendo un caso para el mismo paciente al mismo tiempo desde dos sedes
 * —el caso que rompe el diseño, challenge seccion 8— se llevan el mismo numero. El
 * {@code UPDATE ... SET ultimo_numero = ultimo_numero + 1} toma un lock exclusivo de fila y
 * serializa, sin leer nada antes.
 *
 * <p>El unique {@code (organization_id, historia_clinica_id, numero_caso)} sobre
 * {@code caso_clinico} es el respaldo, no el mecanismo: si el numerador fallara, la base impide el
 * numero repetido en vez de dejarlo pasar.
 *
 * <p>Gemelo de {@code encounter.domain.SesionNumerador}, y no se reusa porque es de otro modulo:
 * ArchUnit rechaza importarlo y compartirlo exigiria una capa global, que AGENT.md seccion 4
 * regla 3 prohibe.
 */
@Entity
@Table(name = "caso_numerador")
public class CasoNumerador {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	@Column(name = "ultimo_numero", nullable = false)
	private int ultimoNumero;

	protected CasoNumerador() {
		// Requerido por JPA.
	}

	public Long getId() {
		return id;
	}

	public int getUltimoNumero() {
		return ultimoNumero;
	}
}
