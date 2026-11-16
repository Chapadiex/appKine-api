package com.akine.assistant.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ValidadorDeSqlTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"SELECT count(*) AS pacientes FROM personas WHERE es_paciente AND activa",
			"select count(*) from personas;",
			"SELECT p.apellido, sum(d.saldo) AS deuda FROM deudas d JOIN personas p ON p.persona_id = d.persona_id "
					+ "WHERE d.estado IN ('PENDIENTE', 'PARCIAL') GROUP BY p.apellido ORDER BY deuda DESC LIMIT 50",
			"WITH mes AS (SELECT * FROM turnos WHERE date_trunc('month', fecha) = date_trunc('month', CURRENT_DATE)) "
					+ "SELECT estado, count(*) FROM mes GROUP BY estado",
			"SELECT count(*) FROM asistente_sql.personas",
			"SELECT apellido FROM personas WHERE lower(apellido) LIKE lower('%gómez; drop%')",
			"SELECT extract(year FROM alta_en) AS anio, count(*) FROM personas GROUP BY 1",
			"SELECT count(*) FROM turnos WHERE fecha >= CURRENT_DATE - interval '7 days'"})
	void aceptaSelectsSobreLasVistas(String sql) {
		assertThat(ValidadorDeSql.rechazo(sql)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"DELETE FROM personas",
			"UPDATE deudas SET saldo = 0",
			"SELECT 1; DROP TABLE persona",
			"SELECT count(*) FROM persona",
			"SELECT count(*) FROM public.persona",
			"SELECT * FROM pg_catalog.pg_tables",
			"SELECT set_config('akine.asistente.organizacion', '2', true)",
			"SELECT current_setting('akine.asistente.organizacion')",
			"SELECT pg_read_file('/etc/passwd')",
			"SELECT * INTO copia FROM personas",
			"SELECT * FROM personas FOR UPDATE",
			"SELECT * FROM personas -- comentario",
			"SELECT * FROM personas /* x */",
			"SELECT 'x'::regclass",
			"SELECT * FROM information_schema.tables",
			"SELECT * FROM dblink('host=x', 'select 1') AS t(a int)",
			"esto no es sql"})
	void rechazaTodoLoDemas(String sql) {
		assertThat(ValidadorDeSql.rechazo(sql)).isPresent();
	}
}
