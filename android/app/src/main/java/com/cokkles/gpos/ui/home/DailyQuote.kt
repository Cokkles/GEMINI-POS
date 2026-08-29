package com.cokkles.gpos.ui.home

import java.time.LocalDate

data class DailyQuote(
    val text: String,
    val attribution: String? = null,
)

private val dailyQuotes = listOf(
    DailyQuote("Start where you are. Use what you have. Do what you can.", "Arthur Ashe"),
    DailyQuote("The secret of getting ahead is getting started.", "Mark Twain"),
    DailyQuote("Well begun is half done.", "Aristotle"),
    DailyQuote("Great things are done by a series of small things brought together.", "Vincent van Gogh"),
    DailyQuote("Nothing will work unless you do.", "Maya Angelou"),
    DailyQuote("Action is the foundational key to all success.", "Pablo Picasso"),
    DailyQuote("Success is the sum of small efforts, repeated day in and day out.", "Robert Collier"),
    DailyQuote("What you do today can improve all your tomorrows.", "Ralph Marston"),
    DailyQuote("It always seems impossible until it's done.", "Nelson Mandela"),
    DailyQuote("Focus on the step in front of you, not the whole staircase."),
    DailyQuote("Make the next decision a useful one."),
    DailyQuote("Progress compounds when you keep showing up."),
)

fun quoteFor(date: LocalDate = LocalDate.now()): DailyQuote {
    val index = (date.toEpochDay() % dailyQuotes.size).toInt().let { if (it < 0) it + dailyQuotes.size else it }
    return dailyQuotes[index]
}
