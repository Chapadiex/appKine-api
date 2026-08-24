package com.akine.organization.application;

import java.time.ZoneId;

/**
 * Validacion de una zona horaria de sede.
 *
 * <h2>Solo identificadores de la base IANA</h2>
 *
 * <p>Se acepta unicamente un identificador que este en {@link ZoneId#getAvailableZoneIds()}.
 * Se <b>rechazan</b> los offsets fijos ({@code -03:00}, {@code UTC-3}, {@code Z}), las
 * abreviaturas de tres letras ({@code ART}, {@code EST}) y las formas {@code GMT+X}.
 *
 * <p>El motivo no es purismo: <b>un offset fijo no conoce el horario de verano</b>. El dia que
 * un pais lo reinstaure, toda la agenda de esa sede se corre una hora sin que nadie lo note, y
 * lo que se corre son turnos, sesiones y cortes de caja ya registrados. {@code ZoneId.of} acepta
 * {@code "-03:00"} sin chistar y devuelve un {@code ZoneOffset}, asi que llamarlo solo no
 * alcanza: hay que comprobar la pertenencia al catalogo IANA.
 *
 * <p>Las abreviaturas de tres letras estan ademas obsoletas en {@code java.time} y son
 * ambiguas entre paises ({@code CST} son tres husos distintos).
 */
final class ZonasHorarias {

	private ZonasHorarias() {
		// Utilidad sin estado.
	}

	/**
	 * Devuelve la zona validada.
	 *
	 * @throws IllegalArgumentException (400) si es nula, vacia o no es un identificador IANA
	 */
	static String exigirValida(String zona) {
		if (zona == null || zona.isBlank()) {
			throw new IllegalArgumentException(
					"La zona horaria de la sede es obligatoria: sin ella no hay forma de calcular "
							+ "el dia operativo ni la agenda");
		}
		String candidata = zona.strip();
		if (!ZoneId.getAvailableZoneIds().contains(candidata)) {
			throw new IllegalArgumentException(
					"La zona horaria debe ser un identificador de la base IANA, por ejemplo "
							+ "America/Argentina/Cordoba. No se aceptan offsets fijos ni "
							+ "abreviaturas: un offset fijo no conoce el horario de verano y "
							+ "correria la agenda una hora sin aviso");
		}
		return candidata;
	}
}
