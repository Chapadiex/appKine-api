package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.MedicionTipoIncompatibleException;
import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionTipo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * El valor medido en una sesion (RF-M14-004).
 *
 * <h2>El significado esta CONGELADO en la fila, y es la condicion dura de la etapa</h2>
 *
 * <p>{@code definicionCodigo}, {@code definicionNombre}, {@code definicionUnidad},
 * {@code definicionTipo} y {@code definicionVersion} son copias de lo que decia
 * {@code medicion_definicion} en el instante del registro, y <b>ninguna de las cinco es
 * editable</b>. Sin ellas, un {@code UPDATE} sobre el catalogo —cambiar la unidad de cm a mm,
 * corregir el nombre de un test— reescribiria el significado de todas las mediciones pasadas sin
 * tocar una sola fila de esta tabla: reescritura de historia clinica por la puerta de atras, que
 * ningun test de esta etapa veria porque el dato cambia en otra tabla.
 *
 * <p>Es el mismo snapshot que congela el importe en la obligacion (07.01) y el nombre de la oferta
 * en el item del plan (04.04). {@code definicionId} se conserva igual para poder seguir una misma
 * medida en el tiempo; lo que no se hace es <b>resolver el significado por join al leer</b>.
 *
 * <h2>El valor es tipado, y el tipo que manda es el COPIADO</h2>
 *
 * <p>{@code NUMERICO} y {@code ESCALA} escriben {@code valorNumerico}, {@code TEXTO} escribe
 * {@code valorTexto} y {@code BOOLEANO} escribe {@code valorBooleano}. Exactamente uno, nunca dos
 * ni cero, y el CHECK de V52 lo verifica contra el tipo de la fila — no contra el vigente en el
 * catalogo, que puede haber cambiado.
 *
 * <p>{@code BigDecimal} y jamas {@code double}: AGENT.md seccion 5. Un ROM de 92,5 grados guardado
 * como binario flotante deja de ser comparable consigo mismo, que es lo unico para lo que una
 * medicion sirve.
 *
 * <h2>Lo que esta clase NO tiene</h2>
 *
 * <p>Ni delta ni referencia a la medicion anterior: <b>la comparacion se calcula al leer</b>, igual
 * que el timeline de 04.02 y el avance del plan de 04.04. Guardarla seria una segunda copia de la
 * verdad que miente el dia que alguien enmiende la sesion anterior.
 *
 * <p>Ni baja logica. El borrado es fisico y esta acotado a la sesion <b>en curso</b>; sobre una
 * cerrada la operacion no existe, y eso lo decide el servicio. Es el mismo criterio con el que
 * 04.04 admitio borrar items de un plan en BORRADOR: lo que nunca se cerro no es informacion
 * historica.
 */
@Entity
@Table(name = "sesion_medicion")
public class SesionMedicion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "sesion_id", nullable = false, updatable = false)
	private Long sesionId;

	@Column(name = "definicion_id", nullable = false, updatable = false)
	private Long definicionId;

	@Column(name = "definicion_codigo", nullable = false, updatable = false, length = 48)
	private String definicionCodigo;

	@Column(name = "definicion_nombre", nullable = false, updatable = false, length = 160)
	private String definicionNombre;

	@Column(name = "definicion_unidad", updatable = false, length = 24)
	private String definicionUnidad;

	@Enumerated(EnumType.STRING)
	@Column(name = "definicion_tipo", nullable = false, updatable = false, length = 16)
	private MedicionTipo definicionTipo;

	@Column(name = "definicion_version", nullable = false, updatable = false)
	private long definicionVersion;

	/** Parte de la identidad de la medicion: entra en {@code uk_sesion_medicion}. */
	@Enumerated(EnumType.STRING)
	@Column(name = "lateralidad", nullable = false, updatable = false, length = 16)
	private LateralidadMedicion lateralidad;

	@Column(name = "valor_numerico", precision = 10, scale = 3)
	private BigDecimal valorNumerico;

	@Column(name = "valor_texto", length = 500)
	private String valorTexto;

	@Column(name = "valor_booleano")
	private Boolean valorBooleano;

	@Column(name = "nota", length = 280)
	private String nota;

	@Column(name = "registrada_en", nullable = false)
	private Instant registradaEn;

	@Column(name = "registrada_por_cuenta_id", nullable = false)
	private Long registradaPorCuentaId;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SesionMedicion() {
		// Requerido por JPA.
	}

	/**
	 * Primera escritura de esta medicion.
	 *
	 * <p>El snapshot se toma del {@code spi} <b>aca</b> y no en el servicio, para que no exista
	 * ningun camino que construya la fila sin congelar el significado.
	 *
	 * @throws MedicionTipoIncompatibleException si el valor no corresponde al tipo de la definicion
	 */
	public SesionMedicion(
			long organizationId,
			long sesionId,
			MedicionDefinicionSnapshot definicion,
			LateralidadMedicion lateralidad,
			ValorMedido valor,
			String nota,
			Instant occurredAt,
			long registradaPorCuentaId) {

		this.organizationId = organizationId;
		this.sesionId = sesionId;
		this.definicionId = definicion.id();
		this.definicionCodigo = definicion.codigo();
		this.definicionNombre = definicion.nombre();
		this.definicionUnidad = definicion.unidad();
		this.definicionTipo = definicion.tipo();
		this.definicionVersion = definicion.version();
		this.lateralidad = lateralidad == null ? LateralidadMedicion.NO_APLICA : lateralidad;
		this.registradaPorCuentaId = registradaPorCuentaId;
		aplicar(valor, nota, occurredAt);
	}

	/**
	 * Reescritura de la misma medicion: es lo que hace idempotente al {@code PUT} del autosave.
	 *
	 * <p><b>El tipo se revalida contra el snapshot de la fila, no contra el catalogo de hoy.</b>
	 * Si la definicion cambiara de tipo —cosa que la entidad de {@code resource} impide por otro
	 * camino—, esta fila seguiria admitiendo solo el valor coherente con lo que ya guardo, que es
	 * lo unico que el CHECK de V52 acepta.
	 */
	public void actualizar(
			ValorMedido valor, String nota, Instant occurredAt, long registradaPorCuentaId) {

		this.registradaPorCuentaId = registradaPorCuentaId;
		aplicar(valor, nota, occurredAt);
	}

	private void aplicar(ValorMedido valor, String nota, Instant occurredAt) {
		if (valor == null) {
			throw new MedicionTipoIncompatibleException(
					definicionCodigo, definicionTipo.name(),
					"no se envio ningun valor: una medicion sin valor no mide nada");
		}
		valor.exigirCompatibleCon(definicionTipo, definicionCodigo);

		// Los tres se asignan siempre, incluidos los dos que quedan en null: una reescritura que
		// solo pisara el campo nuevo dejaria la fila con dos valores y el CHECK la rechazaria al
		// flush, con un 500 en vez del 400 que corresponde.
		this.valorNumerico = valor.numerico();
		this.valorTexto = valor.texto();
		this.valorBooleano = valor.booleano();
		this.nota = nota == null || nota.isBlank() ? null : nota.strip();
		this.registradaEn = occurredAt;
	}

	/** El valor de esta medicion, tal como se guardo. */
	public ValorMedido valor() {
		return new ValorMedido(valorNumerico, valorTexto, valorBooleano);
	}

	@PrePersist
	void alInsertar() {
		Instant ahora = Instant.now();
		if (createdAt == null) {
			createdAt = ahora;
		}
		updatedAt = ahora;
	}

	@PreUpdate
	void alActualizar() {
		updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getSesionId() {
		return sesionId;
	}

	public Long getDefinicionId() {
		return definicionId;
	}

	public String getDefinicionCodigo() {
		return definicionCodigo;
	}

	public String getDefinicionNombre() {
		return definicionNombre;
	}

	public String getDefinicionUnidad() {
		return definicionUnidad;
	}

	public MedicionTipo getDefinicionTipo() {
		return definicionTipo;
	}

	public long getDefinicionVersion() {
		return definicionVersion;
	}

	public LateralidadMedicion getLateralidad() {
		return lateralidad;
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

	public String getNota() {
		return nota;
	}

	public Instant getRegistradaEn() {
		return registradaEn;
	}

	public Long getRegistradaPorCuentaId() {
		return registradaPorCuentaId;
	}

	public long getVersion() {
		return version;
	}
}
