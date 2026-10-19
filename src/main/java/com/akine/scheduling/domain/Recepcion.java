package com.akine.scheduling.domain;

import com.akine.scheduling.domain.exception.TransicionDeRecepcionNoPermitidaException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * La recepcion de un turno: llegada, validacion administrativa, espera y llamado (M13, DP-16).
 *
 * <p>Es la maquina de estados propia que DP-05 pedia y que 05.04 habia resuelto como un estado
 * mas del turno. El turno sigue siendo solo la reserva; esto es lo que pasa en el mostrador. Ver
 * {@link EstadoRecepcion} para el diagrama y {@code docs/diseno/AKINE-E-4-recepcion.md} para las
 * reglas.
 *
 * <p>Cada metodo de transicion valida el estado de origen y deja la fila coherente con los CHECK
 * de {@code V78}; quien la llama registra el {@link RecepcionEvento}. <b>Ninguna transicion
 * prueba que la prestacion ocurrio</b> (DP-05).
 */
@Entity
@Table(name = "recepcion")
public class Recepcion {

	private static final Set<EstadoRecepcion> VALIDABLES =
			EnumSet.of(EstadoRecepcion.LLEGO, EstadoRecepcion.OBSERVADA);

	private static final Set<EstadoRecepcion> PASAN_A_ESPERA =
			EnumSet.of(EstadoRecepcion.VALIDADA, EstadoRecepcion.OBSERVADA);

	/** U-21: Particular tambien desde la sala, sin anular y volver a marcar la llegada. */
	private static final Set<EstadoRecepcion> ADMITEN_PARTICULAR =
			EnumSet.of(EstadoRecepcion.LLEGO, EstadoRecepcion.OBSERVADA, EstadoRecepcion.EN_ESPERA);

	/** U-21: de donde se puede salir sin ser atendido. Lo decidio el usuario, no se infiere. */
	private static final Set<EstadoRecepcion> ADMITEN_RETIRO =
			EnumSet.of(EstadoRecepcion.LLEGO, EstadoRecepcion.EN_ESPERA);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "turno_id", nullable = false, updatable = false)
	private Long turnoId;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoRecepcion estado;

	@Column(name = "llegada_en", nullable = false, updatable = false)
	private Instant llegadaEn;

	@Column(name = "llegada_por_cuenta_id", nullable = false, updatable = false)
	private Long llegadaPorCuentaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "modalidad", length = 16)
	private ModalidadRecepcion modalidad;

	@Column(name = "practica_id")
	private Long practicaId;

	@Column(name = "cobertura_id")
	private Long coberturaId;

	@Column(name = "convenio_id")
	private Long convenioId;

	@Column(name = "observacion", length = 1000)
	private String observacion;

	@Column(name = "motivo_particular", length = 300)
	private String motivoParticular;

	@Column(name = "validada_en")
	private Instant validadaEn;

	@Column(name = "validada_por_cuenta_id")
	private Long validadaPorCuentaId;

	@Column(name = "en_espera_desde")
	private Instant enEsperaDesde;

	@Column(name = "llamada_en")
	private Instant llamadaEn;

	@Column(name = "llamada_por_cuenta_id")
	private Long llamadaPorCuentaId;

	@Column(name = "cerrada_en")
	private Instant cerradaEn;

	@Column(name = "cerrada_por_cuenta_id")
	private Long cerradaPorCuentaId;

	@Column(name = "motivo_cierre", length = 300)
	private String motivoCierre;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Recepcion() {
		// Requerido por JPA.
	}

	private Recepcion(long organizationId, long consultorioId, long turnoId,
			Instant llegadaEn, long cuentaId) {
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.turnoId = turnoId;
		this.estado = EstadoRecepcion.LLEGO;
		this.llegadaEn = llegadaEn;
		this.llegadaPorCuentaId = cuentaId;
	}

	/**
	 * La llegada de la persona al turno. La hora la pone el servidor (RN-M13-001): es evidencia
	 * administrativa y no la decide el reloj del mostrador.
	 */
	public static Recepcion llegada(Turno turno, Instant ahora, long cuentaId) {
		return new Recepcion(turno.getOrganizationId(), turno.getConsultorioId(), turno.getId(),
				ahora, cuentaId);
	}

	// =================================================================================
	// Transiciones
	// =================================================================================

	/** El servidor encontro cobertura elegible. Guarda el snapshot administrativo preliminar. */
	public void validarConCobertura(
			long practicaId, long coberturaId, Long convenioId, long cuentaId, Instant ahora) {
		exigir(VALIDABLES, "validar");
		this.estado = EstadoRecepcion.VALIDADA;
		this.modalidad = ModalidadRecepcion.COBERTURA;
		this.practicaId = practicaId;
		this.coberturaId = coberturaId;
		this.convenioId = convenioId;
		this.observacion = null;
		marcarValidacion(cuentaId, ahora);
	}

	/**
	 * La validacion encontro algo. <b>No bloquea</b> (RN-M13-003): la recepcion sigue y puede
	 * pasar a espera; la observacion queda a la vista.
	 */
	public void observar(
			String observacion, Long practicaId, Long coberturaId, Long convenioId,
			long cuentaId, Instant ahora) {
		exigir(VALIDABLES, "validar");
		if (observacion == null || observacion.isBlank()) {
			throw new IllegalArgumentException("Una recepcion observada dice que se observo");
		}
		this.estado = EstadoRecepcion.OBSERVADA;
		this.modalidad = null;
		this.practicaId = practicaId;
		this.coberturaId = coberturaId;
		this.convenioId = convenioId;
		this.observacion = recortar(observacion.strip(), 1000);
		marcarValidacion(cuentaId, ahora);
	}

	/**
	 * Decision explicita del operador de atender como Particular (RF-M13-005).
	 *
	 * <p>No toca la cobertura maestra del paciente (RN-M13-004): es un dato de esta recepcion. La
	 * observacion previa, si la hubo, se conserva: es la razon por la que se decidio esto.
	 *
	 * <p><b>Desde {@code EN_ESPERA}</b> (U-21) la persona sigue en la sala: cambia la modalidad y
	 * el estado queda {@code EN_ESPERA}, con su hora de espera intacta. Volverla a
	 * {@code VALIDADA} la sacaria de la sala por una decision administrativa. Si ya se atiende
	 * como Particular es 409: repetirla pisaria el motivo que ya consta.
	 */
	public void atenderComoParticular(String motivo, long cuentaId, Instant ahora) {
		exigir(ADMITEN_PARTICULAR, "atender como Particular");
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Atender como Particular exige un motivo (RF-M13-005)");
		}
		if (modalidad == ModalidadRecepcion.PARTICULAR) {
			throw new TransicionDeRecepcionNoPermitidaException(turnoId,
					"ya se atiende como Particular");
		}
		if (estado != EstadoRecepcion.EN_ESPERA) {
			this.estado = EstadoRecepcion.VALIDADA;
		}
		this.modalidad = ModalidadRecepcion.PARTICULAR;
		this.motivoParticular = motivo.strip();
		this.coberturaId = null;
		this.convenioId = null;
		marcarValidacion(cuentaId, ahora);
	}

	public void pasarAEspera(Instant ahora) {
		if (estado == EstadoRecepcion.LLEGO) {
			throw new TransicionDeRecepcionNoPermitidaException(turnoId,
					"falta la validacion administrativa: validar o atender como Particular");
		}
		exigir(PASAN_A_ESPERA, "pasar a espera");
		this.estado = EstadoRecepcion.EN_ESPERA;
		this.enEsperaDesde = ahora;
	}

	public void llamar(long cuentaId, Instant ahora) {
		exigir(EnumSet.of(EstadoRecepcion.EN_ESPERA), "llamar");
		this.estado = EstadoRecepcion.LLAMADA;
		this.llamadaEn = ahora;
		this.llamadaPorCuentaId = cuentaId;
	}

	/** El check-in fue un error. La fila queda; un check-in posterior crea otra recepcion. */
	public void anular(String motivo, long cuentaId, Instant ahora) {
		exigirAbierta("anular");
		cerrar(EstadoRecepcion.ANULADA, motivo, cuentaId, ahora);
	}

	/**
	 * La persona se retiro sin ser atendida (U-21). A diferencia de {@link #anular}, la llegada
	 * <b>vale</b>: la persona vino. Y a diferencia de {@link #cerrarPorCancelacion}, el turno no se
	 * toca. El motivo es obligatorio (lo exige tambien {@code ck_recepcion_retirado_con_motivo}).
	 */
	public void retirar(String motivo, long cuentaId, Instant ahora) {
		exigir(ADMITEN_RETIRO, "registrar que se retiro sin ser atendido");
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Registrar que la persona se retiro sin ser atendida exige un motivo");
		}
		cerrar(EstadoRecepcion.RETIRADO, motivo, cuentaId, ahora);
	}

	/** El turno se cancelo con la persona presente: la llegada consta y la recepcion termina. */
	public void cerrarPorCancelacion(String motivo, long cuentaId, Instant ahora) {
		exigirAbierta("cerrar");
		cerrar(EstadoRecepcion.CERRADA, motivo, cuentaId, ahora);
	}

	public boolean estaAbierta() {
		return estado.estaAbierta();
	}

	private void cerrar(EstadoRecepcion destino, String motivo, long cuentaId, Instant ahora) {
		this.estado = destino;
		this.cerradaEn = ahora;
		this.cerradaPorCuentaId = cuentaId;
		this.motivoCierre = motivo == null || motivo.isBlank() ? null : recortar(motivo.strip(), 300);
	}

	private void marcarValidacion(long cuentaId, Instant ahora) {
		this.validadaEn = ahora;
		this.validadaPorCuentaId = cuentaId;
	}

	private void exigir(Set<EstadoRecepcion> admitidos, String operacion) {
		if (!admitidos.contains(estado)) {
			throw new TransicionDeRecepcionNoPermitidaException(turnoId,
					"esta " + estado.name() + " y no admite " + operacion);
		}
	}

	private void exigirAbierta(String operacion) {
		if (!estado.estaAbierta()) {
			throw new TransicionDeRecepcionNoPermitidaException(turnoId,
					"ya esta " + estado.name() + " y no admite " + operacion);
		}
	}

	private static String recortar(String texto, int maximo) {
		return texto.length() <= maximo ? texto : texto.substring(0, maximo);
	}

	// =================================================================================
	// Lectura
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

	public Long getTurnoId() {
		return turnoId;
	}

	public EstadoRecepcion getEstado() {
		return estado;
	}

	public Instant getLlegadaEn() {
		return llegadaEn;
	}

	public Long getLlegadaPorCuentaId() {
		return llegadaPorCuentaId;
	}

	public ModalidadRecepcion getModalidad() {
		return modalidad;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	public Long getCoberturaId() {
		return coberturaId;
	}

	public Long getConvenioId() {
		return convenioId;
	}

	public String getObservacion() {
		return observacion;
	}

	public String getMotivoParticular() {
		return motivoParticular;
	}

	public Instant getValidadaEn() {
		return validadaEn;
	}

	public Instant getEnEsperaDesde() {
		return enEsperaDesde;
	}

	public Instant getLlamadaEn() {
		return llamadaEn;
	}

	public Instant getCerradaEn() {
		return cerradaEn;
	}

	public String getMotivoCierre() {
		return motivoCierre;
	}

	public long getVersion() {
		return version;
	}
}
