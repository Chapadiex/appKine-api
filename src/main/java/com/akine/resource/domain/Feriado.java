package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Feriado del calendario nacional (M05, RF-M05-004). GLOBAL: sin {@code organization_id}
 * (ADR-0022, V22).
 *
 * <h2>Por que esta tabla no tiene duenio</h2>
 *
 * <p>Un feriado nacional no es de nadie, y es un caso mas simple que el de
 * {@link CatalogoConcepto}: aca no conviven una poblacion global y una contextual en la misma
 * tabla —no hace falta el mecanismo de {@code owner_key} nullable— asi que alcanza con la
 * ausencia lisa y llana de la columna. La decision que SI es de cada sede —si cierra o no ese
 * dia— vive en {@link CalendarioSede}, no aca.
 *
 * <h2>Por que no hay baja logica ni bloqueo optimista</h2>
 *
 * <p>La migracion V22 no le puso {@code version} ni {@code active/deleted_at}: un feriado mal
 * cargado se corrige o se reemplaza por decreto, no se "da de baja" con motivo declarado como un
 * recurso de negocio. Esta entidad solo tiene id, los cuatro datos del feriado y las marcas de
 * {@link MarcaTemporal}.
 *
 * <p>{@code tipo} se mapea como texto simple y no como enum: la migracion (V22,
 * {@code ck_feriado_tipo}) es quien fija el conjunto valido
 * (INAMOVIBLE, TRASLADABLE, PUENTE, NO_LABORABLE, RELIGIOSO), y esta etapa no necesita
 * ramificar comportamiento por tipo —solo mostrarlo—.
 */
@Entity
@Table(name = "feriado")
public class Feriado extends MarcaTemporal {

	/** ISO 3166-1 alfa-2 por defecto: el pais de operacion inicial del producto. */
	public static final String PAIS_POR_DEFECTO = "AR";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pais", nullable = false, length = 2, updatable = false)
	private String pais;

	@Column(name = "fecha", nullable = false, updatable = false)
	private LocalDate fecha;

	@Column(name = "nombre", nullable = false, length = 160)
	private String nombre;

	@Column(name = "tipo", nullable = false, length = 32)
	private String tipo;

	protected Feriado() {
		// Requerido por JPA.
	}

	public Feriado(String pais, LocalDate fecha, String nombre, String tipo) {
		this.pais = exigirTexto(pais, "El pais del feriado es obligatorio");
		this.fecha = exigirNoNulo(fecha, "La fecha del feriado es obligatoria");
		this.nombre = exigirTexto(nombre, "El nombre del feriado es obligatorio");
		this.tipo = exigirTexto(tipo, "El tipo del feriado es obligatorio");
	}

	private static String exigirTexto(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
	}

	private static LocalDate exigirNoNulo(LocalDate valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	public Long getId() {
		return id;
	}

	public String getPais() {
		return pais;
	}

	public LocalDate getFecha() {
		return fecha;
	}

	public String getNombre() {
		return nombre;
	}

	public String getTipo() {
		return tipo;
	}
}
