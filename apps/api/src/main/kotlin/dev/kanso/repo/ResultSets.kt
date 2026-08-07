package dev.kanso.repo

import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// JDBC getters return platform types; these keep nullability honest at the
// mapping boundary instead of scattering !! through every row mapper.

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)!!

internal fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

internal fun ResultSet.timestamp(column: String): OffsetDateTime =
	getObject(column, OffsetDateTime::class.java)!!

internal fun ResultSet.timestampOrNull(column: String): OffsetDateTime? =
	getObject(column, OffsetDateTime::class.java)

internal fun ResultSet.dateOrNull(column: String): LocalDate? = getDate(column)?.toLocalDate()

internal fun ResultSet.text(column: String): String = getString(column)!!
