package org.a1tsign.vds.api

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

const val DEFAULT_PAGE_SIZE: Int = 20
const val MAX_PAGE_SIZE: Int = 100

/** Appended to every sort as the final key -- see [pageRequest]. */
const val TIE_BREAKER_FIELD: String = "id"

/** The one list-response shape for every paged endpoint. */
@Serializable
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** One parsed `sort` key. [descending] maps to `DESC`, otherwise `ASC`. */
data class SortField(val field: String, val descending: Boolean)

/** Validated `page`/`size`/`sort` of one request -- see [pageRequest]. */
data class PageRequest(val page: Int, val size: Int, val sort: List<SortField>)

fun <T> pageResponse(total: Long, page: Int, size: Int, items: List<T>): PageResponse<T> =
    PageResponse(
        content = items,
        page = page,
        size = size,
        totalElements = total,
        // Rounds up; an empty result has zero pages, not one empty page.
        totalPages = if (total <= 0L || size <= 0) 0 else ((total + size - 1) / size).toInt(),
    )

/**
 * Parses `page` (0-based, default 0), `size` (default [DEFAULT_PAGE_SIZE], at most
 * [MAX_PAGE_SIZE]) and `sort` (`field` or `field,asc|desc`, default direction `asc`) off the query
 * string, reporting **every** bad parameter at once as a `400`.
 *
 * An out-of-range value is rejected, never clamped: a client that asked for 1000 rows and silently
 * got 100 cannot tell it is paging through a truncated view.
 *
 * [sortableFields] is the endpoint's own whitelist -- anything else is a `400`, so a `sort`
 * parameter can never reach a SQL `ORDER BY` as free text.
 *
 * The result always ends with `id ASC`: without a unique final key, rows with equal sort values
 * come back in whatever order the database picks per query, so a row can appear on two pages or on
 * none while the client walks them.
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
