package com.akine.platform.infrastructure.security;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;

import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Que es una falla transitoria de la base (503) y que es un bug (500).
 *
 * <p>La linea importa: un 503 le dice al cliente "reintente", y reintentar algo que va a fallar
 * siempre —dos memberships vigentes, una transaccion en un estado imposible— esconde el defecto
 * detras de un error que parece pasajero.
 */
class FallaEnFiltroTest {

	@Test
	@DisplayName("las fallas de conexion son de infraestructura: 503")
	void las_fallas_de_conexion_son_503() {
		assertThat(FallaEnFiltro.esDeInfraestructura(
				new CannotCreateTransactionException("Could not open JPA EntityManager",
						new SQLTransientConnectionException("Connection is not available, request timed out"))))
				.isTrue();
		assertThat(FallaEnFiltro.esDeInfraestructura(
				new DataAccessResourceFailureException("Communications link failure"))).isTrue();
		assertThat(FallaEnFiltro.esDeInfraestructura(
				new JDBCConnectionException("caida", new SQLException("caida", "08S01")))).isTrue();
		assertThat(FallaEnFiltro.esDeInfraestructura(
				new IllegalStateException("envuelta", new SQLException("sin conexion", "08001")))).isTrue();
	}

	@Test
	@DisplayName("las de persistencia que delatan un bug no son 503: dos memberships vigentes es un 500")
	void los_bugs_de_persistencia_no_son_503() {
		assertThat(FallaEnFiltro.esDeInfraestructura(new NonUniqueResultException("dos vigentes"))).isFalse();
		assertThat(FallaEnFiltro.esDeInfraestructura(
				new IncorrectResultSizeDataAccessException("dos vigentes", 1,
						new NonUniqueResultException("dos vigentes")))).isFalse();
		assertThat(FallaEnFiltro.esDeInfraestructura(new NoResultException("nada"))).isFalse();
		assertThat(FallaEnFiltro.esDeInfraestructura(new IllegalTransactionStateException("anidada"))).isFalse();
		assertThat(FallaEnFiltro.esDeInfraestructura(new UnexpectedRollbackException("rollback"))).isFalse();
		assertThat(FallaEnFiltro.esDeInfraestructura(
				new IllegalStateException("bug", new SQLException("sintaxis", "42000")))).isFalse();
	}
}
