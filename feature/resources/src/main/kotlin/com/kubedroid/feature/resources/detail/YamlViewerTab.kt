package com.kubedroid.feature.resources.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.kubedroid.feature.resources.R

@Composable
fun YamlViewerTab(
    yaml: String,
    modifier: Modifier = Modifier,
) {
    val lines = if (yaml.isBlank()) emptyList() else yaml.lines()
    val contentPadding = dimensionResource(id = R.dimen.resource_detail_tab_content_padding)
    val rowPadding = dimensionResource(id = R.dimen.resource_detail_yaml_row_padding)
    val rowSpacing = dimensionResource(id = R.dimen.resource_detail_yaml_row_spacing)
    val numberWidth = dimensionResource(id = R.dimen.resource_detail_yaml_line_number_width)
    val corner = dimensionResource(id = R.dimen.resource_detail_yaml_corner_radius)

    if (lines.isEmpty()) {
        Text(
            text = stringResource(id = R.string.yaml_viewer_empty),
            modifier = modifier.padding(contentPadding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val palette = rememberYamlHighlightPalette()

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = contentPadding)
            .background(color = background, shape = RoundedCornerShape(corner)),
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        itemsIndexed(lines) { index, line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = contentPadding, vertical = rowPadding),
            ) {
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier
                        .width(numberWidth)
                        .padding(end = rowSpacing),
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = highlightYamlLine(
                        line = line,
                        palette = palette,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun rememberYamlHighlightPalette(): YamlHighlightPalette = YamlHighlightPalette(
    key = MaterialTheme.colorScheme.primary,
    valueString = MaterialTheme.colorScheme.tertiary,
    valueNumber = MaterialTheme.colorScheme.error,
    valueKeyword = MaterialTheme.colorScheme.secondary,
    punctuation = MaterialTheme.colorScheme.onSurface,
    comment = MaterialTheme.colorScheme.onSurfaceVariant,
)

private data class YamlHighlightPalette(
    val key: Color,
    val valueString: Color,
    val valueNumber: Color,
    val valueKeyword: Color,
    val punctuation: Color,
    val comment: Color,
)

private fun highlightYamlLine(
    line: String,
    palette: YamlHighlightPalette,
): AnnotatedString {
    val trimmedStart = line.trimStart()
    if (trimmedStart.startsWith("#")) {
        return buildAnnotatedString {
            appendStyled(
                text = line,
                color = palette.comment,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
        }
    }

    val keyValueMatch = KEY_VALUE_REGEX.matchEntire(line)
    if (keyValueMatch != null) {
        val (indent, key, value) = keyValueMatch.destructured
        return buildAnnotatedString {
            append(indent)
            appendStyled(text = key, color = palette.key, weight = FontWeight.SemiBold)
            appendStyled(text = ":", color = palette.punctuation)
            if (value.isNotBlank()) {
                append(" ")
                appendValue(value, palette)
            }
        }
    }

    val listItemMatch = LIST_ITEM_REGEX.matchEntire(line)
    if (listItemMatch != null) {
        val (indent, value) = listItemMatch.destructured
        return buildAnnotatedString {
            append(indent)
            appendStyled(text = "-", color = palette.punctuation, weight = FontWeight.Bold)
            if (value.isNotBlank()) {
                append(" ")
                appendValue(value, palette)
            }
        }
    }

    return buildAnnotatedString {
        appendValue(line, palette)
    }
}

private fun AnnotatedString.Builder.appendValue(
    value: String,
    palette: YamlHighlightPalette,
) {
    when {
        value.startsWith("\"") || value.startsWith("'") -> {
            appendStyled(text = value, color = palette.valueString)
        }

        KEYWORD_VALUES.contains(value.lowercase()) -> {
            appendStyled(text = value, color = palette.valueKeyword, weight = FontWeight.Medium)
        }

        NUMBER_REGEX.matches(value) -> {
            appendStyled(text = value, color = palette.valueNumber)
        }

        value.startsWith("[") || value.startsWith("{") -> {
            appendStyled(text = value, color = palette.punctuation)
        }

        else -> {
            appendStyled(text = value, color = palette.punctuation)
        }
    }
}

private fun AnnotatedString.Builder.appendStyled(
    text: String,
    color: Color,
    weight: FontWeight? = null,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
) {
    pushStyle(
        SpanStyle(
            color = color,
            fontWeight = weight,
            fontStyle = fontStyle,
        ),
    )
    append(text)
    pop()
}

private val KEY_VALUE_REGEX = Regex("^(\\s*)([A-Za-z0-9_.\\-\"']+):(?:\\s*(.*))?$")
private val LIST_ITEM_REGEX = Regex("^(\\s*)-(?:\\s*(.*))?$")
private val NUMBER_REGEX = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")
private val KEYWORD_VALUES = setOf("true", "false", "null", "~")
