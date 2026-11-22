package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Lo que vale una practica bajo un convenio, durante un periodo (M16, RF-M16-004).
 *
 * <h2>Un solo importe: lo que paga el financiador (V33)</h2>
 *
 * <p>El arancel guarda {@code importeFinanciador} (B). El coseguro <b>no se carga</b>: se calcula
 * al cerrar la sesion como {@code max(A - B, 0)}, con A el precio de la oferta ese dia
 * ({@code contracting.spi.Coseguro}). {@code importeTotal} y {@code coseguro} quedan como
 * historicos de los aranceles cargados antes de V33: esta clase los pone en {@code null} en todo
 * alta y en toda edicion del importe, y nadie los usa para calcular.
 *
 * <p><b>No hay porcentaje de cobertura a proposito:</b> un porcentaje obliga a multiplicar y
 * redondear (§37). Una resta de dos {@code DECIMAL(12,2)} es exacta.
 *
 * <p>{@code BigDecimal} y jamas {@code double}: AGENT.md §5, y ArchUnit lo verifica
 * ({@code sin_punto_flotante_en_el_dominio}). La comparacion es {@code compareTo} y nunca
 * {@code equals}: {@code 100.0} y {@code 100.00} son iguales como importe y distintos como
 * {@code BigDecimal}.
 *
 * <h2>Editar un arancel no reescribe lo ya liquidado (RN-M16-003)</h2>
 *
 * <p>Esta clase no puede garantizarlo sola y conviene saber quien lo hace: lo garantiza que el
 * consumidor COPIE el snapshot que entrega {@code contracting.spi.ArancelDirectory#congelar}, en
 * vez de volver a resolver al mostrar. Lo que esta clase aporta es que la forma natural de subir
 * un precio sea <b>cerrar la vigencia del arancel viejo y abrir uno nuevo</b>, no pisar el importe
 * de una ventana ya transcurrida.
 *
 * <h2>Dos aranceles de la misma practica son NORMALES; solaparse no</h2>
 *
 * <p>El de 2026 y el de 2027 conviven en el mismo convenio, y por eso no hay ningun unique de
 * {@code (convenio_id, practica_id)}. Lo prohibido es que sus vigencias se pisen (RN-M16-002), y
 * eso ningun indice lo sabe expresar: lo hace cumplir el lock de {@code convenio_lock}.
 */
@Entity
@Table(name = "convenio_arancel")
public class ConvenioArancel extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** Copia de la sede del convenio. {@code null} si es del convenio del plan (V37). */
	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Column(name = "convenio_id", nullable = false, updatable = false)
	private Long convenioId;

	/** Practica del catalogo clinico (M06). Inmutable: cambiarla seria otro arancel. */
	@Column(name = "practica_id", nullable = false, updatable = false)
	private Long practicaId;

	/**
	 * B-3 (RF-M16-008). {@code null} = arancel GENERAL de la practica en el convenio, lo que habia
	 * hasta V80. Con valor = arancel de la practica cuando se presta dentro de ESA oferta: al
	 * resolver con oferta manda sobre el general. No se muda: cambiarlo seria inventar otro arancel.
	 */
	@Column(name = "oferta_id", updatable = false)
	private Long ofertaId;

	/** Historico, anterior a V33. {@code null} en los aranceles nuevos. */
	@Column(name = "importe_total", precision = 12, scale = 2)
	private BigDecimal importeTotal;

	/** B: lo que paga el financiador por la practica. */
	@Column(name = "importe_financiador", nullable = false, precision = 12, scale = 2)
	private BigDecimal importeFinanciador;

	/** Historico, anterior a V33. {@code null} en los aranceles nuevos. */
	@Column(name = "coseguro", precision = 12, scale = 2)
	private BigDecimal coseguro;

	@Column(name = "moneda", nullable = false, length = 3)
	private String moneda;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	/** ULTIMO dia en que se aplica, INCLUSIVE. {@code null} = sin fin previsto. */
	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected ConvenioArancel() {
		// Requerido por JPA.
	}

	@SuppressWarnings("java:S107")
	public ConvenioArancel(
			long organizationId,
			Long consultorioId,
			long convenioId,
			long practicaId,
			BigDecimal importeFinanciador,
			String moneda,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		this(organizationId, consultorioId, convenioId, practicaId, null, importeFinanciador,
				moneda, vigenciaDesde, vigenciaHasta);
	}

	/** Alta de un arancel especifico de una oferta (B-3, RF-M16-008); general si es {@code null}. */
	@SuppressWarnings("java:S107")
	public ConvenioArancel(
			long organizationId,
			Long consultorioId,
			long convenioId,
			long practicaId,
			Long ofertaId,
			BigDecimal importeFinanciador,
			String moneda,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.convenioId = convenioId;
		this.practicaId = practicaId;
		this.ofertaId = ofertaId;
		aplicarImporte(importeFinanciador);
		this.moneda = exigirMoneda(moneda);
		aplicarVigencia(vigenciaDesde, vigenciaHasta);
	}

	/**
	 * Edicion parcial. Lo que llega en {@code null} NO se toca. La vigencia se valida como par.
	 *
	 * <p>Ni la practica ni el convenio estan aca: cambiarlos no seria editar este arancel, seria
	 * inventar otro.
	 */
	public void updateDatos(
			BigDecimal importeFinanciador,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		if (importeFinanciador != null) {
			aplicarImporte(importeFinanciador);
		}
		if (vigenciaDesde != null || vigenciaHasta != null) {
			aplicarVigencia(
					vigenciaDesde == null ? this.vigenciaDesde : vigenciaDesde,
					vigenciaHasta == null ? this.vigenciaHasta : vigenciaHasta);
		}
	}

	/** Baja logica con motivo obligatorio. No hay reactivacion. */
	public void deactivate(Instant occurredAt, String reason) {
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason;
	}

	// =================================================================================
	// Reglas
	// =================================================================================

	public Vigencia vigencia() {
		return new Vigencia(vigenciaDesde, vigenciaHasta);
	}

	/** Ver {@code Convenio#seSolapaCon}: quien decide que dos aranceles compiten es la consulta. */
	public boolean seSolapaCon(ConvenioArancel otro) {
		return vigencia().seSolapaCon(otro.vigencia());
	}

	public boolean isOperable() {
		return active;
	}

	/** {@code true} si el arancel se aplica ese dia: operable Y dentro de su vigencia. */
	public boolean aplicaEl(LocalDate fecha) {
		return active && vigencia().cubre(fecha);
	}

	// =================================================================================
	// Invariantes internas
	// =================================================================================

	/**
	 * Fija B y borra los historicos: un total o un coseguro anteriores a V33 que sobrevivieran a
	 * un cambio de B describirian un arancel que ya no existe.
	 */
	private void aplicarImporte(BigDecimal financiador) {
		exigirNoNegativo(financiador, "Lo que paga el financiador");
		this.importeFinanciador = financiador;
		this.importeTotal = null;
		this.coseguro = null;
	}

	private void aplicarVigencia(LocalDate desde, LocalDate hasta) {
		Vigencia validada = new Vigencia(desde, hasta);
		this.vigenciaDesde = validada.desde();
		this.vigenciaHasta = validada.hasta();
	}

	private static void exigirNoNegativo(BigDecimal importe, String cual) {
		if (importe == null) {
			throw new IllegalArgumentException(cual + " es obligatorio");
		}
		if (importe.signum() < 0) {
			throw new IllegalArgumentException(cual + " no puede ser negativo");
		}
	}

	private static String exigirMoneda(String moneda) {
		if (moneda == null || moneda.isBlank()) {
			throw new IllegalArgumentException("Un importe sin moneda no es un importe");
		}
		return moneda.strip().toUpperCase(Locale.ROOT);
	}

	// =================================================================================
	// Accesores
	// =================================================================================

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getConvenioId() {
		return convenioId;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	/** {@code null} = arancel general de la practica. Ver el campo. */
	public Long getOfertaId() {
		return ofertaId;
	}

	/** Si pertenece al grupo de no-solapamiento de esa oferta ({@code null} = el general). */
	public boolean esDelGrupo(Long oferta) {
		return java.util.Objects.equals(ofertaId, oferta);
	}

	public BigDecimal getImporteTotal() {
		return importeTotal;
	}

	public BigDecimal getImporteFinanciador() {
		return importeFinanciador;
	}

	public BigDecimal getCoseguro() {
		return coseguro;
	}

	public String getMoneda() {
		return moneda;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
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
