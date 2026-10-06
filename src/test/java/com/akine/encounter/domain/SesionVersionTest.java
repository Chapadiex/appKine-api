package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.SesionNoCerradaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las invariantes del historial de una sesion cerrada (AKINE-06.06) que viven en la entidad y no
 * en el servicio: por eso valen para cualquier camino futuro que no pase por
 * {@code SesionService}.
 */
@DisplayName("SesionVersion")
class SesionVersionTest {

	private static final Instant CIERRE = Instant.parse("2026-09-15T12:48:00Z");

	@Test
	@DisplayName("Una sesion abierta no tiene version 1: el historial lo inaugura el cierre")
	void la_abierta_no_tiene_original() {
		assertThatThrownBy(() -> SesionVersion.original(sesion(null)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("La version 1 lleva el instante y el autor DEL CIERRE, y ningun motivo")
	void el_original_es_el_cierre() {
		Sesion sesion = cerrada(null);

		SesionVersion original = SesionVersion.original(sesion);

		assertThat(original.getNumeroVersion()).isEqualTo(1);
		assertThat(original.esEnmienda()).isFalse();
		assertThat(original.getMotivoEnmienda()).isNull();
		assertThat(original.getRegistradaEn()).isEqualTo(CIERRE);
		assertThat(original.getRegistradaPor()).isEqualTo(99L);
		assertThat(original.getNotaDeCierre()).isEqualTo("Terapia manual");
	}

	@Test
	@DisplayName("El motivo de la enmienda se guarda sin espacios de borde")
	void el_motivo_se_normaliza() {
		Sesion sesion = cerrada(null);
		sesion.enmendar(contenido("Nota corregida"), Asistencia.PRESENTE);

		SesionVersion enmienda = SesionVersion.enmienda(
				sesion, "  Lateralidad corregida  ", CIERRE.plusSeconds(60), 8L);

		assertThat(enmienda.getNumeroVersion()).isEqualTo(2);
		assertThat(enmienda.esEnmienda()).isTrue();
		assertThat(enmienda.getMotivoEnmienda()).isEqualTo("Lateralidad corregida");
		assertThat(enmienda.getRegistradaPor()).as("el autor es por version").isEqualTo(8L);
	}

	@Test
	@DisplayName("Un motivo mas largo que la columna se rechaza en la entidad, no truncado por MySQL")
	void el_motivo_largo_se_rechaza() {
		// Del otro lado no hay un 400 prolijo: hay un VARCHAR truncado que el handler global
		// devuelve como 409 "choca con un dato existente", y la enmienda se pierde.
		Sesion sesion = cerrada(null);
		sesion.enmendar(contenido("Nota corregida"), Asistencia.PRESENTE);

		assertThatThrownBy(() -> SesionVersion.enmienda(
				sesion, "x".repeat(SesionVersion.MOTIVO_MAXIMO + 1), CIERRE, 8L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Una version sin autor no se puede escribir: el historial dice siempre quien")
	void la_version_exige_autor() {
		Sesion sesion = cerrada(null);
		sesion.enmendar(contenido("Nota corregida"), Asistencia.PRESENTE);

		assertThatThrownBy(() -> SesionVersion.enmienda(sesion, "motivo", CIERRE, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Una sesion abierta no se enmienda: se guarda")
	void la_abierta_no_se_enmienda() {
		assertThatThrownBy(() -> sesion(null).enmendar(contenido("Nota"), Asistencia.PRESENTE))
				.isInstanceOf(SesionNoCerradaException.class);
	}

	@Test
	@DisplayName("Una sesion sin caso no recibe numero de caso al cerrar")
	void sin_caso_no_hay_numero_de_caso() {
		Sesion sesion = sesion(null);
		CierreDeSesion cierre =
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null);

		assertThatThrownBy(() -> sesion.cerrar(cierre, 8, 3, CIERRE, 99L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(sesion.estaCerrada()).isFalse();
	}

	private static Sesion sesion(Long casoId) {
		Sesion sesion = new Sesion(1L, 7L, 88L, casoId, 301L, 42L, 31L, Instant.EPOCH, 99L);
		ReflectionTestUtils.setField(sesion, "id", 1L);
		return sesion;
	}

	private static Sesion cerrada(Long casoId) {
		Sesion sesion = sesion(casoId);
		sesion.cerrar(new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null,
				null), 8, null, CIERRE, 99L);
		return sesion;
	}

	private static ContenidoDeSesion contenido(String notaDeCierre) {
		return new ContenidoDeSesion("dolor lumbar", 4, "Lumbar", Lateralidad.DERECHA,
				Evolucion.MEJOR, null, null, notaDeCierre, null, null, null, null);
	}
}
