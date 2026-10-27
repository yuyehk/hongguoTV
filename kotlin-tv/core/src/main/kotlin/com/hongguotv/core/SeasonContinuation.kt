// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.text.Normalizer
import java.util.Locale

/** Conservative selection from an already fetched official related-work list; performs no I/O. */
object SeasonContinuation {
    private val idPattern=Regex("[0-9]{1,30}")
    private val marker=Regex("第\\s*([0-9零〇一二两三四五六七八九十百]{1,8})\\s*[季部]|\\bseason\\s*([0-9]{1,3})\\b")
    private val punctuation=Regex("[\\p{P}\\p{Z}\\s]+")
    private const val DIGITS="零一二三四五六七八九"

    /**
     * Missing or conflicting numbering and ambiguous matches require a manual choice. In
     * particular, an unnumbered title is not automatically assumed to mean the first season.
     */
    fun next(current: Series, related: List<RelatedWork>): Series? {
        if(!idPattern.matches(current.id)) return null
        val currentSeason=season(current.title,current.badge,current.tags) ?: return null
        val currentTitle=baseTitle(current.title).takeIf(String::isNotBlank) ?: return null
        val candidates=related.mapNotNull { work ->
            if(!work.confirmed || work.upcoming || work.series.id==current.id || !idPattern.matches(work.series.id)) return@mapNotNull null
            val number=season(work.series.title,work.season) ?: return@mapNotNull null
            if(number<=currentSeason || baseTitle(work.series.title)!=currentTitle) return@mapNotNull null
            number to work.series
        }
        val nextSeason=candidates.minOfOrNull { it.first } ?: return null
        return candidates.filter { it.first==nextSeason }.map { it.second }.distinctBy { it.id }.singleOrNull()
    }

    private fun normalized(value: String)=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    internal fun baseTitle(value: String)=punctuation.replace(marker.replace(normalized(value),""),"")

    internal fun searchTitle(value: String)=marker.replace(Normalizer.normalize(value,Normalizer.Form.NFKC),"")
        .trim().trim('《','》','（','）','(',')',':','：',' ','-','·').trim()

    internal fun season(vararg values: String): Int? {
        val numbers=values.flatMap { marker.findAll(normalized(it)).toList() }.map { match ->
            number(match.groupValues[1].ifBlank { match.groupValues[2] }) ?: return null
        }
        return numbers.distinct().singleOrNull()
    }

    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { number -> number in 1..999 } }
        val clean=value.replace('两','二').replace('〇','零')
        var total=0
        var digit=0
        for(character in clean) {
            val unit=when(character) { '十' -> 10; '百' -> 100; else -> 0 }
            if(unit>0) { total+=(if(digit==0) 1 else digit)*unit; digit=0 }
            else { digit=DIGITS.indexOf(character); if(digit<0) return null }
        }
        val number=total+digit
        if(number !in 1..999) return null
        // A canonical round trip rejects malformed/repeated units and ambiguous digit strings.
        return number.takeIf { chinese(it)==clean || (it in 10..19 && "一"+chinese(it)==clean) }
    }

    private fun chinese(number: Int): String = when {
        number<10 -> DIGITS[number].toString()
        number<20 -> "十" + if(number%10==0) "" else DIGITS[number%10].toString()
        number<100 -> DIGITS[number/10]+"十"+if(number%10==0) "" else DIGITS[number%10].toString()
        else -> DIGITS[number/100]+"百"+when(val rest=number%100) {
            0 -> ""
            in 1..9 -> "零"+chinese(rest)
            in 10..19 -> "一"+chinese(rest)
            else -> chinese(rest)
        }
    }
}
