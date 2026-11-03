package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fila-lock de la agenda de UNA PERSONA en una organizacion (V129). No guarda estado.
 *
 * <p>Existe porque los locks de agenda eran por sede ({@code agenda_sede},
 * {@code consultorio_calendario}) y una persona con membership en dos sedes tiene dos ids de
 * membership: dos escrituras en dos sedes no se veian. La fila es de la cuenta, que es lo que no
 * puede estar en dos lugares a la vez.
 *
 * <p>Se toma {@code FOR UPDATE} siempre DESPUES del lock de la sede. Ver
 * {@code resource.spi.BloqueoDeAgendaDeProfesional}.
 */
@Entity
@Table(name = "agenda_profesional")
public class AgendaProfesional {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	protected AgendaProfesional() {
		// Requerido por JPA.
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getAccountId() {
		return accountId;
	}
}
