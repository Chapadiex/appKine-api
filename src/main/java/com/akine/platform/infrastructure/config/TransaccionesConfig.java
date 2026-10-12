package com.akine.platform.infrastructure.config;

import com.akine.platform.spi.transaccion.AlTerminarLaTransaccion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * El transaction manager de la aplicacion: el de JPA que Spring Boot configuraria, mas el aviso a
 * {@link AlTerminarLaTransaccion} cuando una transaccion fisica empieza y cuando ya devolvio su
 * conexion.
 *
 * <p>Reemplaza al de la autoconfiguracion —que es {@code @ConditionalOnMissingBean}— y le aplica
 * los mismos {@link TransactionManagerCustomizers}, asi que {@code spring.transaction.*} sigue
 * valiendo.
 */
@Configuration(proxyBeanMethods = false)
public class TransaccionesConfig {

	@Bean
	public JpaTransactionManager transactionManager(
			ObjectProvider<TransactionManagerCustomizers> customizers) {
		JpaTransactionManager transactionManager = new AvisaAlTerminar();
		customizers.ifAvailable(customizer -> customizer.customize(transactionManager));
		return transactionManager;
	}

	/**
	 * {@code doBegin} y {@code doCleanupAfterCompletion} corren solo para transacciones
	 * <b>fisicas</b> —la de afuera y cada {@code REQUIRES_NEW}—, nunca para las que se unen a una
	 * existente. La limpieza cierra el {@code EntityManager}, que devuelve la conexion: lo que corre
	 * despues de {@code super} ya no tiene ninguna tomada por esta transaccion.
	 */
	static final class AvisaAlTerminar extends JpaTransactionManager {

		private static final long serialVersionUID = 1L;

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
			super.doBegin(transaction, definition);
			AlTerminarLaTransaccion.empezo();
		}

		@Override
		protected void doCleanupAfterCompletion(Object transaction) {
			try {
				super.doCleanupAfterCompletion(transaction);
			} finally {
				AlTerminarLaTransaccion.termino();
			}
		}
	}
}
