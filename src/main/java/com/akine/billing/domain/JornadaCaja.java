package com.akine.billing.domain;

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
import java.time.LocalDate;

/**
 * Un turno de caja de una sede: se abre con un saldo contado, recibe movimientos y se arquea.
 *
 * <h2>La caja es la sede, la unidad operativa es la jornada</h2>
 *
 * <p>No hay una caja por responsable —exigiria un cajon fisico por persona, y en un centro el
 * mostrador es uno y lo atienden tres— ni una por organizacion —sumaria dos cajones que estan a
 * 40 km—. Hay <b>a lo sumo una jornada ABIERTA por sede</b>, y lo hace cumplir la base con la
 * columna generada {@code abierta_marca} mas un unique: varios NULL no colisionan en MySQL. Varias
 * jornadas por fecha si son legitimas: manana y tarde, con responsables distintos, arquean dos
 * veces.
 *
 * <h2>Esta clase casi no tiene comportamiento, y es a proposito</h2>
 *
 * <p>El saldo se mueve y la jornada se cierra con <b>UPDATE condicionales en SQL</b>
 * ({@code JornadaCajaRepositoryPort}), no llamando metodos de esta entidad. La razon es que las dos
 * operaciones tienen una condicion que decide la correctitud —{@code saldo_arqueo >= :importe} y
 * {@code saldo_arqueo = :esperado}— y evaluarla en Java obliga a leer antes de escribir, que es
 * donde se cuela la ventana entre dos operadores concurrentes. Es el mismo criterio que 07.02 uso
 * para imputar y 04.05 para consumir autorizaciones.
 *
 * <p>Corolario: <b>no hay {@code @Version}.</b> Esas escrituras son nativas y no la incrementarian,
 * y un token optimista que no avanza cuando el dato cambia promete una proteccion que no da. Peor:
 * forzarlo sobre un padre que ademas queda sucio hace avanzar la version dos veces y el cliente
 * come un 409 del que no puede salir, que es la trampa que 04.02 pago. La proteccion del cierre es
 * {@code saldoTeoricoEsperado}, que es control optimista sobre la cantidad que significa algo.
 */
@Entity
@Table(name = "jornada_caja")
public class JornadaCaja {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** Una caja pertenece a una <b>sede</b>, no a la organizacion. */
	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	/**
	 * El dia operativo, en la zona IANA de la sede.
	 *
	 * <p>Se deriva al abrir y <b>no se pide</b>: abrir una jornada "para ayer" es un ajuste contable
	 * disfrazado de operacion. La zona sale de {@code ConsultorioSnapshot.timezone}, que existe
	 * desde 02.01 y cuyo comentario en V16 ya nombra el corte de caja como su motivo.
	 */
	@Column(name = "fecha_negocio", nullable = false, updatable = false)
	private LocalDate fechaNegocio;

	/** Fija al abrir: un arqueo que suma pesos con dolares no se puede contar. */
	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoJornada estado;

	@Column(name = "saldo_inicial", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal saldoInicial;

	/**
	 * Saldo <b>en efectivo</b>. Arranca igual al inicial y solo lo mueven los movimientos que
	 * afectan el arqueo.
	 *
	 * <p>Es una derivacion del ledger pero se materializa, por lo mismo que V36 materializa el saldo
	 * de la obligacion y V50 {@code cantidad_consumida}: calcularla al leer obligaria a sumar todas
	 * las filas dentro de la transaccion que registra un egreso, y a nadie a garantizar que dos
	 * egresos concurrentes no la dejen en negativo. Con la columna, el egreso es un UPDATE
	 * condicional y el CHECK impide el estado imposible.
	 */
	@Column(name = "saldo_arqueo", nullable = false, precision = 12, scale = 2)
	private BigDecimal saldoArqueo;

	@Column(name = "abierta_en", nullable = false, updatable = false)
	private Instant abiertaEn;

	@Column(name = "abierta_por_cuenta_id", nullable = false, updatable = false)
	private Long abiertaPorCuentaId;

	@Column(name = "cerrada_en")
	private Instant cerradaEn;

	@Column(name = "cerrada_por_cuenta_id")
	private Long cerradaPorCuentaId;

	/** Congelado al cerrar. Un cierre historico se explica dentro de seis meses sin recalcular. */
	@Column(name = "saldo_teorico_cierre", precision = 12, scale = 2)
	private BigDecimal saldoTeoricoCierre;

	/** Lo que se conto fisicamente. */
	@Column(name = "saldo_declarado", precision = 12, scale = 2)
	private BigDecimal saldoDeclarado;

	/** {@code declarado - teorico}. Positiva sobra, negativa falta. <b>La calcula el servidor.</b> */
	@Column(name = "diferencia", precision = 12, scale = 2)
	private BigDecimal diferencia;

	/** Obligatorio con diferencia distinta de cero, prohibido sin ella. RN-M20-004, por CHECK. */
	@Column(name = "motivo_diferencia", length = 280)
	private String motivoDiferencia;

	protected JornadaCaja() {
		// Requerido por JPA.
	}

	/** Abre la jornada. El saldo arqueable arranca igual al inicial declarado. */
	public JornadaCaja(
			long organizationId,
			long consultorioId,
			LocalDate fechaNegocio,
			String moneda,
			BigDecimal saldoInicial,
			Instant abiertaEn,
			long abiertaPorCuentaId) {

		if (saldoInicial == null || saldoInicial.signum() < 0) {
			throw new IllegalArgumentException(
					"El saldo inicial de una caja no puede ser negativo: " + saldoInicial);
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.fechaNegocio = fechaNegocio;
		this.moneda = moneda;
		this.estado = EstadoJornada.ABIERTA;
		this.saldoInicial = saldoInicial;
		this.saldoArqueo = saldoInicial;
		this.abiertaEn = abiertaEn;
		this.abiertaPorCuentaId = abiertaPorCuentaId;
	}

	public boolean estaAbierta() {
		return estado == EstadoJornada.ABIERTA;
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

	public LocalDate getFechaNegocio() {
		return fechaNegocio;
	}

	public String getMoneda() {
		return moneda;
	}

	public EstadoJornada getEstado() {
		return estado;
	}

	public BigDecimal getSaldoInicial() {
		return saldoInicial;
	}

	public BigDecimal getSaldoArqueo() {
		return saldoArqueo;
	}

	public Instant getAbiertaEn() {
		return abiertaEn;
	}

	public Long getAbiertaPorCuentaId() {
		return abiertaPorCuentaId;
	}

	public Instant getCerradaEn() {
		return cerradaEn;
	}

	public Long getCerradaPorCuentaId() {
		return cerradaPorCuentaId;
	}

	public BigDecimal getSaldoTeoricoCierre() {
		return saldoTeoricoCierre;
	}

	public BigDecimal getSaldoDeclarado() {
		return saldoDeclarado;
	}

	public BigDecimal getDiferencia() {
		return diferencia;
	}

	public String getMotivoDiferencia() {
		return motivoDiferencia;
	}
}
