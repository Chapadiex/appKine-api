package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Los datos y las reglas de un financiador que comparten la fila del centro ({@link Financiador})
 * y la del catalogo global de la plataforma ({@link FinanciadorCatalogo}, DP-43).
 *
 * <p>Existe para que adoptar sea COPIAR campos que significan lo mismo con las mismas reglas: el
 * CUIT se normaliza igual, el codigo es igual de inmutable y la baja exige el mismo motivo en las
 * dos tablas. Lo que las distingue —el tenant y el puntero de origen— vive en cada subclase. Las
 * reglas en si estan desarrolladas en el javadoc de {@link Financiador}.
 */
@MappedSuperclass
public abstract class FinanciadorBase extends MarcaTemporal {

	/**
	 * Clave estable. {@code updatable = false} a proposito: es lo que las referencias guardan, y
	 * mutarlo haria que una referencia vieja pase a significar otra cosa.
	 */
	@Column(name = "codigo", nullable = false, updatable = false, length = 64)
	private String codigo;

	@Column(name = "nombre", nullable = false, length = 160)
	private String nombre;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 24)
	private TipoFinanciador tipo;

	/** Identidad fiscal ya normalizada a 11 digitos. {@code null} es un estado real. */
	@Column(name = "cuit", length = 13)
	private String cuit;

	@Column(name = "email_contacto", length = 254)
	private String emailContacto;

	@Column(name = "telefono_contacto", length = 40)
	private String telefonoContacto;

	@Column(name = "observaciones", length = 500)
	private String observaciones;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected FinanciadorBase() {
		// Requerido por JPA.
	}

	protected FinanciadorBase(
			String codigo,
			String nombre,
			TipoFinanciador tipo,
			String cuit,
			String emailContacto,
			String telefonoContacto,
			String observaciones) {

		this.codigo = exigirTexto(codigo, "El codigo del financiador es obligatorio");
		this.nombre = exigirTexto(nombre, "El nombre del financiador es obligatorio");
		this.tipo = exigirNoNulo(tipo, "El tipo de financiador es obligatorio");
		this.cuit = normalizarCuit(cuit);
		this.emailContacto = vacioEsNulo(emailContacto);
		this.telefonoContacto = vacioEsNulo(telefonoContacto);
		this.observaciones = vacioEsNulo(observaciones);
		this.active = true;
	}

	/**
	 * Normaliza un CUIT a 11 digitos, o {@code null}.
	 *
	 * <p>Se guarda sin guiones para que el unique de CUIT sirva de algo: {@code 30-71234567-8} y
	 * {@code 30712345678} son el mismo contribuyente. Un valor que no queda en 11 digitos <b>se
	 * rechaza</b>: el CHECK de la base lo rechazaria igual, y hacerlo aca produce un 400 que nombra
	 * el campo en vez de un 500 al cerrar la transaccion.
	 */
	public static String normalizarCuit(String valor) {
		if (valor == null || valor.isBlank()) {
			return null;
		}
		String soloDigitos = valor.replaceAll("[^0-9]", "");
		if (soloDigitos.length() != 11) {
			throw new IllegalArgumentException(
					"El CUIT tiene que tener 11 digitos: se recibieron " + soloDigitos.length());
		}
		return soloDigitos;
	}

	/**
	 * Edicion parcial: cada valor {@code null} deja el campo como estaba. Para borrar un campo
	 * opcional se manda cadena vacia. El {@link #codigo} no esta: es {@code updatable = false}.
	 */
	public void updateDatos(
			String nombre,
			TipoFinanciador tipo,
			String cuit,
			String emailContacto,
			String telefonoContacto,
			String observaciones) {

		if (nombre != null) {
			this.nombre = exigirTexto(nombre, "El nombre del financiador es obligatorio");
		}
		if (tipo != null) {
			this.tipo = tipo;
		}
		if (cuit != null) {
			this.cuit = normalizarCuit(cuit);
		}
		if (emailContacto != null) {
			this.emailContacto = vacioEsNulo(emailContacto);
		}
		if (telefonoContacto != null) {
			this.telefonoContacto = vacioEsNulo(telefonoContacto);
		}
		if (observaciones != null) {
			this.observaciones = vacioEsNulo(observaciones);
		}
	}

	/** Baja logica con motivo declarado (RF-M15-003). No borra nada y no cascadea. */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un financiador exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando admite planes nuevos y selecciones nuevas. */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	protected static String exigirTexto(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
	}

	protected static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	protected static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}

	public String getCodigo() {
		return codigo;
	}

	public String getNombre() {
		return nombre;
	}

	public TipoFinanciador getTipo() {
		return tipo;
	}

	public String getCuit() {
		return cuit;
	}

	public String getEmailContacto() {
		return emailContacto;
	}

	public String getTelefonoContacto() {
		return telefonoContacto;
	}

	public String getObservaciones() {
		return observaciones;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public String getDeactivationReason() {
		return deactivationReason;
	}

	public long getVersion() {
		return version;
	}
}
