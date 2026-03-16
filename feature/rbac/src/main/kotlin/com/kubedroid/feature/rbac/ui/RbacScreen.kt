package com.kubedroid.feature.rbac.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kubedroid.feature.rbac.R
import com.kubedroid.feature.rbac.domain.model.CanIResult

@Composable
fun RbacRoute(
    viewModel: RbacViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    RbacScreen(
        state = state,
        onIntent = viewModel::onIntent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RbacScreen(
    state: RbacUiState,
    onIntent: (RbacIntent) -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.rbac_screen_padding)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.rbac_title)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = screenPadding),
        ) {
            RbacTabs(
                selectedTab = state.selectedTab,
                onTabSelected = { onIntent(RbacIntent.SelectTab(it)) },
            )

            when (state.selectedTab) {
                RbacTab.Roles -> RolesTab(
                    state = state,
                    onNamespaceSelected = { onIntent(RbacIntent.SelectNamespace(it)) },
                )

                RbacTab.Bindings -> BindingsTab(
                    state = state,
                    onNamespaceSelected = { onIntent(RbacIntent.SelectNamespace(it)) },
                )

                RbacTab.CanI -> CanICheckerTab(
                    state = state,
                    onResourceChange = { onIntent(RbacIntent.UpdateCanIResource(it)) },
                    onVerbSelected = { onIntent(RbacIntent.SelectCanIVerb(it)) },
                    onNamespaceSelected = { onIntent(RbacIntent.SelectCanINamespace(it)) },
                    onCheckClick = { onIntent(RbacIntent.CheckCanI) },
                )
            }
        }
    }
}

@Composable
private fun RbacTabs(
    selectedTab: RbacTab,
    onTabSelected: (RbacTab) -> Unit,
) {
    val tabs = listOf(RbacTab.Roles, RbacTab.Bindings, RbacTab.CanI)
    TabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
        tabs.forEach { tab ->
            val label = when (tab) {
                RbacTab.Roles -> stringResource(id = R.string.rbac_tab_roles)
                RbacTab.Bindings -> stringResource(id = R.string.rbac_tab_bindings)
                RbacTab.CanI -> stringResource(id = R.string.rbac_tab_can_i)
            }
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(text = label) },
            )
        }
    }
}

@Composable
private fun RolesTab(
    state: RbacUiState,
    onNamespaceSelected: (String) -> Unit,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.rbac_section_spacing)
    val itemSpacing = dimensionResource(id = R.dimen.rbac_item_spacing)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        NamespacePicker(
            label = stringResource(id = R.string.rbac_namespace_label),
            selectedNamespace = state.selectedNamespace,
            namespaceOptions = state.namespaceOptions,
            onNamespaceSelected = onNamespaceSelected,
        )

        when {
            state.isLoading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.roles.isEmpty() -> {
                Text(
                    text = stringResource(id = R.string.rbac_roles_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    items(
                        items = state.roles,
                        key = { "${it.namespace.orEmpty()}:${it.name}" },
                    ) { role ->
                        RoleListItem(role = role)
                    }
                }
            }
        }
    }
}

@Composable
fun RoleListItem(
    role: RoleListItemUiModel,
    modifier: Modifier = Modifier,
) {
    val cardPadding = dimensionResource(id = R.dimen.rbac_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.rbac_row_spacing)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = role.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (role.isDangerous) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(text = stringResource(id = R.string.rbac_danger_badge)) },
                    )
                }
            }

            Text(
                text = stringResource(
                    id = R.string.rbac_role_namespace,
                    role.namespace ?: stringResource(id = R.string.rbac_cluster_scope),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(id = R.string.rbac_role_rule_count, role.ruleCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BindingsTab(
    state: RbacUiState,
    onNamespaceSelected: (String) -> Unit,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.rbac_section_spacing)
    val itemSpacing = dimensionResource(id = R.dimen.rbac_item_spacing)
    val rowSpacing = dimensionResource(id = R.dimen.rbac_row_spacing)
    val cardPadding = dimensionResource(id = R.dimen.rbac_item_padding)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        NamespacePicker(
            label = stringResource(id = R.string.rbac_namespace_label),
            selectedNamespace = state.selectedNamespace,
            namespaceOptions = state.namespaceOptions,
            onNamespaceSelected = onNamespaceSelected,
        )

        when {
            state.isLoading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.bindings.isEmpty() -> {
                Text(
                    text = stringResource(id = R.string.rbac_bindings_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    items(
                        items = state.bindings,
                        key = { "${it.namespace.orEmpty()}:${it.name}" },
                    ) { binding ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(cardPadding),
                                verticalArrangement = Arrangement.spacedBy(rowSpacing),
                            ) {
                                Text(
                                    text = binding.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        id = R.string.rbac_binding_role_ref,
                                        binding.roleRefName,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(
                                        id = R.string.rbac_binding_subject_count,
                                        binding.subjectCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        id = R.string.rbac_binding_namespace,
                                        binding.namespace
                                            ?: stringResource(id = R.string.rbac_cluster_scope),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CanICheckerTab(
    state: RbacUiState,
    onResourceChange: (String) -> Unit,
    onVerbSelected: (String) -> Unit,
    onNamespaceSelected: (String?) -> Unit,
    onCheckClick: () -> Unit,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.rbac_section_spacing)
    val rowSpacing = dimensionResource(id = R.dimen.rbac_row_spacing)
    val cardPadding = dimensionResource(id = R.dimen.rbac_item_padding)

    val canSubmit = state.canIResourceInput.isNotBlank() && !state.isCheckingCanI

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        OutlinedTextField(
            value = state.canIResourceInput,
            onValueChange = onResourceChange,
            label = { Text(text = stringResource(id = R.string.rbac_can_i_resource_label)) },
            placeholder = { Text(text = stringResource(id = R.string.rbac_can_i_resource_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OptionPicker(
            label = stringResource(id = R.string.rbac_can_i_verb_label),
            selectedText = state.canIVerb,
            options = SUPPORTED_CAN_I_VERBS,
            onSelect = onVerbSelected,
        )

        val clusterScopeLabel = stringResource(id = R.string.rbac_cluster_scope)
        val namespaceOptions = listOf<String?>(null) + state.namespaceOptions
        OptionPicker(
            label = stringResource(id = R.string.rbac_can_i_namespace_label),
            selectedText = state.canINamespace ?: clusterScopeLabel,
            options = namespaceOptions,
            displayText = { it ?: clusterScopeLabel },
            onSelect = onNamespaceSelected,
        )

        Button(
            onClick = onCheckClick,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isCheckingCanI) {
                CircularProgressIndicator()
            } else {
                Text(text = stringResource(id = R.string.rbac_can_i_check_button))
            }
        }

        if (state.canIResult != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(cardPadding),
                    verticalArrangement = Arrangement.spacedBy(rowSpacing),
                ) {
                    val resultLabel = when (state.canIResult) {
                        CanIResult.Allowed -> stringResource(id = R.string.rbac_can_i_result_allowed)
                        CanIResult.Denied -> stringResource(id = R.string.rbac_can_i_result_denied)
                        CanIResult.Unknown -> stringResource(id = R.string.rbac_can_i_result_unknown)
                    }
                    val resultColor = when (state.canIResult) {
                        CanIResult.Allowed -> MaterialTheme.colorScheme.primary
                        CanIResult.Denied -> MaterialTheme.colorScheme.error
                        CanIResult.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Text(
                        text = resultLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = resultColor,
                    )

                    Text(
                        text = canIExplanationText(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun canIExplanationText(state: RbacUiState): String {
    val summary = state.canICheckSummary
    if (summary == null) {
        return stringResource(id = R.string.rbac_can_i_result_missing_input)
    }

    val scopeLabel = summary.namespace ?: stringResource(id = R.string.rbac_cluster_scope)
    return when (state.canIResult) {
        CanIResult.Allowed -> stringResource(
            id = R.string.rbac_can_i_explanation_allowed,
            summary.verb,
            summary.resource,
            scopeLabel,
        )

        CanIResult.Denied -> stringResource(
            id = R.string.rbac_can_i_explanation_denied,
            summary.verb,
            summary.resource,
            scopeLabel,
        )

        CanIResult.Unknown -> stringResource(id = R.string.rbac_can_i_explanation_unknown)
        null -> stringResource(id = R.string.rbac_can_i_result_missing_input)
    }
}

@Composable
private fun NamespacePicker(
    label: String,
    selectedNamespace: String,
    namespaceOptions: List<String>,
    onNamespaceSelected: (String) -> Unit,
) {
    OptionPicker(
        label = label,
        selectedText = selectedNamespace,
        options = namespaceOptions,
        onSelect = onNamespaceSelected,
    )
}

@Composable
private fun <T> OptionPicker(
    label: String,
    selectedText: String,
    options: List<T>,
    displayText: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = selectedText)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = displayText(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
