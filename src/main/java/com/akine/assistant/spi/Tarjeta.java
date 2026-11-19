package com.akine.assistant.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que una accion va a grabar, tal como lo ve y lo corrige la persona. El mismo objeto viaja en la
 * propuesta y vuelve en la confirmacion; los campos de solo lectura (nombres, saldos, opciones) se
 * ignoran a la vuelta.
 */
public sealed interface Tarjeta {

	/** Si esta tarjeta puede confirmar una accion de ese tipo. */
	boolean esDe(TipoDeAccion tipo);

	/** La persona de la que habla, o {@code null}: se compara con la de la propuesta al confirmar. */
	default Long persona() {
		return null;
	}

	/** Una opcion entre las que elegir (un turno, una oferta, un caso, un posible duplicado). */
	record Opcion(long id, String etiqueta) {
	}

	/**
	 * @param claseId la clase elegida; {@code null} si hay varias y falta elegir
	 * @param clases  las clases de hoy que coinciden, cada una con sus inscriptos ya marcados
	 */
	record TarjetaDeAsistencia(Long claseId, List<ClaseEnTarjeta> clases) implements Tarjeta {
		public TarjetaDeAsistencia {
			clases = clases == null ? List.of() : List.copyOf(clases);
		}

		@Override
		public boolean esDe(TipoDeAccion tipo) {
			return tipo == TipoDeAccion.ASISTENCIA_CLASE;
		}
	}

	record ClaseEnTarjeta(long claseId, String titulo, Instant inicio, String estado, List<ItemDeAsistencia> items) {
		public ClaseEnTarjeta {
			items = items == null ? List.of() : List.copyOf(items);
		}
	}

	/**
	 * @param resultado    {@code PRESENTE}, {@code AUSENTE} o {@code null} (sin marcar: no se graba)
	 * @param yaRegistrado el resultado que ya tenia, o {@code null}
	 */
	record ItemDeAsistencia(long inscripcionId, String nombre, String resultado, String yaRegistrado) {
	}

	/**
	 * @param total        lo que se cobra; {@code null} si no se dijo
	 * @param medio        el medio de pago; {@code null} si no se dijo (no se asume ninguno)
	 * @param imputaciones las deudas del paciente con lo que se le aplica a cada una
	 * @param anticipo     lo que queda como saldo a favor
	 * @param cajaAbierta  si la sede tiene la caja abierta (el efectivo la exige)
	 */
	record TarjetaDePago(long personaId, String nombre, BigDecimal total, String medio, String moneda,
			List<ImputacionEnTarjeta> imputaciones, BigDecimal anticipo, boolean cajaAbierta) implements Tarjeta {
		public TarjetaDePago {
			imputaciones = imputaciones == null ? List.of() : List.copyOf(imputaciones);
		}

		@Override
		public boolean esDe(TipoDeAccion tipo) {
			return tipo == TipoDeAccion.PAGO;
		}

		@Override
		public Long persona() {
			return personaId;
		}
	}

	record ImputacionEnTarjeta(long obligacionId, String concepto, LocalDate fecha, BigDecimal saldo,
			BigDecimal importe) {
	}

	/**
	 * @param turnoId             el turno de hoy; {@code null} si es una atencion sin turno
	 * @param ofertaId            la oferta de una atencion sin turno
	 * @param casoId              el caso clinico, si la oferta lo exige
	 * @param tratamientos        lo dicho, con la practica que se reconocio (o sin ella)
	 * @param nota                la nota de cierre: la frase original, editable
	 * @param cerrar              si la sesion se cierra al confirmar
	 * @param tratamientosPrevios los que ya tenia la sesion abierta de ese turno
	 */
	record TarjetaDeAtencion(long personaId, String nombre, Long turnoId, List<Opcion> turnos, Long ofertaId,
			List<Opcion> ofertas, Long casoId, List<Opcion> casos, List<TratamientoEnTarjeta> tratamientos,
			String nota, boolean cerrar, int tratamientosPrevios) implements Tarjeta {
		public TarjetaDeAtencion {
			turnos = turnos == null ? List.of() : List.copyOf(turnos);
			ofertas = ofertas == null ? List.of() : List.copyOf(ofertas);
			casos = casos == null ? List.of() : List.copyOf(casos);
			tratamientos = tratamientos == null ? List.of() : List.copyOf(tratamientos);
		}

		@Override
		public boolean esDe(TipoDeAccion tipo) {
			return tipo == TipoDeAccion.ATENCION;
		}

		@Override
		public Long persona() {
			return personaId;
		}
	}

	/** @param practicaId la practica reconocida; {@code null} si hay que elegirla a mano */
	record TratamientoEnTarjeta(String texto, Long practicaId, String nombre) {
	}

	record TarjetaDeLlegada(long personaId, String nombre, long turnoId, Instant hora) implements Tarjeta {
		@Override
		public boolean esDe(TipoDeAccion tipo) {
			return tipo == TipoDeAccion.LLEGADA;
		}

		@Override
		public Long persona() {
			return personaId;
		}
	}

	/**
	 * @param personaId                {@code null} en el alta
	 * @param version                  la version leida de la persona que se edita
	 * @param datos                    lo que se carga o se cambia
	 * @param anterior                 los datos actuales de la persona que se edita
	 * @param posiblesDuplicados       personas parecidas a la que se da de alta
	 * @param confirmaPosibleDuplicado la persona vio los duplicados y sigue adelante
	 */
	record TarjetaDePaciente(Long personaId, Long version, DatosDePersona datos, String tipoDocumento,
			DatosDePersona anterior, List<Opcion> posiblesDuplicados, boolean confirmaPosibleDuplicado)
			implements Tarjeta {
		public TarjetaDePaciente {
			posiblesDuplicados = posiblesDuplicados == null ? List.of() : List.copyOf(posiblesDuplicados);
			datos = datos == null ? DatosDePersona.VACIO : datos;
		}

		@Override
		public boolean esDe(TipoDeAccion tipo) {
			return personaId == null ? tipo == TipoDeAccion.PACIENTE_ALTA : tipo == TipoDeAccion.PACIENTE_EDICION;
		}

		@Override
		public Long persona() {
			return personaId;
		}
	}
}
