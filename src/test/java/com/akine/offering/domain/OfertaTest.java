package com.akine.offering.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La regla que AKINE-02.06 existe para proteger, hecha ejecutable: <b>los defaults del
 * {@link Servicio} son una propuesta inicial y jamas la fuente de verdad de una
 * {@link OfertaServicioConsultorio} ya creada</b>.
 *
 * <h2>Por que estos casos y no veinte</h2>
 *
 * <p>Los dos primeros son CA-M03-007-06 y CA-M06-006-06 —"modificar el intervalo o capacidad por
 * defecto no altera retroactivamente ofertas existentes"— literalmente convertidos en aserciones.
 * Los otros tres son los invariantes de {@link OfertaServicioConsultorio} que se pueden decidir
 * sin base: la baja logica exige motivo, la baja no borra la fila, y la vigencia invertida se
 * rechaza en el dominio y no solo con el CHECK {@code ck_oferta_vigencia_coherente}. El unique de
 * nombre comercial, la carrera de la baja y todo lo que necesita persistencia real quedan para el
 * test de integracion de la tarea que construya el repositorio.
 */
class OfertaTest {

	private static final Instant AHORA = Instant.parse("2026-08-27T12:00:00Z");
	private static final LocalDate DESDE = LocalDate.of(2026, 3, 1);
	private static final LocalDate HASTA = LocalDate.of(2026, 12, 1);

	private static Servicio servicio() {
		return new Servicio(
				"KINE-DEPORTIVA",
				"Kinesiologia deportiva",
				"Rehabilitacion orientada a deportistas",
				Naturaleza.CLINICO,
				Modalidad.INDIVIDUAL,
				false,
				false);
	}

	private static OfertaServicioConsultorio oferta(Long servicioId) {
		return new OfertaServicioConsultorio(
				1L,
				10L,
				servicioId,
				"Kinesiologia deportiva vespertina",
				"Turnos de 18 a 21hs",
				Modalidad.GRUPAL,
				60,
				5,
				null,
				null,
				null,
				false,
				true,
				true,
				true,
				true,
				DESDE,
				HASTA);
	}

	@Test
	@DisplayName("Una oferta no hereda los defaults del servicio al editarse")
	void una_oferta_no_hereda_los_defaults_del_servicio_al_editarse() {
		Servicio servicio = servicio();
		OfertaServicioConsultorio oferta = oferta(1L);

		// El servicio propone INDIVIDUAL / sin caso clinico / sin registro clinico. La oferta,
		// deliberadamente, se creo con lo contrario en los tres campos: RF-M06-006 dice que el
		// default no reemplaza la configuracion concreta de la oferta, y esto lo demuestra desde
		// el momento mismo de la creacion, no solo despues de un cambio posterior.
		assertThat(servicio.getModalidadDefault()).isEqualTo(Modalidad.INDIVIDUAL);
		assertThat(oferta.getModalidad())
				.as("la oferta manda con su propia modalidad, no la propuesta del servicio")
				.isEqualTo(Modalidad.GRUPAL);

		assertThat(servicio.isRequiereCasoClinicoDefault()).isFalse();
		assertThat(oferta.isRequiereCasoClinico())
				.as("idem para requiere_caso_clinico: la oferta decidio explicitamente")
				.isTrue();

		assertThat(servicio.isGeneraRegistroClinicoDefault()).isFalse();
		assertThat(oferta.isGeneraRegistroClinico())
				.as("idem para genera_registro_clinico")
				.isTrue();

		// Editar la oferta tampoco la acerca al servicio: no hay ningun camino de codigo que
		// vuelva a leer el default. Esto solo lo demuestra por ausencia: no existe un metodo en
		// OfertaServicioConsultorio que reciba un Servicio, y updateDatos no lo necesita.
		oferta.updateDatos(
				"Kinesiologia deportiva vespertina (renombrada)",
				null, null, null, null, null, null, false, null, false,
				null, null, null, null, null, null, null, false);

		assertThat(oferta.getModalidad())
				.as("editar otros campos no toca la modalidad, y mucho menos la vuelve a copiar")
				.isEqualTo(Modalidad.GRUPAL);
	}

	@Test
	@DisplayName("Cambiar un default del servicio no toca las ofertas existentes")
	void cambiar_un_default_del_servicio_no_toca_las_ofertas_existentes() {
		Servicio servicio = servicio();
		OfertaServicioConsultorio oferta = oferta(1L);

		Modalidad modalidadOfertaAntes = oferta.getModalidad();
		boolean requiereCasoClinicoAntes = oferta.isRequiereCasoClinico();
		boolean generaRegistroClinicoAntes = oferta.isGeneraRegistroClinico();

		// Se cambian los tres defaults del servicio, en la direccion contraria a como quedo la
		// oferta, para que cualquier filtracion sea imposible de no notar.
		servicio.updateDatos(
				null, null, null,
				Modalidad.GRUPAL,
				true,
				true);

		assertThat(servicio.getModalidadDefault()).isEqualTo(Modalidad.GRUPAL);
		assertThat(servicio.isRequiereCasoClinicoDefault()).isTrue();
		assertThat(servicio.isGeneraRegistroClinicoDefault()).isTrue();

		// La oferta, sin tocarla, sigue exactamente igual que antes del cambio en el servicio.
		assertThat(oferta.getModalidad())
				.as("CA-M06-006-06: modificar el default no altera retroactivamente la oferta")
				.isEqualTo(modalidadOfertaAntes);
		assertThat(oferta.isRequiereCasoClinico()).isEqualTo(requiereCasoClinicoAntes);
		assertThat(oferta.isGeneraRegistroClinico()).isEqualTo(generaRegistroClinicoAntes);
	}

	@Test
	@DisplayName("La baja exige motivo: sin el, la auditoria no responde por que")
	void la_baja_exige_motivo() {
		assertThatThrownBy(() -> servicio().deactivate(AHORA, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> servicio().deactivate(AHORA, "   "))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> oferta(1L).deactivate(AHORA, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> oferta(1L).deactivate(AHORA, ""))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("La baja es logica: no borra la fila, solo cambia su estado")
	void la_baja_es_logica_y_conserva_la_fila() {
		OfertaServicioConsultorio oferta = oferta(1L);
		String nombreOriginal = oferta.getNombreComercial();
		Long servicioIdOriginal = oferta.getServicioId();

		oferta.deactivate(AHORA, "La sede dejo de ofrecer este turno vespertino");

		assertThat(oferta.isActive()).isFalse();
		assertThat(oferta.getDeletedAt()).isEqualTo(AHORA);
		assertThat(oferta.getDeactivationReason())
				.isEqualTo("La sede dejo de ofrecer este turno vespertino");
		assertThat(oferta.isOperable()).isFalse();

		// La fila sigue ahi, con todos sus datos: la baja logica cambia estado, no borra.
		assertThat(oferta.getNombreComercial()).isEqualTo(nombreOriginal);
		assertThat(oferta.getServicioId()).isEqualTo(servicioIdOriginal);
		assertThat(oferta.estaVigente(DESDE.plusDays(1)))
				.as("una vez dada de baja, ya no se ofrece para nada nuevo, aunque este dentro de "
						+ "su ventana operativa")
				.isFalse();
	}

	@Test
	@DisplayName("Una vigencia invertida es rechazada")
	void una_vigencia_invertida_es_rechazada() {
		assertThatThrownBy(() -> new OfertaServicioConsultorio(
				1L, 10L, 1L, "Oferta invertida", null,
				Modalidad.INDIVIDUAL, 60, 1, null, null, null,
				false, false, false, false, false,
				HASTA, DESDE))
				.as("vigenciaHasta anterior a vigenciaDesde")
				.isInstanceOf(IllegalArgumentException.class);

		// El limite superior es EXCLUSIVO: el empate tambien se rechaza, seria una oferta
		// reservable durante cero dias.
		assertThatThrownBy(() -> new OfertaServicioConsultorio(
				1L, 10L, 1L, "Oferta de un dia cero", null,
				Modalidad.INDIVIDUAL, 60, 1, null, null, null,
				false, false, false, false, false,
				DESDE, DESDE))
				.as("vigenciaHasta igual a vigenciaDesde")
				.isInstanceOf(IllegalArgumentException.class);
	}
}
