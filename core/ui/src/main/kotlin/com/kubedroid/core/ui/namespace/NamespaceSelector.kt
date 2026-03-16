package com.kubedroid.core.ui.namespace

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubedroid.core.ui.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamespaceSelector(
    namespaces: List<String>,
    selectedNamespace: String?,
    onNamespaceSelected: (String?) -> Unit,
    isSheetVisible: Boolean,
    onSheetVisibilityChange: (Boolean) -> Unit,
    allowAllNamespaces: Boolean,
    modifier: Modifier = Modifier,
) {
    val isAllNamespacesSelected = selectedNamespace.isAllNamespacesSelection() || selectedNamespace.isNullOrBlank()
    val selectorText = when {
        isAllNamespacesSelected -> {
            if (allowAllNamespaces) {
                stringResource(id = R.string.namespace_selector_all_namespaces)
            } else {
                namespaces.firstOrNull().orEmpty()
            }
        }
        else -> selectedNamespace
    }

    TextButton(
        onClick = { onSheetVisibilityChange(true) },
        modifier = modifier,
        enabled = namespaces.isNotEmpty(),
    ) {
        Text(
            text = selectorText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
        )
    }

    if (isSheetVisible) {
        val sheetPadding = dimensionResource(id = R.dimen.namespace_selector_title_padding)
        ModalBottomSheet(onDismissRequest = { onSheetVisibilityChange(false) }) {
            Text(
                text = stringResource(id = R.string.namespace_selector_switch_namespace),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(sheetPadding),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (allowAllNamespaces) {
                    item {
                        val isSelected = isAllNamespacesSelected
                        TextButton(
                            onClick = {
                                onNamespaceSelected(ALL_NAMESPACES)
                                onSheetVisibilityChange(false)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(id = R.string.namespace_selector_all_namespaces),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                items(items = namespaces, key = { namespace -> namespace }) { namespace ->
                    val isSelected = namespace == selectedNamespace
                    TextButton(
                        onClick = {
                            onNamespaceSelected(namespace)
                            onSheetVisibilityChange(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = namespace,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val ALL_NAMESPACES = "__all__"

private fun String?.isAllNamespacesSelection(): Boolean = this?.trim() == ALL_NAMESPACES
