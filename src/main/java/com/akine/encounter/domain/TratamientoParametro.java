package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.ParametroInvalidoException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un parametro <b>tipado</b> de una intervencion realizada.
 *
 * <p>{@code plan_sesiones.txt} seccion 10.4 pide campos distintos por tipo de practica
 * —intensidad y frecuencia en electroterapia; series, repeticiones y carga en ejercicio
 * terapeutico— y el plan de implementacion nombra el caso borde de frente: un parametro sin tipo
 * <b>se rechaza</b>.
 *
 * <h2>Por que es una entity y no una clave de un {@code json}</h2>
 *
 * <p>Ver {@link TipoDatoParametro}. En resumen: un {@code json} no puede rechazar nada, y MySQL lo
 * normaliza al guardarlo.
 *
 * <h2>No tiene identidad hacia afuera, y por eso se borra fisicamente</h2>
 *
 * <p>Es la unica excepcion a la baja logica en esta etapa y esta declarada en V55 y en el
 * challenge: un parametro es un <b>atributo</b> del tratamiento, como lo seria una columna. Nadie
 * lo referencia, ninguna auditoria lo nombra, y solo se edita mientras la sesion esta en borrador
 * —una sesion cerrada no se edita, se enmienda, y eso es 06.06—.
 *
 * <p><b>Si 06.06 habilita editar un tratamiento de una sesion cerrada, esta excepcion deja de
 * valer y hay que versionarlos.</b>
 *
 * <h2>Lo que esta clase NO valida</h2>
 *
 * <p><b>Cuales parametros son obligatorios para cada practica.</b> No existe ninguna configuracion
 * que lo declare: M06 tiene practicas, no esquemas de parametros. Inventar ese catalogo aca seria
 * decidir por el usuario. Se valida que el parametro este <b>tipado y coherente</b>, que es lo que
 * el caso borde exige.
 */
@Entity
@Table(name = "tratamiento_parametro")
public class TratamientoParametro {

	/** Tope de {@code valor_texto} en V55. */
	private static final int MAX_TEXTO = 280;

	/** Tope de {@code clave} en V55. */
	private static final int MAX_CLAVE = 64;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "tratamiento_realizado_id", nullable = false, updatable = false)
	private Long tratamientoRealizadoId;

	@Column(name = "clave", nullable = false, length = MAX_CLAVE, updatable = false)
	private String clave;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo_dato", nullable = false, length = 16, updatable = false)
	private TipoDatoParametro tipoDato;

	@Column(name = "valor_numerico", precision = 12, scale = 3, updatable = false)
	private BigDecimal valorNumerico;

	@Column(name = "valor_texto", length = MAX_TEXTO, updatable = false)
	private String valorTexto;

	@Column(name = "valor_booleano", updatable = false)
	private Boolean valorBooleano;

	@Column(name = "unidad", length = 24, updatable = false)
	private String unidad;

	@Column(name = "orden", nullable = false, updatable = false)
	private int orden;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TratamientoParametro() {
		// Requerido por JPA.
	}

	/**
	 * Crea el parametro validando su tipado.
	 *
	 * <p>La validacion se hace <b>aca y no en el service</b> porque es una invariante del dato, no
	 * una regla de orquestacion: un {@code TratamientoParametro} mal tipado no debe poder existir
	 * ni siquiera en memoria. El {@code CHECK} de V55 es el respaldo del motor, no el mecanismo.
	 *
	 * @throws ParametroInvalidoException si falta el tipo, si el valor no cae en la columna que su
	 *                                    tipo declara, o si una unidad acompaña a algo que no es
	 *                                    numerico
	 */
	public TratamientoParametro(
			long organizationId,
			long tratamientoRealizadoId,
			ParametroAplicado aplicado,
			int orden,
			Instant createdAt) {

		aplicado.exigirCoherente();

		this.organizationId = organizationId;
		this.tratamientoRealizadoId = tratamientoRealizadoId;
		this.clave = aplicado.clave().strip();
		this.tipoDato = aplicado.tipoDato();
		this.valorNumerico = aplicado.valorNumerico();
		this.valorTexto = aplicado.valorTexto() == null ? null : aplicado.valorTexto().strip();
		this.valorBooleano = aplicado.valorBooleano();
		this.unidad = aplicado.unidad() == null || aplicado.unidad().isBlank()
				? null
				: aplicado.unidad().strip();
		this.orden = orden;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getTratamientoRealizadoId() {
		return tratamientoRealizadoId;
	}

	public String getClave() {
		return clave;
	}

	public TipoDatoParametro getTipoDato() {
		return tipoDato;
	}

	public BigDecimal getValorNumerico() {
		return valorNumerico;
	}

	public String getValorTexto() {
		return valorTexto;
	}

	public Boolean getValorBooleano() {
		return valorBooleano;
	}

	public String getUnidad() {
		return unidad;
	}

	public int getOrden() {
		return orden;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
