package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class SeasonContinuationTest {
    private val current=Series("100","归来 第一季")
    private fun work(id: String, title: String, season: String="", upcoming: Boolean=false)=
        RelatedWork(Series(id,title),season,upcoming)

    @Test fun choosesLowestReleasedHigherSeasonWithChineseOrArabicMarkers() {
        val second=work("200","归来（第２季）","第二季")
        assertEquals(second.series,SeasonContinuation.next(current,listOf(
            work("300","归来 第三季"), second, work("50","归来 第零季"))))
    }

    @Test fun officialSeasonTagAllowsSameBaseTitleWithoutTitleSeasonSuffix() {
        val second=work("200","归来","第2季")
        assertEquals(second.series,SeasonContinuation.next(current,listOf(second)))
    }

    @Test fun currentSeasonCanComeFromPersistedSeriesBadgeOrTags() {
        val second=work("200","归来 第二季")
        assertEquals(second.series,SeasonContinuation.next(Series("100","归来",badge="第一季"),listOf(second)))
        assertEquals(second.series,SeasonContinuation.next(Series("100","归来",tags="短剧 · 第1季"),listOf(second)))
    }

    @Test fun supportsChineseTensAndPartMarkers() {
        val next=work("1300","归来 第十三部","第十三部")
        assertEquals(next.series,SeasonContinuation.next(Series("1200","归来 第十二部"),listOf(next)))
    }

    @Test fun upcomingSamePreviousAndCurrentIdAreNeverContinued() {
        assertNull(SeasonContinuation.next(current,listOf(
            work("200","归来 第二季",upcoming=true),
            work("300","归来 第一季"),work("100","归来 第三季"))))
    }

    @Test fun similarlyNamedAndUnrelatedTitlesAreNotGuessed() {
        assertNull(SeasonContinuation.next(current,listOf(
            work("200","归来之后 第二季"),work("300","归来之谜","第二季"))))
    }

    @Test fun unnumberedCurrentOrCandidateDoesNotInventASeason() {
        assertNull(SeasonContinuation.next(Series("100","归来"),listOf(work("200","归来 第二季"))))
        assertNull(SeasonContinuation.next(current,listOf(work("200","归来"))))
    }

    @Test fun conflictingTitleAndOfficialSeasonMetadataIsRejected() {
        assertNull(SeasonContinuation.next(current,listOf(work("200","归来 第二季","第三季"))))
        assertNull(SeasonContinuation.next(current.copy(tags="第三季"),listOf(work("200","归来 第二季"))))
    }

    @Test fun multipleWorksAtTheNextSeasonAreAmbiguousButDuplicateSameIdIsFine() {
        val second=work("200","归来 第二季")
        assertNull(SeasonContinuation.next(current,listOf(second,work("201","归来 第二季"))))
        assertEquals(second.series,SeasonContinuation.next(current,listOf(second,second)))
    }

    @Test fun invalidSeriesIdentifiersAndEmptyNormalizedTitlesAreRejected() {
        assertNull(SeasonContinuation.next(current,listOf(work("invalid","归来 第二季"))))
        assertNull(SeasonContinuation.next(Series("100","第一季"),listOf(work("200","第二季"))))
    }

    @Test fun punctuationAndSpacingCanVaryWithoutSubstringMatching() {
        val next=work("200","《归来》：第2季")
        assertEquals(next.series,SeasonContinuation.next(Series("100","《归来》 第1季"),listOf(next)))
    }
}
