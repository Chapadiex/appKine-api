package com.akine.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un trozo de texto de un libro, con su vector.
 *
 * <p>El dominio maneja el vector como bytes (float32 little-endian), sin {@code float}/{@code double};
 * en la base es {@code vector(768)} de pgvector, con los mismos float32. Los fragmentos se reemplazan en bloque, no se editan.
 */
@Entity
@Table(name = "libro_fragmento")
public class LibroFragmento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "libro_id", nullable = false, updatable = false)
	private Long libroId;

	@Column(name = "orden", nullable = false)
	private int orden;

	@Column(name = "pagina_desde", nullable = false)
	private int paginaDesde;

	@Column(name = "pagina_hasta", nullable = false)
	private int paginaHasta;

	@Column(name = "texto", nullable = false, columnDefinition = "text")
	private String texto;

	@JdbcTypeCode(SqlTypes.VECTOR)
	@Array(length = 768)
	@Column(name = "vector")
	private float[] vector;

	@Column(name = "modelo_embeddings", length = 100)
	private String modeloEmbeddings;

	@Column(name = "sospechoso", nullable = false)
	private boolean sospechoso;

	protected LibroFragmento() {
	}

	public LibroFragmento(long organizationId, long libroId, int orden, int paginaDesde,
			int paginaHasta, String texto, boolean sospechoso) {
		this.organizationId = organizationId;
		this.libroId = libroId;
		this.orden = orden;
		this.paginaDesde = paginaDesde;
		this.paginaHasta = paginaHasta;
		this.texto = texto;
		this.sospechoso = sospechoso;
	}

	/** Fragmento con su vector; {@code vector} y {@code modelo} van juntos o los dos nulos. */
	public LibroFragmento(long organizationId, long libroId, int orden, int paginaDesde,
			int paginaHasta, String texto, boolean sospechoso, byte[] vector, String modelo) {
		this(organizationId, libroId, orden, paginaDesde, paginaHasta, texto, sospechoso);
		if ((vector == null) != (modelo == null)) {
			throw new IllegalArgumentException("vector y modelo van juntos");
		}
		this.vector = vector == null ? null : Vectores.deBytes(vector);
		this.modeloEmbeddings = modelo;
	}

	public Long getId() { return id; }
	public Long getOrganizationId() { return organizationId; }
	public Long getLibroId() { return libroId; }
	public int getOrden() { return orden; }
	public int getPaginaDesde() { return paginaDesde; }
	public int getPaginaHasta() { return paginaHasta; }
	public String getTexto() { return texto; }
	public byte[] getVector() { return vector == null ? null : Vectores.aBytes(vector); }
	public String getModeloEmbeddings() { return modeloEmbeddings; }
	public boolean isSospechoso() { return sospechoso; }
}
