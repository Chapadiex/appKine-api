package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * El lote que el centro le reclama a un financiador. <b>No es un cobro y no es la caja.</b>
 *
 * <h2>La cuarta cosa</h2>
 *
 * <p>El repositorio ya separaba tres hechos: la {@link Obligacion} dice que <i>se debe</i>, el
 * {@link Cobro} dice que el paciente <i>pago</i>, y {@link MovimientoCaja} dice que <i>entro plata
 * a un cajon</i>. Esta clase agrega la cuarta: <i>se le reclamo a un financiador</i>. RN-M21-001.
 *
 * <p><b>Confirmar una presentacion no mueve un peso.</b> No toca el saldo de ninguna obligacion y
 * no genera ningun movimiento de caja. Que una obra social reciba un lote de doscientas sesiones no
 * significa que haya pagado nada.
 *
 * <h2>La cuenta corriente de un financiador tampoco es la caja</h2>
 *
 * <p>La caja es un cajon de una sede, con jornada, arqueo y fecha de negocio: se cuenta. La cuenta
 * corriente de un financiador vive en meses y nadie la arquea. Se tocan en un solo punto —cuando el
 * financiador paga, {@link FinanciadorPago}— y en ningun otro.
 *
 * <h2>Los cuatro importes, y la condicion que los mueve</h2>
 *
 * <pre>
 *   total_presentado   la suma de los items al confirmar. SE CONGELA.
 *   total_debitado     lo que el financiador rechazo, item por item.
 *   total_cobrado      lo que efectivamente pago.
 *   saldo              presentado - debitado - cobrado. NUNCA NEGATIVO.
 * </pre>
 *
 * <p>El saldo es derivado pero <b>materializado</b>, por el mismo motivo que el saldo de la
 * obligacion en V36: calcularlo al leer obligaria a sumar los items dentro de la transaccion que
 * los mueve, y a nadie a garantizar que dos escrituras concurrentes no lo dejen en negativo.
 *
 * <p><b>Las dos escrituras que lo mueven son UPDATE condicionales nativos</b>
 * —{@code WHERE ... AND saldo >= :importe}— y por eso <b>esta clase no las expone como metodos</b>.
 * Ver {@code PresentacionRepository}: no hay ventana entre leer y escribir porque no se lee.
 *
 * <h2>La {@code @Version} y lo que NO protege</h2>
 *
 * <p>Existe para las escrituras que pasan por JPA —la factura, la anulacion del borrador—. Las que
 * mueven el saldo son SQL nativo y <b>no la incrementan</b>: su proteccion es la condicion del
 * {@code WHERE}, que es mas fuerte que un token optimista porque no depende de que el cliente lo
 * haya leido antes.
 */
@Entity
@Table(name = "presentacion")
public class Presentacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "financiador_id", nullable = false, updatable = false)
	private Long financiadorId;

	/** NULL mientras es borrador. Se asigna al confirmar, con el numerador. */
	@Column(name = "numero")
	private Integer numero;

	@Column(name = "periodo_desde", nullable = false, updatable = false)
	private LocalDate periodoDesde;

	@Column(name = "periodo_hasta", nullable = false, updatable = false)
	private LocalDate periodoHasta;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoPresentacion estado;

	@Column(name = "total_presentado", nullable = false, precision = 12, scale = 2)
	private BigDecimal totalPresentado;

	@Column(name = "total_debitado", nullable = false, precision = 12, scale = 2)
	private BigDecimal totalDebitado;

	@Column(name = "total_cobrado", nullable = false, precision = 12, scale = 2)
	private BigDecimal totalCobrado;

	@Column(name = "saldo", nullable = false, precision = 12, scale = 2)
	private BigDecimal saldo;

	@Column(name = "factura_numero", length = 40)
	private String facturaNumero;

	@Column(name = "factura_fecha")
	private LocalDate facturaFecha;

	@Column(name = "factura_registrada_en")
	private Instant facturaRegistradaEn;

	@Column(name = "factura_registrada_por_cuenta_id")
	private Long facturaRegistradaPorCuentaId;

	@Column(name = "confirmada_en")
	private Instant confirmadaEn;

	@Column(name = "confirmada_por_cuenta_id")
	private Long confirmadaPorCuentaId;

	@Column(name = "conciliada_en")
	private Instant conciliadaEn;

	@Column(name = "conciliada_por_cuenta_id")
	private Long conciliadaPorCuentaId;

	@Column(name = "anulada_en")
	private Instant anuladaEn;

	@Column(name = "anulada_por_cuenta_id")
	private Long anuladaPorCuentaId;

	@Column(name = "motivo_anulacion", length = 280)
	private String motivoAnulacion;

	@Column(name = "creada_en", nullable = false, updatable = false)
	private Instant creadaEn;

	@Column(name = "creada_por_cuenta_id", nullable = false, updatable = false)
	private Long creadaPorCuentaId;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Presentacion() {
		// Requerido por JPA.
	}

	public Presentacion(
			long organizationId,
			long consultorioId,
			long financiadorId,
			LocalDate periodoDesde,
			LocalDate periodoHasta,
			String moneda,
			Instant creadaEn,
			long creadaPorCuentaId) {

		if (periodoDesde == null || periodoHasta == null || periodoHasta.isBefore(periodoDesde)) {
			throw new IllegalArgumentException(
					"El periodo de una presentacion termina cuando empieza o despues: "
							+ periodoDesde + ".." + periodoHasta);
		}

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.financiadorId = financiadorId;
		this.periodoDesde = periodoDesde;
		this.periodoHasta = periodoHasta;
		this.moneda = moneda;
		this.estado = EstadoPresentacion.BORRADOR;
		this.totalPresentado = BigDecimal.ZERO.setScale(2);
		this.totalDebitado = BigDecimal.ZERO.setScale(2);
		this.totalCobrado = BigDecimal.ZERO.setScale(2);
		this.saldo = BigDecimal.ZERO.setScale(2);
		this.creadaEn = creadaEn;
		this.creadaPorCuentaId = creadaPorCuentaId;
	}

	/**
	 * Fija el total del lote mientras se arma.
	 *
	 * <p>Solo en borrador: despues de confirmar, el total esta congelado y lo unico que lo cambia
	 * son los debitos y los pagos, que van por {@code UPDATE} condicional.
	 */
	public void recalcularTotal(BigDecimal totalDeLosItems) {
		this.totalPresentado = totalDeLosItems;
		this.saldo = totalDeLosItems;
	}

	/**
	 * Confirma el envio del lote (RF-M21-004): le asigna numero y lo saca del borrador.
	 *
	 * <p>El numero llega ya resuelto por el numerador —{@code UPDATE ... ultimo_numero + 1}, nunca
	 * {@code MAX + 1}— y la idempotencia se evalua <b>antes</b> de pedirlo, igual que en el cierre
	 * de sesion de 06.05 y en el comprobante de 07.02.
	 */
	public void confirmar(int numeroAsignado, Instant occurredAt, long confirmadaPorCuentaId) {
		this.numero = numeroAsignado;
		this.estado = EstadoPresentacion.PRESENTADA;
		this.confirmadaEn = occurredAt;
		this.confirmadaPorCuentaId = confirmadaPorCuentaId;
	}

	/**
	 * Registra el comprobante externo del centro (RF-M21-005).
	 *
	 * <p><b>El sistema no lo genera ni lo numera: lo registra.</b> Se emite fuera de AKINE. Lo unico
	 * que el sistema puede hacer —y hace— es impedir que el mismo numero quede asociado a dos lotes
	 * del mismo financiador, con el unique de V56.
	 */
	public void registrarFactura(
			String numeroDeFactura, LocalDate fecha, Instant occurredAt, long actorCuentaId) {

		this.facturaNumero = numeroDeFactura;
		this.facturaFecha = fecha;
		this.facturaRegistradaEn = occurredAt;
		this.facturaRegistradaPorCuentaId = actorCuentaId;
		this.estado = EstadoPresentacion.FACTURADA;
	}

	/** Cierra el lote (RF-M21-008). El servicio ya verifico que el saldo sea cero. */
	public void conciliar(Instant occurredAt, long actorCuentaId) {
		this.estado = EstadoPresentacion.CONCILIADA;
		this.conciliadaEn = occurredAt;
		this.conciliadaPorCuentaId = actorCuentaId;
	}

	/**
	 * Descarta el borrador con motivo.
	 *
	 * <p><b>No borra.</b> La fila queda, sus items pasan a {@code ANULADO} —lo que libera las
	 * obligaciones— y el lote conserva la evidencia de que se penso presentar eso.
	 */
	public void anular(String motivo, Instant occurredAt, long actorCuentaId) {
		this.estado = EstadoPresentacion.ANULADA;
		this.anuladaEn = occurredAt;
		this.anuladaPorCuentaId = actorCuentaId;
		this.motivoAnulacion = motivo;
	}

	/** {@code true} si el saldo llego a cero: lo presentado quedo explicado por completo. */
	public boolean estaSaldada() {
		return saldo.signum() == 0;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getFinanciadorId() {
		return financiadorId;
	}

	public Integer getNumero() {
		return numero;
	}

	public LocalDate getPeriodoDesde() {
		return periodoDesde;
	}

	public LocalDate getPeriodoHasta() {
		return periodoHasta;
	}

	public String getMoneda() {
		return moneda;
	}

	public EstadoPresentacion getEstado() {
		return estado;
	}

	public BigDecimal getTotalPresentado() {
		return totalPresentado;
	}

	public BigDecimal getTotalDebitado() {
		return totalDebitado;
	}

	public BigDecimal getTotalCobrado() {
		return totalCobrado;
	}

	public BigDecimal getSaldo() {
		return saldo;
	}

	public String getFacturaNumero() {
		return facturaNumero;
	}

	public LocalDate getFacturaFecha() {
		return facturaFecha;
	}

	public Instant getConfirmadaEn() {
		return confirmadaEn;
	}

	public Instant getConciliadaEn() {
		return conciliadaEn;
	}

	public Instant getAnuladaEn() {
		return anuladaEn;
	}

	public String getMotivoAnulacion() {
		return motivoAnulacion;
	}

	public Instant getCreadaEn() {
		return creadaEn;
	}

	public long getVersion() {
		return version;
	}
}
