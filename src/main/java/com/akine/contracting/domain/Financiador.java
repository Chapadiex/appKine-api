package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Obra social, prepaga u otro pagador externo con el que trabaja una organizacion (M15).
 *
 * <h2>Es de la organizacion, no de la sede y no global</h2>
 *
 * <p>{@code organization_id} es {@code NOT NULL}: la obra social con la que trabaja un centro,
 * con su codigo interno y su contacto administrativo, es dato de ese centro. Dos organizaciones
 * pueden tener "OSDE" con datos distintos y ninguna ve la del otro. <b>No existe el financiador
 * global de plataforma en 03.03</b>, y la consecuencia de esa decision —la celda "Catalogo
 * global" de la matriz de permisos queda sin cumplirse— esta declarada en la cabecera de V41 y
 * en {@link PermissionCodes}. <b>Desde DP-43 (V117) existe un catalogo global</b>, pero no cambia
 * esto: el centro lo ADOPTA y lo que adopta es una copia en esta tabla, con {@code catalogo_id}
 * como puntero informativo. Ver {@link FinanciadorCatalogo}.
 *
 * <h2>Que hace estable a una referencia historica</h2>
 *
 * <p>La garantia que 03.04 y 03.05 heredan —"una cobertura firmada ayer no cambia porque hoy
 * alguien edito el financiador"— tiene dos mitades y esta clase aporta la primera:
 *
 * <ol>
 *   <li><b>{@link #codigo} es inmutable</b> ({@code updatable = false}). Renombrar es cambiar
 *       {@link #nombre}; el codigo es lo que las referencias guardan, y mutarlo haria que una
 *       referencia vieja pase a significar otra cosa sin que nadie lo haya pedido. Mismo
 *       criterio, y mismo motivo, que {@code Servicio.codigo} en M27.</li>
 *   <li><b>No hay borrado fisico</b> (RN-M15-003): la baja deja {@link #active} en
 *       {@code false} y la fila sigue resolviendo con su nombre y su codigo.</li>
 * </ol>
 *
 * <p>La segunda mitad <b>no vive aca y no puede vivir aca</b>: es que el consumidor COPIE la
 * referencia a sus propias columnas en vez de leer esta fila cada vez que muestra una cobertura.
 * Eso lo entrega {@code contracting.spi.ReferenciaDeCobertura}, que es un record de copia y no un
 * puntero, exactamente como {@code obligacion} congela el precio de la oferta en AKINE-07.01.
 *
 * <h2>Ciclo de vida: baja logica con motivo, sin cascada</h2>
 *
 * <p>Dar de baja un financiador <b>no da de baja sus planes</b> ni toca ninguna cobertura ya
 * firmada. Lo unico que se impide es crear planes NUEVOS bajo el, y que sus planes se ofrezcan
 * para selecciones nuevas — y esa segunda parte la decide quien lee, combinando el estado del
 * plan con el de su financiador. La garantia es estructural: esta clase no tiene ninguna
 * relacion JPA hacia sus planes, asi que no hay por donde cascadear aunque alguien quisiera.
 */
@Entity
@Table(name = "financiador")
public class Financiador extends FinanciadorBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Financiador del catalogo global del que se adopto esta copia (DP-43), o {@code null} si el
	 * centro lo cargo a mano. INFORMATIVO: ninguna lectura lo sigue para resolver datos, y editar
	 * el catalogo no toca la copia.
	 */
	@Column(name = "catalogo_id", updatable = false)
	private Long catalogoId;

	protected Financiador() {
		// Requerido por JPA.
	}

	public Financiador(
			Long organizationId,
			String codigo,
			String nombre,
			TipoFinanciador tipo,
			String cuit,
			String emailContacto,
			String telefonoContacto,
			String observaciones) {

		super(codigo, nombre, tipo, cuit, emailContacto, telefonoContacto, observaciones);
		this.organizationId =
				exigirNoNulo(organizationId, "El financiador pertenece siempre a una organizacion");
	}

	/**
	 * La copia del centro de un financiador del catalogo global (DP-43): los mismos datos, del
	 * tenant que adopta, con el puntero de origen. Desde aca es una fila del centro como cualquier
	 * otra.
	 */
	public static Financiador adoptado(Long organizationId, FinanciadorCatalogo origen) {
		Financiador copia = new Financiador(
				organizationId,
				origen.getCodigo(),
				origen.getNombre(),
				origen.getTipo(),
				origen.getCuit(),
				origen.getEmailContacto(),
				origen.getTelefonoContacto(),
				origen.getObservaciones());
		copia.catalogoId = exigirNoNulo(origen.getId(), "El financiador del catalogo no tiene id");
		return copia;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getCatalogoId() {
		return catalogoId;
	}
}
