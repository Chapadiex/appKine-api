package com.akine.encounter.infrastructure;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.domain.Lateralidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V65} contra MySQL real: {@code ck_tratamiento_lateralidad} admite {@code NO_APLICA}.
 *
 * <p>El defecto que corrige: {@code V55} declaro el CHECK con {@code IZQUIERDA, DERECHA,
 * BILATERAL}, pero el enum {@link Lateralidad}, los DTOs y el comentario de la propia columna
 * incluyen {@code NO_APLICA}. Registrar un tratamiento en una zona central (lumbar, cervical)
 * con {@code NO_APLICA} violaba el CHECK. Sin {@code V65}, {@link #no_aplica_se_acepta()} falla.
 *
 * <p>Se inserta <b>directo</b>, sin pasar por ningun servicio, y con
 * {@code FOREIGN_KEY_CHECKS = 0} en la conexion del test: lo que se ejerce es el CHECK, no las
 * FK hacia sesion, practica y espacio. Cada insert se borra al terminar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class TratamientoLateralidadMigrationIT {

	private static final long ORG = 9651L;

	private static final AtomicLong SECUENCIA = new AtomicLong(1L);

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("el CHECK admite exactamente los valores del enum Lateralidad, y NULL")
	void admite_todos_los_valores_del_enum() throws SQLException {
		for (Lateralidad valor : Lateralidad.values()) {
			assertThat(insertar(valor.name(), "Zona")).as("lateralidad %s", valor).isEqualTo(1);
		}
		assertThat(insertar(null, null)).as("lateralidad NULL sin zona").isEqualTo(1);
	}

	@Test
	@DisplayName("NO_APLICA se acepta: el defecto C-1")
	void no_aplica_se_acepta() throws SQLException {
		assertThat(insertar("NO_APLICA", "Columna lumbar")).isEqualTo(1);
	}

	@Test
	@DisplayName("un valor fuera del enum sigue rechazado por ck_tratamiento_lateralidad")
	void un_valor_desconocido_se_rechaza() {
		assertThatThrownBy(() -> insertar("ARRIBA", "Zona"))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ck_tratamiento_lateralidad");
	}

	@Test
	@DisplayName("NO_APLICA sin zona sigue rechazado por ck_tratamiento_lateralidad_con_zona")
	void no_aplica_sin_zona_se_rechaza() {
		assertThatThrownBy(() -> insertar("NO_APLICA", null))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ck_tratamiento_lateralidad_con_zona");
	}

	@Test
	@DisplayName("el CHECK conserva su nombre")
	void el_constraint_conserva_su_nombre() throws SQLException {
		try (Connection c = dataSource.getConnection();
		     PreparedStatement ps = c.prepareStatement("""
				SELECT constraint_name FROM information_schema.table_constraints
				 WHERE table_schema = DATABASE() AND table_name = 'tratamiento_realizado'
				   AND constraint_type = 'CHECK' AND constraint_name = 'ck_tratamiento_lateralidad'
				""");
		     var rs = ps.executeQuery()) {
			assertThat(rs.next()).isTrue();
		}
	}

	private int insertar(String lateralidad, String zona) throws SQLException {
		long orden = SECUENCIA.getAndIncrement();
		try (Connection c = dataSource.getConnection()) {
			try (Statement s = c.createStatement()) {
				s.execute("SET FOREIGN_KEY_CHECKS = 0");
			}
			try {
				int filas;
				try (PreparedStatement ps = c.prepareStatement("""
						INSERT INTO tratamiento_realizado
						       (organization_id, consultorio_id, sesion_id, orden, practica_id,
						        practica_codigo, practica_nombre, zona, lateralidad,
						        profesional_membership_id, registrado_en, registrado_por_cuenta_id,
						        version, created_at, updated_at)
						 VALUES (?, 1, 1, ?, 1, 'P-LAT', 'Practica sintetica', ?, ?, 1,
						         UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
						""")) {
					ps.setLong(1, ORG);
					ps.setLong(2, orden);
					ps.setString(3, zona);
					ps.setString(4, lateralidad);
					filas = ps.executeUpdate();
				}
				return filas;
			} finally {
				try (PreparedStatement del = c.prepareStatement(
						"DELETE FROM tratamiento_realizado WHERE organization_id = ?")) {
					del.setLong(1, ORG);
					del.executeUpdate();
				}
				try (Statement s = c.createStatement()) {
					s.execute("SET FOREIGN_KEY_CHECKS = 1");
				}
			}
		}
	}
}
