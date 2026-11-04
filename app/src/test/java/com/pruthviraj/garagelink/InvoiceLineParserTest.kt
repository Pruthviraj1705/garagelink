package com.pruthviraj.garagelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceLineParserTest {

    private fun parse(vararg lines: String) = InvoiceLineParser.parse(lines.joinToString("\n"))

    // ---- empty and noisy input ----------------------------------------------------------

    @Test
    fun blankInputGivesNoRows() {
        assertTrue(InvoiceLineParser.parse("").isEmpty())
        assertTrue(InvoiceLineParser.parse("  \n \n\t\n").isEmpty())
    }

    @Test
    fun headersFootersAndTotalsAreDropped() {
        val rows = parse(
            "Tax Invoice",
            "Invoice No 4471",
            "Date: 12/05/2026",
            "Brake pad 2 150.50",
            "Subtotal 301.00",
            "GST 18%",
            "Total 355.18",
            "Signature"
        )
        assertEquals(1, rows.size)
        assertEquals("Brake pad", rows[0].itemNameGuess)
    }

    @Test
    fun singleCharacterLinesAreIgnored() {
        val rows = parse("A", "Brake pad 2 150")
        assertEquals(1, rows.size)
    }

    // ---- one item per line: "name qty price" ----------------------------------------------

    @Test
    fun lineWithQuantityAndPriceIsSplitCorrectly() {
        val row = parse("Brake pad 2 150.50").single()
        assertEquals("Brake pad 2 150.50", row.rawText)
        assertEquals("Brake pad", row.itemNameGuess)
        assertEquals(2, row.quantityGuess)
        assertEquals(150.50, row.priceGuess!!, 1e-9)
    }

    @Test
    fun lineWithOnlyOneNumberIsTreatedAsQuantity() {
        val row = parse("Spark plug 4").single()
        assertEquals("Spark plug", row.itemNameGuess)
        assertEquals(4, row.quantityGuess)
        assertNull(row.priceGuess)
    }

    @Test
    fun lineWithNoNumberDefaultsToQuantityOne() {
        // letters only, nothing after it: becomes a name with the default quantity
        val row = parse("Air filter").single()
        assertEquals("Air filter", row.itemNameGuess)
        assertEquals(1, row.quantityGuess)
        assertNull(row.priceGuess)
    }

    @Test
    fun quantityOutsideOneToNineNinetyNineFallsBackToOne() {
        assertEquals(1, parse("Chain 1000").single().quantityGuess)
        assertEquals(1, parse("Oil 0").single().quantityGuess)
        assertEquals(999, parse("Oil 999").single().quantityGuess)
    }

    @Test
    fun withThreeNumbersTheSecondLastIsTheQuantityAndTheLastIsThePrice() {
        val row = parse("Filter 10 2 99").single()
        assertEquals(2, row.quantityGuess)
        assertEquals(99.0, row.priceGuess!!, 1e-9)
    }

    @Test
    fun punctuationInTheNameIsCleanedUp() {
        val row = parse("Brake-pad (front) 2 100").single()
        assertEquals("Brake pad front", row.itemNameGuess)
    }

    @Test
    fun severalItemLinesBecomeSeveralRows() {
        val rows = parse("Brake pad 2 150", "Clutch cable 1 80", "Spark plug 4 45.5")
        assertEquals(listOf("Brake pad", "Clutch cable", "Spark plug"), rows.map { it.itemNameGuess })
        assertEquals(listOf(2, 1, 4), rows.map { it.quantityGuess })
    }

    // ---- column layout: all names first, then the numbers -------------------------------

    @Test
    fun namesFollowedByANumberColumnArePairedByPosition() {
        val rows = parse("Brake pad", "Clutch cable", "10", "25")
        assertEquals(listOf("Brake pad", "Clutch cable"), rows.map { it.itemNameGuess })
        assertEquals(listOf(10, 25), rows.map { it.quantityGuess })
        assertTrue(rows.all { it.priceGuess == null })
    }

    @Test
    fun namesWithoutEnoughNumbersFallBackToQuantityOne() {
        val rows = parse("Brake pad", "Clutch cable", "10")
        assertEquals(listOf(10, 1), rows.map { it.quantityGuess })
    }

    @Test
    fun aNumberWithNoItemNameBeforeItIsSkipped() {
        val rows = parse("25", "Brake pad 2 100")
        assertEquals(1, rows.size)
        assertEquals("Brake pad", rows[0].itemNameGuess)
    }
}
