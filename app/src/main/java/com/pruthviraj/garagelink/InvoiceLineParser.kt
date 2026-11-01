package com.pruthviraj.garagelink

data class ParsedInvoiceRow(
    val rawText: String,
    val itemNameGuess: String,
    val quantityGuess: Int,
    val priceGuess: Double?
)

object InvoiceLineParser {
    private val numberRegex = Regex("""\d+(\.\d+)?""")
    private val noiseWords =
        listOf("invoice", "date", "total", "subtotal", "gst", "tax", "signature")

    fun parse(rawOcrText: String): List<ParsedInvoiceRow> {
        val lines = rawOcrText.lines().map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 && noiseWords.none { w -> it.lowercase().contains(w) } }

        data class Classified(val text: String, val hasLetters: Boolean, val hasDigits: Boolean)
        val classified = lines.map { Classified(it, it.any { c -> c.isLetter() }, it.any { c -> c.isDigit() }) }

        val rows = mutableListOf<ParsedInvoiceRow>()
        var i = 0
        while (i < classified.size) {
            val line = classified[i]
            when {
                line.hasLetters && line.hasDigits -> {
                    rows.add(parseMixedLine(line.text))
                    i++
                }
                line.hasLetters -> {
                    val nameBlock = mutableListOf<String>()
                    var j = i
                    while (j < classified.size && classified[j].hasLetters && !classified[j].hasDigits) {
                        nameBlock.add(classified[j].text); j++
                    }
                    val numberBlock = mutableListOf<String>()
                    var k = j
                    while (k < classified.size && !classified[k].hasLetters && classified[k].hasDigits) {
                        numberBlock.add(classified[k].text); k++
                    }
                    nameBlock.forEachIndexed { idx, name ->
                        val qty = numberBlock.getOrNull(idx)?.toDoubleOrNull()?.toInt()?.takeIf { it in 1..999 } ?: 1
                        rows.add(ParsedInvoiceRow(name, name, qty, null))
                    }
                    i = k
                }
                else -> i++ // orphan number with no preceding name, skip
            }
        }
        return rows
    }

    private fun parseMixedLine(line: String): ParsedInvoiceRow {
        val numbers = numberRegex.findAll(line).map { it.value }.toList()
        val (qty, price) = when (numbers.size) {
            0 -> 1 to null
            1 -> (numbers[0].toDoubleOrNull()?.toInt()?.takeIf { it in 1..999 } ?: 1) to null
            else -> {
                val q = numbers[numbers.size - 2].toIntOrNull()?.takeIf { it in 1..999 } ?: 1
                q to numbers.last().toDoubleOrNull()
            }
        }
        val name = line.replace(numberRegex, "").replace(Regex("""[^A-Za-z ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        return ParsedInvoiceRow(line, name.ifBlank { line }, qty, price)
    }
}