package com.akine.resource.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.JdbcType;

/**
 * Le dice a Hibernate que la columna es {@code TIME} (para {@code ddl-auto: validate}), pero
 * transmite el valor como texto (para que {@link HoraLocalConverter} pueda seguir escribiendo
 * {@code '24:00:00'} sin que el driver intente construir un tipo temporal por su cuenta).
 *
 * <h2>Por que hace falta esta clase y no alcanza con el converter solo</h2>
 *
 * <p>{@link HoraLocalConverter} convierte {@code LocalTime <-> String}. Sin nada mas, Hibernate
 * infiere el tipo JDBC de la columna a partir del tipo relacional del converter —{@code String}
 * implica {@code VARCHAR}— y {@code ddl-auto: validate} lo compara contra el tipo REAL de la
 * columna en la base, que es {@code TIME}. La comparacion falla con
 * {@code SchemaManagementException: wrong column type ... found [time], but expecting [varchar]}
 * apenas arranca cualquier contexto de Spring que incluya estas entidades, aunque el converter
 * funcione perfecto en runtime.
 *
 * <p>Este {@link JdbcType} resuelve la contradiccion separando las dos preguntas que Hibernate
 * mezcla por defecto:
 *
 * <ul>
 *   <li>{@link #getJdbcTypeCode()} devuelve {@code SqlTypes.TIME}: es lo que la validacion de
 *       esquema compara contra la columna real, y ahora coincide.</li>
 *   <li>{@link #getBinder} y {@link #getExtractor} siguen moviendo texto plano con
 *       {@code setString}/{@code getString}, exactamente el mismo mecanismo que ya prueba
 *       {@code DisponibilidadMigrationIT} contra SQL crudo: MySQL acepta el cast implicito de
 *       {@code VARCHAR} a {@code TIME} en la asignacion, incluido {@code '24:00:00'}.</li>
 * </ul>
 *
 * <p>Se aplica con {@code @org.hibernate.annotations.JdbcType(HoraJdbcType.class)} junto al
 * {@code @Convert(converter = HoraLocalConverter.class)} en cada campo {@code LocalTime} que
 * mapea una columna {@code TIME} de V23. El converter sigue siendo el unico lugar que sabe de
 * {@link IntervaloLocal#FIN_DE_DIA}; esta clase no conoce esa regla, solo mueve texto.
 */
public class HoraJdbcType implements JdbcType {

	public static final HoraJdbcType INSTANCE = new HoraJdbcType();

	@Override
	public int getJdbcTypeCode() {
		return SqlTypes.TIME;
	}

	@Override
	public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
		return new BasicBinder<>(javaType, this) {
			@Override
			protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
					throws SQLException {
				st.setString(index, javaType.unwrap(value, String.class, options));
			}

			@Override
			protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
					throws SQLException {
				st.setString(name, javaType.unwrap(value, String.class, options));
			}
		};
	}

	@Override
	public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
		return new BasicExtractor<>(javaType, this) {
			@Override
			protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
				return javaType.wrap(rs.getString(paramIndex), options);
			}

			@Override
			protected X doExtract(CallableStatement statement, int index, WrapperOptions options)
					throws SQLException {
				return javaType.wrap(statement.getString(index), options);
			}

			@Override
			protected X doExtract(CallableStatement statement, String name, WrapperOptions options)
					throws SQLException {
				return javaType.wrap(statement.getString(name), options);
			}
		};
	}
}
