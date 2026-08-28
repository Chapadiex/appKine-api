package com.akine.person.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los invariantes de la identidad administrativa, sin base de datos.
 *
 * <p>El mas importante no se prueba con un assert sino con la ausencia de una API: <b>esta clase
 * no tiene ninguna forma de convertir a la persona en paciente</b>. No hay setter, no hay campo y
 * no hay relacion. Lo que si se puede probar es lo otro: que las claves de busqueda nunca queden
 * viejas, y que el par tipo/numero de documento sea indivisible.
 */
@DisplayName("Persona")
class PersonaTest {

	private static final long ORG_ID = 10L;

	@Test
	@DisplayName("Las claves de busqueda se derivan al construir, sin recibirlas")
	void las_claves_se_derivan_al_construir() {
		Persona persona = conNombre("Pérez", "Ana María");

		assertThat(persona.getApellido()).isEqualTo("Pérez");
		assertThat(persona.getApellidoClave()).isEqualTo("PEREZ");
		assertThat(persona.getNombreClave()).isEqualTo("ANA MARIA");
	}

	@Test
	@DisplayName("Editar el apellido recalcula su clave: una fila nunca queda invisible en la busqueda")
	void editar_recalcula_las_claves() {
		// Es el bug mas silencioso posible de este modulo: apellido nuevo con clave vieja deja a
		// la persona fuera de la busqueda por apellido sin ningun error a la vista.
		Persona persona = conNombre("Perez", "Ana");

		persona.updateDatos(null, null, "Gómez", null, null, null, null, null);

		assertThat(persona.getApellido()).isEqualTo("Gómez");
		assertThat(persona.getApellidoClave()).isEqualTo("GOMEZ");
	}

	@Test
	@DisplayName("Una persona sin documento es valida, y su clave queda en null")
	void una_persona_sin_documento_es_valida() {
		Persona persona = conNombre("Perez", "Ana");

		assertThat(persona.getTipoDocumento()).isNull();
		assertThat(persona.getNumeroDocumento()).isNull();
		assertThat(persona.getDocumentoClave()).isNull();
	}

	@Test
	@DisplayName("Un tipo de documento sin numero se rechaza: el par es indivisible")
	void un_tipo_sin_numero_se_rechaza() {
		// La base tambien lo verifica (ck_persona_documento_completo). Aca se rechaza antes para
		// que el error sea un 400 con mensaje util y no un 500 traducido de una violacion de CHECK.
		assertThatThrownBy(() -> new Persona(
				ORG_ID, TipoDocumento.DNI, "   ", "Perez", "Ana", null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("numero de documento");
	}

	@Test
	@DisplayName("El documento se guarda como se tipeo y se compara normalizado")
	void el_documento_conserva_su_forma_y_normaliza_su_clave() {
		Persona persona = new Persona(
				ORG_ID, TipoDocumento.DNI, "12.345.678", "Perez", "Ana", null, null, null, null);

		assertThat(persona.getNumeroDocumento()).isEqualTo("12.345.678");
		assertThat(persona.getDocumentoClave()).isEqualTo("12345678");
	}

	@Test
	@DisplayName("Un PATCH con todo en null no cambia nada: null significa 'no lo toques'")
	void el_patch_vacio_no_borra_nada() {
		// La consecuencia conocida: con esta firma no se puede VACIAR un telefono ya cargado.
		// Es deliberado, y el dia que haga falta se resuelve con un centinela explicito en el DTO.
		Persona persona = new Persona(
				ORG_ID, TipoDocumento.DNI, "12345678", "Perez", "Ana",
				LocalDate.of(1985, 3, 14), "ana@example.com", "1155550000", "sin obra social");

		persona.updateDatos(null, null, null, null, null, null, null, null);

		assertThat(persona.getTelefono()).isEqualTo("1155550000");
		assertThat(persona.getEmail()).isEqualTo("ana@example.com");
		assertThat(persona.getNumeroDocumento()).isEqualTo("12345678");
		assertThat(persona.getNotas()).isEqualTo("sin obra social");
	}

	@Test
	@DisplayName("La baja logica exige motivo y deja la persona no operable")
	void la_baja_exige_motivo() {
		Persona persona = conNombre("Perez", "Ana");

		assertThatThrownBy(() -> persona.deactivate(Instant.now(), "  "))
				.isInstanceOf(IllegalArgumentException.class);

		persona.deactivate(Instant.parse("2026-08-27T12:00:00Z"), "duplicada de la ficha 12");

		assertThat(persona.isActive()).isFalse();
		assertThat(persona.isOperable()).isFalse();
		assertThat(persona.getDeactivationReason()).isEqualTo("duplicada de la ficha 12");
	}

	@Test
	@DisplayName("Una persona recien creada no tiene ninguna forma de declararse paciente")
	void una_persona_no_puede_volverse_paciente_sola() {
		// El assert es sobre la API, no sobre un valor: si algun dia aparece un setter, un campo
		// o una relacion que permita esto, esta clase deja de garantizar RF-M07-010 y el unico
		// lugar donde se ve es aca.
		assertThat(Persona.class.getDeclaredFields())
				.extracting(java.lang.reflect.Field::getName)
				.doesNotContain("esPaciente", "perfilPaciente", "perfilPacienteId");
		assertThat(Persona.class.getDeclaredMethods())
				.extracting(java.lang.reflect.Method::getName)
				.doesNotContain("setEsPaciente", "activarPerfil", "convertirEnPaciente");
	}

	private static Persona conNombre(String apellido, String nombre) {
		return new Persona(ORG_ID, null, null, apellido, nombre, null, null, null, null);
	}
}
