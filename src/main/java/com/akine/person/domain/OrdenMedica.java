package com.akine.person.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Orden medica presentada por un paciente (M17, RF-M17-001/002).
 *
 * <h2>Es un PAPEL transcripto, no un registro clinico</h2>
 *
 * <p>RN-M17-004 es explicita: la documentacion administrativa no reemplaza el registro clinico.
 * {@link #indicacion} son 280 caracteres con lo que el papel dice, para que el mostrador sepa que
 * pedir al financiador. No es evolucion, no es diagnostico estructurado y no participa de ninguna
 * regla clinica. Es el mismo criterio con que V40 dejo afuera las categorias clinicas de
 * {@code adjunto_administrativo}: agregar campos clinicos aca construiria una historia clinica
 * paralela sin ninguno de los controles de M09 —sin justificacion declarada, sin auditoria de
 * lectura, sin relacion asistencial—.
 *
 * <h2>El emisor es texto libre, y es a proposito</h2>
 *
 * <p>Quien firma la orden es un medico EXTERNO al centro. No esta en ningun catalogo del tenant y
 * nunca va a estarlo: obligarlo a existir como fila dejaria al mostrador sin poder cargar la orden
 * que el paciente trajo hoy. {@link #matriculaEmisor} es opcional por lo mismo, y ademas resuelve
 * el caso borde "documento ilegible" sin rechazar la carga.
 *
 * <h2>La cobertura es OPCIONAL, y no por descuido</h2>
 *
 * <p>Una prescripcion la firma un medico, no un financiador. Atarla a una cobertura obligaria a
 * volver a cargarla el dia que el paciente cambia de obra social, aunque el papel siga siendo el
 * mismo. Cuando la orden si se presento contra una cobertura concreta, se guarda; cuando no, vale
 * para cualquiera.
 *
 * <h2>Vencida no es dada de baja</h2>
 *
 * <p>El vencimiento se calcula al leer con {@link #vigenteEl(LocalDate)}: no hay ningun estado
 * VENCIDA que alguien tenga que mover, y la fila no se toca nunca. La baja logica es otra cosa
 * —"esta orden nunca debio cargarse"— y exige motivo. Mismo par que en M08 y M15.
 */
@Entity
@Table(name = "orden_medica")
public class OrdenMedica extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Column(name = "cobertura_id", updatable = false)
	private Long coberturaId;

	@Column(name = "numero", length = 64)
	private String numero;

	@Column(name = "profesional_emisor", nullable = false, length = 160)
	private String profesionalEmisor;

	@Column(name = "matricula_emisor", length = 64)
	private String matriculaEmisor;

	@Column(name = "fecha_emision", nullable = false)
	private LocalDate fechaEmision;

	@Column(name = "indicacion", length = 280)
	private String indicacion;

	@Column(name = "sesiones_prescriptas")
	private Integer sesionesPrescriptas;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "adjunto_id")
	private Long adjuntoId;

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

	protected OrdenMedica() {
		// JPA.
	}

	@SuppressWarnings({"java:S107", "checkstyle:ParameterNumber"})
	public OrdenMedica(
			long organizationId,
			long personaId,
			Long consultorioId,
			Long coberturaId,
			String numero,
			String profesionalEmisor,
			String matriculaEmisor,
			LocalDate fechaEmision,
			String indicacion,
			Integer sesionesPrescriptas,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			String observaciones) {

		this.organizationId = organizationId;
		this.personaId = personaId;
		this.consultorioId = consultorioId;
		this.coberturaId = coberturaId;
		this.numero = vacioEsNulo(numero);
		this.profesionalEmisor = exigirTexto(
				profesionalEmisor,
				"La orden necesita el profesional que la firma: es lo que el financiador mira");
		this.matriculaEmisor = vacioEsNulo(matriculaEmisor);
		this.fechaEmision = exigirNoNulo(
				fechaEmision, "La orden necesita la fecha en que se emitio");
		this.indicacion = vacioEsNulo(indicacion);
		this.sesionesPrescriptas = exigirCantidad(sesionesPrescriptas);
		aplicarVigencia(
				vigenciaDesde == null ? fechaEmision : vigenciaDesde, vigenciaHasta);
		this.observaciones = vacioEsNulo(observaciones);
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Edicion parcial: lo que llega nulo no se toca.
	 *
	 * <p>{@link #personaId} y {@link #coberturaId} no estan: son {@code updatable = false} en el
	 * mapeo y el comando ni siquiera los ofrece. Mudar una orden de paciente reescribiria quien
	 * presento que papel, que es exactamente el historico que la regla maestra 10 protege.
	 */
	@SuppressWarnings("java:S107")
	public void updateDatos(
			String numero,
			String profesionalEmisor,
			String matriculaEmisor,
			LocalDate fechaEmision,
			String indicacion,
			Integer sesionesPrescriptas,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			String observaciones) {

		if (numero != null) {
			this.numero = vacioEsNulo(numero);
		}
		if (profesionalEmisor != null) {
			this.profesionalEmisor = exigirTexto(
					profesionalEmisor, "El profesional emisor no puede quedar vacio");
		}
		if (matriculaEmisor != null) {
			this.matriculaEmisor = vacioEsNulo(matriculaEmisor);
		}
		if (fechaEmision != null) {
			this.fechaEmision = fechaEmision;
		}
		if (indicacion != null) {
			this.indicacion = vacioEsNulo(indicacion);
		}
		if (sesionesPrescriptas != null) {
			this.sesionesPrescriptas = exigirCantidad(sesionesPrescriptas);
		}
		if (vigenciaDesde != null || vigenciaHasta != null) {
			aplicarVigencia(
					vigenciaDesde == null ? this.vigenciaDesde : vigenciaDesde,
					vigenciaHasta == null ? this.vigenciaHasta : vigenciaHasta);
		}
		if (observaciones != null) {
			this.observaciones = vacioEsNulo(observaciones);
		}
		// La coherencia temporal se revalida SIEMPRE, no solo cuando llegan las dos fechas: mover
		// solo la emision hacia adelante puede dejarla despues del inicio de vigencia, y ese es
		// justamente el descuido con el que se rompe la invariante sin darse cuenta.
		exigirEmisionPrevia();
	}

	public void vincularDocumento(long adjuntoId) {
		this.adjuntoId = adjuntoId;
	}

	public void desvincularDocumento() {
		this.adjuntoId = null;
	}

	public void deactivate(Instant occurredAt, String reason) {
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason;
	}

	// =================================================================================
	// Preguntas
	// =================================================================================

	public Vigencia vigencia() {
		return new Vigencia(vigenciaDesde, vigenciaHasta);
	}

	/** Se puede presentar ese dia: esta activa y la fecha cae dentro de su vigencia. */
	public boolean vigenteEl(LocalDate fecha) {
		return active && vigencia().cubre(fecha);
	}

	/** Dias que faltan para el vencimiento, o {@code null} si no vence. RF-M17-006. */
	public Long diasParaVencer(LocalDate fecha) {
		return vigencia().diasHasta(fecha);
	}

	public boolean isOperable() {
		return active;
	}

	/** Sirve para esa cobertura: o no esta atada a ninguna, o esta atada a esa. */
	public boolean sirveParaCobertura(Long otraCoberturaId) {
		return coberturaId == null || coberturaId.equals(otraCoberturaId);
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	private void aplicarVigencia(LocalDate desde, LocalDate hasta) {
		Vigencia validada = new Vigencia(desde, hasta);
		this.vigenciaDesde = validada.desde();
		this.vigenciaHasta = validada.hasta();
		exigirEmisionPrevia();
	}

	/** Una orden no puede valer antes de haberse escrito. La base tambien lo exige (V44). */
	private void exigirEmisionPrevia() {
		if (fechaEmision != null && vigenciaDesde != null && vigenciaDesde.isBefore(fechaEmision)) {
			throw new IllegalArgumentException(
					"La orden no puede empezar a valer antes de su fecha de emision: emitida el "
							+ fechaEmision + " y vigente desde " + vigenciaDesde);
		}
	}

	private static Integer exigirCantidad(Integer cantidad) {
		if (cantidad != null && cantidad <= 0) {
			throw new IllegalArgumentException(
					"Las sesiones prescriptas tienen que ser mayores que cero. Sin cantidad "
							+ "indicada se expresa omitiendolas, no con cero");
		}
		return cantidad;
	}

	private static String exigirTexto(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
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

	public Long getPersonaId() {
		return personaId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getCoberturaId() {
		return coberturaId;
	}

	public String getNumero() {
		return numero;
	}

	public String getProfesionalEmisor() {
		return profesionalEmisor;
	}

	public String getMatriculaEmisor() {
		return matriculaEmisor;
	}

	public LocalDate getFechaEmision() {
		return fechaEmision;
	}

	public String getIndicacion() {
		return indicacion;
	}

	public Integer getSesionesPrescriptas() {
		return sesionesPrescriptas;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
	}

	public Long getAdjuntoId() {
		return adjuntoId;
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
