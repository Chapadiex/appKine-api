package com.akine.person.domain;

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
import java.time.LocalDate;

/**
 * Identidad administrativa de una persona dentro de una organizacion (M07, RN-M07-005).
 *
 * <h2>Una Persona NO es un Paciente, y esta clase existe para que eso siga siendo cierto</h2>
 *
 * <p>RN-M07-005 lo fija: "la identidad base debe evolucionar hacia Persona; Paciente representa un
 * perfil clinico de esa persona". Alguien que viene a una clase de pilates es una Persona y nada
 * mas (RN-M07-006). Que ADEMAS sea paciente se declara insertando un {@link PerfilPaciente}, que
 * es una fila de otra tabla.
 *
 * <p><b>Por eso esta clase no tiene ningun campo {@code esPaciente}, ni una relacion JPA hacia el
 * perfil.</b> No es una omision: es la garantia. Con un booleano, cualquier camino de escritura
 * podria convertir a alguien en paciente sin querer —un {@code setEsPaciente(true)} en el medio de
 * un alta de inscripcion— y eso es exactamente lo que RF-M07-010 prohibe. Sin el campo, la unica
 * forma de crear un paciente es insertar en otra tabla, que es un acto que se ve en el diff y que
 * pasa por {@code PerfilPacienteService}.
 *
 * <p><b>Y esta clase tampoco conoce nada clinico.</b> No hay historia clinica, no hay caso, no hay
 * sesion: son M09 y M14, viven en el modulo {@code clinical} que todavia no existe, y la regla
 * maestra 1 los separa. {@link #notas} es texto ADMINISTRATIVO y RN-M07-003 lo dice: la
 * informacion clinica se mantiene en los modulos clinicos.
 *
 * <h2>Las claves de busqueda las escribe esta clase, siempre</h2>
 *
 * <p>{@link #documentoClave}, {@link #apellidoClave}, {@link #nombreClave} y
 * {@link #telefonoClave} nunca se reciben por parametro ni tienen setter: se derivan de su campo
 * visible con {@link ClaveDeBusqueda} en el constructor y en {@link #updateDatos}. Es lo que hace
 * imposible que una fila quede con un apellido nuevo y una clave vieja —y por lo tanto invisible
 * en la busqueda por apellido, que es la forma mas silenciosa de perder una persona en el padron.
 *
 * <h2>Documento opcional, y el unique que igual funciona</h2>
 *
 * <p>Una persona sin documento es un estado legitimo (caso borde declarado de la etapa: menor sin
 * DNI, extranjero recien llegado, urgencia). {@link #tipoDocumento} y {@link #numeroDocumento} van
 * los dos o ninguno —lo verifica {@code ck_persona_documento_completo} y tambien
 * {@link #documento(TipoDocumento, String)}— y cuando no van, la fila queda fuera del unique
 * porque en MySQL varios NULL no colisionan. Ver la cabecera de V27.
 *
 * <h2>Ciclo de vida: las columnas existen, la baja es de 03.02</h2>
 *
 * <p>{@link #active}, {@link #deletedAt} y {@link #deactivationReason} estan mapeados y
 * {@link #deactivate} existe, pero <b>ningun endpoint de esta etapa lo llama</b>: RF-M07-005 es
 * 03.02. Estan desde ahora porque el unique de documento los necesita —el centinela
 * {@code deleted_key} se calcula sobre {@code deleted_at}— y agregarlos despues obligaria a
 * rehacer ese unique sobre una tabla ya poblada.
 */
@Entity
@Table(name = "persona")
public class Persona extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Tenant propietario. Sin {@code consultorioId}: la identidad es de la organizacion y no de
	 * la sede (DP-03, cabecera de V27). {@code updatable = false} porque mover una persona de
	 * organizacion no es una edicion, es un alta en la otra organizacion.
	 */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo_documento", length = 16)
	private TipoDocumento tipoDocumento;

	@Column(name = "numero_documento", length = 32)
	private String numeroDocumento;

	/** Derivada de {@link #numeroDocumento}. Nunca se recibe: ver la cabecera de la clase. */
	@Column(name = "documento_clave", length = 32)
	private String documentoClave;

	@Column(name = "apellido", nullable = false, length = 120)
	private String apellido;

	@Column(name = "nombre", nullable = false, length = 120)
	private String nombre;

	@Column(name = "apellido_clave", nullable = false, length = 120)
	private String apellidoClave;

	@Column(name = "nombre_clave", nullable = false, length = 120)
	private String nombreClave;

	@Column(name = "fecha_nacimiento")
	private LocalDate fechaNacimiento;

	@Column(name = "email", length = 320)
	private String email;

	@Column(name = "telefono", length = 40)
	private String telefono;

	@Column(name = "telefono_clave", length = 40)
	private String telefonoClave;

	/** Observaciones ADMINISTRATIVAS. Nada clinico entra aca (RN-M07-003). */
	@Column(name = "notas", length = 500)
	private String notas;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Persona() {
		// Requerido por JPA.
	}

	public Persona(
			Long organizationId,
			TipoDocumento tipoDocumento,
			String numeroDocumento,
			String apellido,
			String nombre,
			LocalDate fechaNacimiento,
			String email,
			String telefono,
			String notas) {

		this.organizationId =
				exigirNoNulo(organizationId, "La persona pertenece siempre a una organizacion");
		this.apellido = exigirTexto(apellido, "El apellido es obligatorio");
		this.nombre = exigirTexto(nombre, "El nombre es obligatorio");
		documento(tipoDocumento, numeroDocumento);
		this.fechaNacimiento = fechaNacimiento;
		this.email = vacioEsNulo(email);
		this.telefono = vacioEsNulo(telefono);
		this.notas = vacioEsNulo(notas);
		this.active = true;
		recalcularClaves();
	}

	/**
	 * Edicion parcial (RF-M07-003): cada valor {@code null} deja el campo como estaba.
	 *
	 * <p>Semantica de PATCH y no de PUT, misma convencion que {@code Espacio}, {@code Servicio} y
	 * {@code CatalogoConcepto}. La consecuencia que hay que conocer: <b>con esta firma no se puede
	 * BORRAR un telefono o un email ya cargado</b>, porque mandar {@code null} significa "no lo
	 * toques". Es deliberado en esta etapa —el caso real del mostrador es corregir un dato, no
	 * vaciarlo— y el dia que haga falta se resuelve con un valor centinela explicito en el DTO, no
	 * cambiando el significado de {@code null} para todos los campos a la vez.
	 *
	 * <p><b>El documento se edita distinto</b>: viaja como un par y {@code tipoDocumento} manda. Si
	 * llega un tipo, se reemplazan los dos; si no llega, no se toca ninguno. No hay forma de dejar
	 * un tipo sin numero — {@link #documento(TipoDocumento, String)} lo rechaza.
	 */
	public void updateDatos(
			TipoDocumento tipoDocumento,
			String numeroDocumento,
			String apellido,
			String nombre,
			LocalDate fechaNacimiento,
			String email,
			String telefono,
			String notas) {

		if (apellido != null) {
			this.apellido = exigirTexto(apellido, "El apellido es obligatorio");
		}
		if (nombre != null) {
			this.nombre = exigirTexto(nombre, "El nombre es obligatorio");
		}
		if (tipoDocumento != null) {
			documento(tipoDocumento, numeroDocumento);
		}
		if (fechaNacimiento != null) {
			this.fechaNacimiento = fechaNacimiento;
		}
		if (email != null) {
			this.email = vacioEsNulo(email);
		}
		if (telefono != null) {
			this.telefono = vacioEsNulo(telefono);
		}
		if (notas != null) {
			this.notas = vacioEsNulo(notas);
		}
		recalcularClaves();
	}

	/**
	 * Baja logica con motivo declarado (RN-M07-004).
	 *
	 * <p><b>Sin llamador en esta etapa.</b> RF-M07-005 es 03.02; el metodo existe porque el ciclo
	 * de vida es del dominio y no del endpoint, y porque las columnas que toca ya estan en V27 por
	 * el unique. No borra nada: la persona queda inactiva, sigue resolviendo por id y libera su
	 * documento para un alta nueva.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una persona exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando la persona admite ediciones y perfiles nuevos. */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	// =================================================================================
	// Invariantes internos
	// =================================================================================

	/**
	 * Fija el par tipo/numero, o lo deja entero en {@code null}.
	 *
	 * <p>El par es indivisible: un tipo sin numero no identifica a nadie y un numero sin tipo no
	 * se puede comparar entre paises. La base lo verifica con
	 * {@code ck_persona_documento_completo}, y aca se rechaza antes para que el error sea un 400
	 * con mensaje util y no un 500 traducido de una violacion de CHECK.
	 */
	private void documento(TipoDocumento tipo, String numero) {
		String numeroLimpio = vacioEsNulo(numero);
		if (tipo != null && numeroLimpio == null) {
			throw new IllegalArgumentException(
					"El numero de documento es obligatorio cuando se declara un tipo de documento");
		}
		this.tipoDocumento = numeroLimpio == null ? null : tipo;
		this.numeroDocumento = numeroLimpio;
	}

	/**
	 * Deriva las cuatro claves de busqueda de sus campos visibles.
	 *
	 * <p>Se llama desde el constructor y desde {@link #updateDatos}, que son los DOS unicos
	 * caminos por los que un campo visible cambia. Si alguien agrega un tercero, tiene que
	 * llamarla: una fila con el apellido nuevo y la clave vieja desaparece de la busqueda por
	 * apellido sin ningun error visible.
	 */
	private void recalcularClaves() {
		this.documentoClave = ClaveDeBusqueda.deDocumento(numeroDocumento);
		this.apellidoClave = ClaveDeBusqueda.deNombre(apellido);
		this.nombreClave = ClaveDeBusqueda.deNombre(nombre);
		this.telefonoClave = ClaveDeBusqueda.deTelefono(telefono);
	}

	private static String exigirTexto(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public TipoDocumento getTipoDocumento() {
		return tipoDocumento;
	}

	public String getNumeroDocumento() {
		return numeroDocumento;
	}

	public String getDocumentoClave() {
		return documentoClave;
	}

	public String getApellido() {
		return apellido;
	}

	public String getNombre() {
		return nombre;
	}

	public String getApellidoClave() {
		return apellidoClave;
	}

	public String getNombreClave() {
		return nombreClave;
	}

	public LocalDate getFechaNacimiento() {
		return fechaNacimiento;
	}

	public String getEmail() {
		return email;
	}

	public String getTelefono() {
		return telefono;
	}

	public String getTelefonoClave() {
		return telefonoClave;
	}

	public String getNotas() {
		return notas;
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
