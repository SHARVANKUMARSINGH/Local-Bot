package com.localbots.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localbots.app.theme.TerminalBackground
import com.localbots.app.theme.TerminalCyan
import com.localbots.app.theme.TerminalGreen
import com.localbots.app.theme.TerminalOrange

@Composable
fun MarkdownRenderer(
    markdownText: String,
    modifier: Modifier = Modifier
) {
    val lines = markdownText.lines()
    var inCodeBlock = false
    var codeLanguage = ""
    val codeBlockBuffer = StringBuilder()

    Column(modifier = modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    // Close code block
                    RenderCodeBlock(
                        code = codeBlockBuffer.toString().trimEnd(),
                        language = codeLanguage
                    )
                    codeBlockBuffer.clear()
                    inCodeBlock = false
                } else {
                    // Start code block
                    codeLanguage = line.trim().removePrefix("```").trim()
                    inCodeBlock = true
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeBlockBuffer.append(line).append("\n")
                i++
                continue
            }

            // Normal Markdown Lines
            when {
                line.startsWith("# ") -> {
                    Text(
                        text = parseInlineFormatting(line.removePrefix("# ").trim()),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                line.startsWith("## ") -> {
                    Text(
                        text = parseInlineFormatting(line.removePrefix("## ").trim()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                line.startsWith("### ") -> {
                    Text(
                        text = parseInlineFormatting(line.removePrefix("### ").trim()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                line.trim().startsWith("- ") || line.trim().startsWith("* ") -> {
                    Row(modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)) {
                        Text(
                            text = "• ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = parseInlineFormatting(line.trim().substring(2)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                line.contains("|") && line.trim().startsWith("|") -> {
                    // Simple table line
                    RenderTableRow(line)
                }
                line.isBlank() -> {
                    Spacer(modifier = Modifier.height(6.dp))
                }
                else -> {
                    Text(
                        text = parseInlineFormatting(line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            i++
        }

        if (inCodeBlock && codeBlockBuffer.isNotEmpty()) {
            RenderCodeBlock(
                code = codeBlockBuffer.toString().trimEnd(),
                language = codeLanguage
            )
        }
    }
}

@Composable
fun RenderCodeBlock(code: String, language: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = TerminalBackground
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (language.isNotBlank()) {
                Text(
                    text = language.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalOrange,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    fontFamily = FontFamily.Monospace,
                    color = TerminalGreen
                )
            }
        }
    }
}

@Composable
fun RenderTableRow(rowLine: String) {
    val cells = rowLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    if (cells.all { it.contains("---") || it.contains("-") }) {
        // Divider row
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
        ) {
            cells.forEach { cell ->
                Text(
                    text = parseInlineFormatting(cell),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

fun parseInlineFormatting(text: String): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0

        // Parse **bold**, *italic*, `code`
        val tokenRegex = Regex("(\\*\\*.*?\\*\\*|\\*.*?\\*|`.*?`)")
        val matches = tokenRegex.findAll(text)

        for (match in matches) {
            if (match.range.first > currentIndex) {
                append(text.substring(currentIndex, match.range.first))
            }

            val matchedValue = match.value
            when {
                matchedValue.startsWith("**") && matchedValue.endsWith("**") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(matchedValue.removeSurrounding("**"))
                    }
                }
                matchedValue.startsWith("*") && matchedValue.endsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(matchedValue.removeSurrounding("*"))
                    }
                }
                matchedValue.startsWith("`") && matchedValue.endsWith("`") -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x33888888),
                            color = Color(0xFF00796B),
                            fontSize = 13.sp
                        )
                    ) {
                        append(matchedValue.removeSurrounding("`"))
                    }
                }
            }
            currentIndex = match.range.last + 1
        }

        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}
