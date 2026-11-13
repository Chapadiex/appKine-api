package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Locale;

/**
 * Identidad visual y datos de contacto del tenant: los colores institucionales de cada tema y
 * lo que se muestra en el pie de pagina.
 *
 * <p>Un color {@code null} significa "el de AKINE": la marca es opcional campo por campo, y un
 * centro que solo cargo su telefono sigue viendo la paleta por defecto.
 *
 * <p>Los colores se guardan como {@code #rrggbb} en minusculas. El contraste NO se valida aca:
 * la decision del producto es avisar en pantalla y dejar guardar, y el texto sobre el color lo
 * elige el cliente (blanco o negro, el que mejor se lea).
 */
@Entity
@Table(name = "organization_marca")
public class OrganizationMarca extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "color_principal_claro", length = 7)
	private String colorPrincipalClaro;

	@Column(name = "color_acento_claro", length = 7)
	private String colorAcentoClaro;

	@Column(name = "color_principal_oscuro", length = 7)
	private String colorPrincipalOscuro;

	@Column(name = "color_acento_oscuro", length = 7)
	private String colorAcentoOscuro;

	@Column(name = "color_barra_lateral_claro", length = 7)
	private String colorBarraLateralClaro;

	@Column(name = "color_cabecera_claro", length = 7)
	private String colorCabeceraClaro;

	@Column(name = "color_fondo_claro", length = 7)
	private String colorFondoClaro;

	@Column(name = "color_tarjeta_claro", length = 7)
	private String colorTarjetaClaro;

	@Column(name = "color_pie_claro", length = 7)
	private String colorPieClaro;

	@Column(name = "color_boton_secundario_claro", length = 7)
	private String colorBotonSecundarioClaro;

	@Column(name = "color_barra_lateral_oscuro", length = 7)
	private String colorBarraLateralOscuro;

	@Column(name = "color_cabecera_oscuro", length = 7)
	private String colorCabeceraOscuro;

	@Column(name = "color_fondo_oscuro", length = 7)
	private String colorFondoOscuro;

	@Column(name = "color_tarjeta_oscuro", length = 7)
	private String colorTarjetaOscuro;

	@Column(name = "color_pie_oscuro", length = 7)
	private String colorPieOscuro;

	@Column(name = "color_boton_secundario_oscuro", length = 7)
	private String colorBotonSecundarioOscuro;

	@Column(name = "telefono", length = 40)
	private String telefono;

	@Column(name = "email", length = 254)
	private String email;

	@Column(name = "sitio_web", length = 200)
	private String sitioWeb;

	@Column(name = "direccion", length = 240)
	private String direccion;

	@Column(name = "leyenda", length = 200)
	private String leyenda;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected OrganizationMarca() {
		// Requerido por JPA.
	}

	/** Reemplazo completo: lo que llega vacio se borra. */
	public void reemplazar(
			String colorPrincipalClaro, String colorAcentoClaro,
			String colorPrincipalOscuro, String colorAcentoOscuro,
			ColoresDeInterfaz interfazClaro, ColoresDeInterfaz interfazOscuro,
			String telefono, String email, String sitioWeb, String direccion, String leyenda) {
		this.colorPrincipalClaro = color(colorPrincipalClaro);
		this.colorAcentoClaro = color(colorAcentoClaro);
		this.colorPrincipalOscuro = color(colorPrincipalOscuro);
		this.colorAcentoOscuro = color(colorAcentoOscuro);
		ColoresDeInterfaz claro = ColoresDeInterfaz.normalizar(interfazClaro);
		this.colorBarraLateralClaro = claro.barraLateral();
		this.colorCabeceraClaro = claro.cabecera();
		this.colorFondoClaro = claro.fondo();
		this.colorTarjetaClaro = claro.tarjeta();
		this.colorPieClaro = claro.pie();
		this.colorBotonSecundarioClaro = claro.botonSecundario();
		ColoresDeInterfaz oscuro = ColoresDeInterfaz.normalizar(interfazOscuro);
		this.colorBarraLateralOscuro = oscuro.barraLateral();
		this.colorCabeceraOscuro = oscuro.cabecera();
		this.colorFondoOscuro = oscuro.fondo();
		this.colorTarjetaOscuro = oscuro.tarjeta();
		this.colorPieOscuro = oscuro.pie();
		this.colorBotonSecundarioOscuro = oscuro.botonSecundario();
		this.telefono = texto(telefono);
		this.email = texto(email);
		this.sitioWeb = texto(sitioWeb);
		this.direccion = texto(direccion);
		this.leyenda = texto(leyenda);
	}

	private static String color(String valor) {
		String limpio = texto(valor);
		return limpio == null ? null : limpio.toLowerCase(Locale.ROOT);
	}

	private static String texto(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public String getColorPrincipalClaro() {
		return colorPrincipalClaro;
	}

	public String getColorAcentoClaro() {
		return colorAcentoClaro;
	}

	public String getColorPrincipalOscuro() {
		return colorPrincipalOscuro;
	}

	public String getColorAcentoOscuro() {
		return colorAcentoOscuro;
	}

	public ColoresDeInterfaz getInterfazClaro() {
		return new ColoresDeInterfaz(
				colorBarraLateralClaro, colorCabeceraClaro, colorFondoClaro, colorTarjetaClaro,
				colorPieClaro, colorBotonSecundarioClaro);
	}

	public ColoresDeInterfaz getInterfazOscuro() {
		return new ColoresDeInterfaz(
				colorBarraLateralOscuro, colorCabeceraOscuro, colorFondoOscuro, colorTarjetaOscuro,
				colorPieOscuro, colorBotonSecundarioOscuro);
	}

	public String getTelefono() {
		return telefono;
	}

	public String getEmail() {
		return email;
	}

	public String getSitioWeb() {
		return sitioWeb;
	}

	public String getDireccion() {
		return direccion;
	}

	public String getLeyenda() {
		return leyenda;
	}

	public long getVersion() {
		return version;
	}
}
