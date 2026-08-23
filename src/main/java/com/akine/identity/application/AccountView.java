package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;

import java.time.Instant;

/**
 * Lo que {@code application} devuelve de una cuenta despues de una transicion administrativa.
 *
 * <h2>Por que existe, si la entity ya tiene estos datos</h2>
 *
 * <p>Porque la entity <b>no puede cruzar el borde del servicio</b> (AGENT.md sec. 4), y no es
 * una regla de estilo: {@code CodingConventionsTest.entities_no_salen_por_la_api} falla el build
 * si un DTO de {@code api} toca una clase anotada con {@code @Entity}. Los motivos concretos son
 * tres: serializar la entity filtraria columnas internas —el hash de la contrasena, el contador
 * de intentos fallidos—, dispararia lazy loading fuera de transaccion, y ataria el contrato HTTP
 * al esquema de la base, donde renombrar una columna pasa a ser un cambio incompatible de API.
 *
 * <p>Es el mismo patron que {@code OrganizationView} en {@code organization}: la capa
 * {@code api} construye su DTO desde esta vista y nunca desde {@code Cuenta}.
 *
 * <p><b>Lo que deliberadamente NO trae:</b> {@code passwordHash}, {@code intentosFallidos} y
 * {@code ultimoLoginEn}. El primero es una credencial; los otros dos le contarian a un
 * administrador de la organizacion A cosas sobre la actividad global de una persona que tambien
 * trabaja en B, y la identidad es cross-tenant (ADR-0019).
 *
 * @param id          identificador de la cuenta
 * @param email       direccion tal como se registro
 * @param nombre      nombre de la persona
 * @param apellido    apellido de la persona
 * @param estado      estado resultante de la transicion
 * @param bloqueadaEn momento del bloqueo, {@code null} si no esta bloqueada
 * @param actualizada ultima modificacion
 */
public record AccountView(
		long id,
		String email,
		String nombre,
		String apellido,
		String estado,
		Instant bloqueadaEn,
		Instant actualizada) {

	static AccountView de(Cuenta cuenta) {
		return new AccountView(
				cuenta.getId(),
				cuenta.getEmail(),
				cuenta.getNombre(),
				cuenta.getApellido(),
				cuenta.getEstado().name(),
				cuenta.getBloqueadaEn(),
				cuenta.getUpdatedAt());
	}
}
