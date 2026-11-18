package org.a1tsign.vds.api

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

const val DEFAULT_PAGE_SIZE: Int = 20
const val MAX_PAGE_SIZE: Int = 100

const val TIE_BREAKER_FIELD: String = "id"

@Serializable
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class SortField(val field: String, val descending: Boolean)

data class PageRequest(val page: Int, val size: Int, val sort: List<SortField>)

fun <T> pageResponse(total: Long, page: Int, size: Int, items: List<T>): PageResponse<T> =
    PageResponse(
        content = items,
        page = page,
        size = size,
        totalElements = total,
        totalPages = if (total <= 0L || size <= 0) 0 else ((total + size - 1) / size).toInt(),
    )

/**
 * Parse zero-based pagination and whitelisted sort keys. Report all invalid inputs together as a 400.
 * Append a unique ID tie-breaker so equal sort values cannot drift between pages.
 */
fun ApplicationCall.pageRequest(sortableFields: Set<String>): PageRequest {
    val validation = Validation()
    val query = request.queryParameters

    val pageRaw = query["page"]
    val page = if (pageRaw == null) {
        0
    } else {
        pageRaw.toIntOrNull()?.takeIf { it >= 0 } ?: run {
            validation.reject("page", "must be zero or greater")
            0
        }
    }

    val sizeRaw = query["size"]
    val size = if (sizeRaw == null) {
        DEFAULT_PAGE_SIZE
    } else {
        sizeRaw.toIntOrNull()?.takeIf { it in 1..MAX_PAGE_SIZE } ?: run {
            validation.reject("size", "must be between 1 and $MAX_PAGE_SIZE")
            DEFAULT_PAGE_SIZE
        }
    }

    val sort = mutableListOf<SortField>()
    val sortRaw = query["sort"]
    if (sortRaw != null) {
        val parts = sortRaw.split(",")
        val field = parts[0]
        val direction = parts.getOrNull(1)?.lowercase()
        when {
            parts.size > 2 ->
                validation.reject("sort", "must be 'field' or 'field,asc|desc'")
            field !in sortableFields ->
                validation.reject(
                    "sort",
                    "unknown sort field '$field', allowed: ${sortableFields.sorted().joinToString(", ")}",
                )
            direction != null && direction != "asc" && direction != "desc" ->
                validation.reject("sort", "direction must be 'asc' or 'desc'")
            else ->
                sort += SortField(field, descending = direction == "desc")
        }
    }
    if (sort.none { it.field == TIE_BREAKER_FIELD }) {
        sort += SortField(TIE_BREAKER_FIELD, descending = false)
    }

    validation.throwIfInvalid()
    return PageRequest(page = page, size = size, sort = sort.toList())
}
