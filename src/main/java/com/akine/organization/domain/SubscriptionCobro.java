package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Un cobro de la suscripcion registrado por la plataforma (19/11/2026,
 * docs/diseno/AKINE-cobro-mensual.md).
 *
 * <p><b>Append-only</b>, como {@link SubscriptionTransition}: sin setters y con un repositorio que
 * solo agrega. La verdad vigente es {@code subscription.pagado_hasta}; estas filas cuentan como se
 * llego a ella.
 */
@Entity
@Table(name = "subscription_cobro")
public class SubscriptionCobro {

	/** Cuantos meses se pueden cobrar de una vez. */
	public static final int MESES_MAXIMOS = 12;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "subscription_id", nullable = false, updatable = false)
	private Long subscriptionId;

	@Column(name = "periodo_desde", nullable = false, updatable = false)
	private Instant periodoDesde;

	@Column(name = "periodo_hasta", nullable = false, updatable = false)
	private Instant periodoHasta;

	@Column(name = "meses", nullable = false, updatable = false)
	private int meses;

	@Column(name = "nota", length = 200, updatable = false)
	private String nota;

	/** Cuenta de plataforma que lo registro. Referencia logica, sin FK fisica. */
	@Column(name = "registrado_por", nullable = false, updatable = false)
	private Long registradoPor;

	@Column(name = "registrado_en", nullable = false, updatable = false)
	private Instant registradoEn;

	protected SubscriptionCobro() {
		// Requerido por JPA.
	}

	public SubscriptionCobro(Long organizationId, Long subscriptionId, Periodo periodo, int meses, String nota,
			Long registradoPor, Instant registradoEn) {
		this.organizationId = organizationId;
		this.subscriptionId = subscriptionId;
		this.periodoDesde = periodo.desde();
		this.periodoHasta = periodo.hasta();
		this.meses = meses;
		this.nota = nota;
		this.registradoPor = registradoPor;
		this.registradoEn = registradoEn;
	}

	/**
	 * El periodo que cubre un cobro de {@code meses} meses registrado {@code ahora}.
	 *
	 * <p>Empieza en {@code max(pagadoHasta, ahora)}: pagar antes de vencer conserva el ciclo y
	 * pagar tarde (o el primer cobro, sin {@code pagadoHasta}) lo reinicia desde hoy. Termina
	 * {@code meses} meses calendario despues <b>en la zona del centro</b>, para que el 31/01 a las
	 * 21 de Cordoba no caiga en otro dia por estar en UTC; un mes que no tiene ese dia cae en el
	 * ultimo (31/01 + 1 mes = 28/02 o 29/02).
	 */
	public static Periodo periodo(Instant pagadoHasta, Instant ahora, int meses, ZoneId zona) {
		if (meses < 1 || meses > MESES_MAXIMOS) {
			throw new IllegalArgumentException("Se cobran de 1 a " + MESES_MAXIMOS + " meses");
		}
		Instant desde = pagadoHasta != null && pagadoHasta.isAfter(ahora) ? pagadoHasta : ahora;
		return new Periodo(desde, desde.atZone(zona).plusMonths(meses).toInstant());
	}

	/** Un periodo pagado, {@code [desde, hasta)}. */
	public record Periodo(Instant desde, Instant hasta) {
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getSubscriptionId() {
		return subscriptionId;
	}

	public Instant getPeriodoDesde() {
		return periodoDesde;
	}

	public Instant getPeriodoHasta() {
		return periodoHasta;
	}

	public int getMeses() {
		return meses;
	}

	public String getNota() {
		return nota;
	}

	public Long getRegistradoPor() {
		return registradoPor;
	}

	public Instant getRegistradoEn() {
		return registradoEn;
	}
}
