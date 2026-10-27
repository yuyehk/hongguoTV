// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** A website title match supplies navigation, never proof of a shared season identity. */
object SeasonFamilies {
    fun keyword(current: Series): String = SeasonContinuation.searchTitle(current.title).take(80)

    fun merge(current: Series, official: List<RelatedWork>, searched: List<Series>): List<RelatedWork> {
        val family = SeasonContinuation.baseTitle(current.title)
        val matches = searched.filter {
            family.isNotBlank() && it.id.matches(Regex("[0-9]{1,30}")) &&
                it.id != current.id && SeasonContinuation.baseTitle(it.title) == family
        }.map {
            val number = SeasonContinuation.season(it.title, it.badge, it.tags)
            RelatedWork(it, number?.let { n -> "第${n}季" }.orEmpty(), false, confirmed = false)
        }
        return (official + matches).filter { it.series.id != current.id }.distinctBy { it.series.id }
            .sortedWith(compareBy<RelatedWork> {
                SeasonContinuation.season(it.series.title, it.season) ?: Int.MAX_VALUE
            }.thenBy { it.series.title }.thenBy { it.series.id })
    }
}

data class RelatedWorksPage(
    val items: List<RelatedWork>,
    val nextPage: Int?,
    val searchComplete: Boolean,
    val message: String,
)
