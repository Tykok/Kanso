package dev.kanso.config

import org.jetbrains.exposed.v1.spring.transaction.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Exposed statements join whatever Spring transaction is already open, so
 * `@Transactional` is the only transaction boundary in the codebase — there is
 * no second `transaction { }` idiom to keep in step.
 *
 * Because this manager extends Spring's own, the raw `JdbcClient` statements
 * (recursive CTE, SKIP LOCKED claim, pg_notify) run on the same connection and
 * commit or roll back together with the Exposed ones.
 */
@Configuration
@EnableTransactionManagement
class ExposedConfig {

	@Bean
	fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
		SpringTransactionManager(dataSource)

	/**
	 * The sync worker needs explicit boundaries rather than `@Transactional`: it
	 * reads in one transaction, calls Notion with none open — holding a connection
	 * across a network round trip would tie up the pool for the length of an HTTP
	 * request — then records the outcome in another.
	 */
	@Bean
	fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
		TransactionTemplate(transactionManager)
}
