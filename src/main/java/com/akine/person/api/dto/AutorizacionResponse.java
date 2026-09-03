package com.akine.person.api.dto;

import com.akine.person.application.AutorizacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una autorizacion tal como sale de la API, con su saldo (RF-M17-003) y sus alertas (RF-M17-006).
 *
 * <p><b>{@code cantidadConsumida} vale siempre cero en esta version.</b> RN-M17-001 separa
 * autorizado de consumido, y quien mueve el consumo es la sesion clinica en una integracion
 * posterior; el saldo que se devuelve hoy es el inicial. Los tres numeros viajan igual porque
 * RF-M17-003 los pide y porque el dia que la resta se mueva el contrato no cambia.
 *
 * <p>{@code vigente}, {@code vencida}, {@code agotada}, {@code habilita} y {@code diasParaVencer}
 * se calculan al leer. {@code estadoAutorizacion} es lo unico persistido y solo toma los cuatro
 * valores que decide una persona: VENCIDA y AGOTADA no son estados guardados, porque un job que no
 * corre dejaria autorizaciones vencidas que el sistema cree vigentes.
 *
 * <p>Las seis columnas {@code convenio*} y {@code requeria*} son la COPIA congelada de lo que el
 * convenio exigia al registrar, en pasado a proposito. Vienen todas en null cuando no habia
 * convenio resoluble ese dia, y eso es un estado legitimo: la autorizacion se registra igual.
 */
@Schema(description = "Autorizacion de un financiador para un paciente")
public record AutorizacionResponse(

		@Schema(description = "Identificador de la autorizacion", example = "77")
		long id,

		@Schema(description = "Paciente autorizado", example = "1204")
		long personaId,

		@Schema(description = "Sede que la gestiono. El convenio que la respalda es de la sede",
				example = "7")
		long consultorioId,

		@Schema(description = "Cobertura contra la que el financiador autorizo", example = "412")
		long coberturaId,

		@Schema(description = "Orden medica que la respalda, si hay", example = "51")
		Long ordenMedicaId,

		@Schema(description = "Practica autorizada", example = "33")
		long practicaId,

		@Schema(description = "Numero que devolvio el financiador", example = "AUT-99120034")
		String numero,

		@Schema(
				description = "PENDIENTE, APROBADA, OBSERVADA o RECHAZADA. Los cuatro que decide "
						+ "una persona. VENCIDA y AGOTADA NO estan aca: son los campos calculados "
						+ "vencida y agotada",
				example = "APROBADA",
				allowableValues = {"PENDIENTE", "APROBADA", "OBSERVADA", "RECHAZADA"})
		String estadoAutorizacion,

		@Schema(description = "Por que se observo o se rechazo")
		String motivo,

		@Schema(description = "Sesiones otorgadas. Null = sin tope declarado", example = "10")
		Integer cantidadAutorizada,

		@Schema(description = "Sesiones ya consumidas. HOY SIEMPRE 0: el consumo clinico es una "
				+ "integracion posterior (RF-M17-004)", example = "0")
		int cantidadConsumida,

		@Schema(description = "Autorizadas menos consumidas. Null si no hay tope. RF-M17-003",
				example = "10")
		Integer saldo,

		@Schema(description = "Primer dia en que habilita", example = "2026-09-01")
		LocalDate vigenciaDesde,

		@Schema(description = "ULTIMO dia, INCLUSIVE. Null = sin vencimiento",
				example = "2026-12-31")
		LocalDate vigenciaHasta,

		@Schema(description = "La fecha consultada cae dentro de la vigencia")
		boolean vigente,

		@Schema(description = "Ya paso su fin de vigencia. Vencida NO es dada de baja")
		boolean vencida,

		@Schema(description = "Sin saldo. Una autorizacion sin tope declarado nunca esta agotada")
		boolean agotada,

		@Schema(description = "Habilita a atender ese dia: activa, APROBADA, vigente y con saldo. "
				+ "Es el veredicto que consulta la elegibilidad, y consultarlo NO consume nada")
		boolean habilita,

		@Schema(description = "Dias que faltan para el vencimiento. Negativo si ya vencio, null si "
				+ "no vence. Es la alerta de RF-M17-006", example = "90")
		Long diasParaVencer,

		@Schema(description = "COPIA: convenio congelado al registrar. Null si no habia ninguno "
				+ "resoluble ese dia", example = "12")
		Long convenioId,

		@Schema(description = "COPIA del codigo del convenio al registrar", example = "CONV-OSDE")
		String convenioCodigo,

		@Schema(description = "COPIA del nombre al registrar. Puede diferir del vivo, y ese es el "
				+ "punto")
		String convenioNombre,

		@Schema(description = "COPIA de convenio.requiere_orden al registrar. En pasado: dice lo "
				+ "que el convenio pedia ESE dia")
		Boolean requeriaOrden,

		@Schema(description = "COPIA de convenio.requiere_autorizacion al registrar")
		Boolean requeriaAutorizacion,

		@Schema(description = "COPIA de convenio.requiere_credencial al registrar")
		Boolean requeriaCredencial,

		@Schema(description = "Instante del congelamiento. Explica por que esta copia puede decir "
				+ "algo distinto del convenio de hoy")
		Instant referenciaCapturadaEl,

		@Schema(description = "Adjunto vinculado con el comprobante", example = "908")
		Long adjuntoId,

		@Schema(description = "Notas administrativas")
		String observaciones,

		@Schema(description = "Ciclo de vida: ACTIVA o INACTIVA. NO es el estado de la "
				+ "autorizacion ni su vigencia", example = "ACTIVA",
				allowableValues = {"ACTIVA", "INACTIVA"})
		String estado,

		@Schema(description = "Instante de la baja logica")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja")
		String deactivationReason,

		@Schema(description = "Version para el control optimista", example = "0")
		long version) {

	public static AutorizacionResponse de(AutorizacionView vista) {
		return new AutorizacionResponse(
				vista.id(),
				vista.personaId(),
				vista.consultorioId(),
				vista.coberturaId(),
				vista.ordenMedicaId(),
				vista.practicaId(),
				vista.numero(),
				vista.estadoAutorizacion(),
				vista.motivo(),
				vista.cantidadAutorizada(),
				vista.cantidadConsumida(),
				vista.saldo(),
				vista.vigenciaDesde(),
				vista.vigenciaHasta(),
				vista.vigente(),
				vista.vencida(),
				vista.agotada(),
				vista.habilita(),
				vista.diasParaVencer(),
				vista.convenioId(),
				vista.convenioCodigo(),
				vista.convenioNombre(),
				vista.requeriaOrden(),
				vista.requeriaAutorizacion(),
				vista.requeriaCredencial(),
				vista.referenciaCapturadaEl(),
				vista.adjuntoId(),
				vista.observaciones(),
				vista.estado(),
				vista.deletedAt(),
				vista.deactivationReason(),
				vista.version());
	}
}
