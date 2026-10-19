package com.akine.contracting.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Financiador del catalogo global de la plataforma (M15, DP-43).
 *
 * <p><b>No es un financiador de nadie.</b> Lo administra el {@code PLATFORM_ADMIN} y ningun centro
 * lo referencia: un centro lo ADOPTA y lo que queda es una copia propia en {@link Financiador} con
 * {@code catalogo_id} apuntando aca. Por eso la tabla no lleva {@code organization_id} ni
 * {@code owner_key}: tiene una sola poblacion, igual que {@code servicio} (ADR-0023). Su hermana con
 * tenant —la que pide la condicion 2 del ADR— es {@code financiador}.
 *
 * <p>Editar o dar de baja esta fila <b>no propaga</b> a ninguna copia: las copias son de los
 * centros. Lo unico que cambia es lo que se ofrece para adoptar de aca en adelante.
 */
@Entity
@Table(name = "financiador_catalogo")
public class FinanciadorCatalogo extends FinanciadorBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	protected FinanciadorCatalogo() {
		// Requerido por JPA.
	}

	public FinanciadorCatalogo(
			String codigo,
			String nombre,
			TipoFinanciador tipo,
			String cuit,
			String emailContacto,
			String telefonoContacto,
			String observaciones) {

		super(codigo, nombre, tipo, cuit, emailContacto, telefonoContacto, observaciones);
	}

	public Long getId() {
		return id;
	}
}
